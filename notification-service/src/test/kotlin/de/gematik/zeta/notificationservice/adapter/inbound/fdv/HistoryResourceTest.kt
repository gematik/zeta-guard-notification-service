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
package de.gematik.zeta.notificationservice.adapter.inbound.fdv

import de.gematik.zeta.notificationservice.adapter.outbound.dispatch.InMemoryDispatchQueue
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.testsupport.TestClient
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotificationHistory
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEnabledDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import io.mockk.mockk
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Endpoint-level coverage for the message history (`A_29975`–`A_29978`).
 *
 * Identifiers are drawn from the `X1104140xx` range on purpose: every `@QuarkusTest` class in a run shares the same
 * Postgres instance, and `ChannelResourceTest`, `PusherResourceTest` and `NotificationResourceTest` occupy the other
 * ranges.
 */
@QuarkusTest
@WithDatabase
class HistoryResourceTest {
    @BeforeEach
    fun replaceDispatchQueueWithNoop() {
        QuarkusMock.installMockForType(
            mockk<InMemoryDispatchQueue>(relaxed = true),
            InMemoryDispatchQueue::class.java,
        )
    }

    @Test
    fun getNotification_afterSubmission_returnsTheStoredMessage() {
        val identifier = "X110414001"
        registerEnabledDevice(identifier, "hist-single")
        val notificationId = submit(identifier)

        getNotification(identifier, notificationId)
            .statusCode(200)
            .body("identifier", equalTo(notificationId))
            .body("payload.message", equalTo("Hello World!"))
            .body("payload.document_id", equalTo("123"))
    }

    @Test
    fun getNotification_forUnknownId_returns404WithNotificationNotFound() {
        getNotification("X110414002", UUID.randomUUID().toString())
            .statusCode(404)
            .body("errorCode", equalTo("NOTIFICATION_NOT_FOUND"))
    }

    @Test
    fun getNotifications_afterTwoSubmissions_returnsBothInOrder() {
        val identifier = "X110414006"
        registerEnabledDevice(identifier, "hist-list")
        val first = submit(identifier)
        val second = submit(identifier)

        getNotificationHistory(identifier, "hist-list")
            .statusCode(200)
            .body("messages.size()", equalTo(2))
            .body("messages[0].identifier", equalTo(first))
            .body("messages[1].identifier", equalTo(second))
            .body("messages[0].payload.message", equalTo("Hello World!"))
    }

    /** Submits through the RS endpoint and returns the assigned `notification_id`. */
    private fun submit(identifier: String): String =
        TestClient
            .postNotification(identifier)
            .statusCode(202)
            .body("status", equalTo("accepted"))
            .extract()
            .path("notification_id")
}
