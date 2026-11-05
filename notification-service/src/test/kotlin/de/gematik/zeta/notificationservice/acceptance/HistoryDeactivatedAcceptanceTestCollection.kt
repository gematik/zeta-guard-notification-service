/*
 * #%L
 * ZETA Notification Service
 * %%
 * (C) EY Strategy & Transactions GmbH, 2026, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */
package de.gematik.zeta.notificationservice.acceptance

import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectPushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectStorageRecordProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.StorageRecordProbe
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotificationHistory
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEnabledDevice
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The A_29974 and A_29988 scenarios that need the message-history feature switched off.
 * Run twice like [AcceptanceTestCollection]:
 * 1. As normal @QuarkusTests with other unit tests and so on. [HistoryDeactivatedAcceptanceTest]
 * 2. As @QuarkusIntegrationTests in the integration test package against the native image (in the ci)
 *
 * They cannot live in [AcceptanceTestCollection]: `%test` enables the feature and a Quarkus test profile
 * applies to a whole class, so it cannot be scoped to a few of its scenarios. Each runner therefore
 * carries `@TestProfile(HistoryDeactivatedProfile::class)` on top of the usual resource annotations.
 *
 * The same two constraints as [AcceptanceTestCollection] apply: no nested classes, and no CDI.
 */
abstract class HistoryDeactivatedAcceptanceTestCollection {
    @InjectPushGatewayMock
    lateinit var pushGateway: PushGatewayMock

    @InjectStorageRecordProbe
    lateinit var storageRecords: StorageRecordProbe

    @BeforeEach
    fun resetPushGateway() {
        pushGateway.wireMock().resetAll()
        pushGateway.stubDefaultSuccessPlainBatch()
    }

    // A_29974
    @Test
    @DisplayName("A_29974: both history endpoints answer 404 HISTORY_DISABLED while the feature is deactivated")
    fun noHistoryIsServedWhenTheFeatureIsDeactivated() {
        val identifier = "X299880004"
        val pushkey = "history-off"
        registerEnabledDevice(identifier, pushkey)

        val notificationId =
            postNotification(identifier)
                .statusCode(202)
                .extract()
                .path<String>("notification_id")

        assertEquals(1, pushGateway.awaitRequest(pushkey).size) {
            "deactivating the history feature must not stop the notification from being dispatched"
        }

        getNotification(identifier, notificationId)
            .statusCode(404)
            .body("errorCode", equalTo("HISTORY_DISABLED"))

        getNotificationHistory(identifier, pushkey)
            .statusCode(404)
            .body("errorCode", equalTo("HISTORY_DISABLED"))

        getNotificationHistory(identifier, "history-off-unknown")
            .statusCode(404)
            .body("errorCode", equalTo("HISTORY_DISABLED"))
    }

    // A_29988
    @Test
    @DisplayName("A_29988: no notification history is persisted while the message-history feature is deactivated")
    fun noHistoryIsPersistedWhenTheFeatureIsDeactivated() {
        val identifier = "X299880005"
        val pushkey = "history-off-store"
        registerEnabledDevice(identifier, pushkey)

        postNotification(identifier).statusCode(202)
        assertEquals(1, pushGateway.awaitRequest(pushkey).size)

        // That this lookup does find a real record when the feature is on is covered by
        // AcceptanceTestCollection.messageHistoryOutlivesTheProcessingDataRetention.
        assertFalse(storageRecords.historyRecordExists(identifier, pushkey)) {
            "with the feature deactivated no dispatch history may be held at all"
        }
    }
}
