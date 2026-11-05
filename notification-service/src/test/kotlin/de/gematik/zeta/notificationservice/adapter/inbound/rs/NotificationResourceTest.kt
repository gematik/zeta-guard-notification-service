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
package de.gematik.zeta.notificationservice.adapter.inbound.rs

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import de.gematik.zeta.notificationservice.application.repository.NotificationsRepository
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectPushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayTestResource
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithPushGatewayMock
import de.gematik.zeta.notificationservice.testsupport.TestClient
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEnabledDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Endpoint-level coverage for the Resource-Server submission API, including the asynchronous handover
 * to the (WireMock) Push Gateway: [PushGatewayTestResource] puts the mock's URL on the allow-list.
 *
 * Identifiers are drawn from the `X1104130xx` range on purpose: every `@QuarkusTest` class in a run shares the same
 * Postgres instance, and `ChannelResourceTest`, `PusherResourceTest` and `HistoryResourceTest` occupy the other ranges.
 *
 * Push Gateway traffic is asserted per pushkey, never by total request count: delivery is asynchronous,
 * so a straggler from an earlier test may still arrive while the next one runs.
 *
 * `%test` switches the client-certificate check on `POST /notifications` off (it is covered by
 * `RsMtlsAcceptanceTestCollection`), so no credential is sent here — the setup calls to the FdV endpoints need a
 * `zeta-user-info` header.
 */
@QuarkusTest
@WithPushGatewayMock
@WithDatabase
class NotificationResourceTest {
    @Inject
    lateinit var notifications: NotificationsRepository

    @InjectPushGatewayMock
    lateinit var pushGateway: PushGatewayMock

    @BeforeEach
    fun resetPushGateway() {
        pushGateway.wireMock().resetAll()
        pushGateway.stubDefaultSuccessPlainBatch(priority = 10)
    }

    @Test
    fun submitNotification_withNoActiveChannel_neitherContactsThePushGatewayNorPersists() {
        val identifier = "X110413012"
        val pushkey = "notif-terminal"
        registerPusher(identifier, pushkey)

        val notificationId =
            TestClient
                .postNotification(
                    identifier,
                ).statusCode(202)
                .extract()
                .path<String>("notification_id")

        Thread.sleep(300)
        assertEquals(0, pushGateway.findMatchingBatchRequests(pushkey).size)
        assertNull(notifications.findBy(checkNotNull(NotificationId.parseOrNull(notificationId))))
    }

    @Test
    fun submitNotification_dispatchesOneBatchCoveringEveryEnabledDevice() {
        val identifier = "X110413013"
        registerEnabledDevice(identifier, "notif-multi-1")
        registerEnabledDevice(identifier, "notif-multi-2")
        registerPusher(identifier, "notif-multi-3")

        TestClient.postNotification(identifier).statusCode(202)

        val requests = pushGateway.awaitRequest("notif-multi-1")
        assertEquals(1, requests.size)
        val body = requests.single().bodyAsString
        assertTrue(body.contains("notif-multi-2")) { "both enabled devices should share one batch" }
        assertTrue(!body.contains("notif-multi-3")) { "a device without the channel enabled must not be targeted" }
    }

    @Test
    fun submitNotification_withRejectedPushkeysInTheResponse_answers202AndDeregistersTheDevice() {
        val identifier = "X110413014"
        val pushkey = "notif-rejected"
        registerEnabledDevice(identifier, pushkey)
        pushGateway.wireMock().stubFor(
            post(urlPathMatching(".*/notify/batch")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "results": [{"id": "any", "status": "partial", "rejected": ["$pushkey"]}],
                          "summary": {"total": 1, "successful": 0, "failed": 0, "partial": 1}
                        }
                        """.trimIndent(),
                    ),
            ),
        )

        TestClient.postNotification(identifier).statusCode(202).body("status", equalTo("accepted"))

        assertEquals(1, pushGateway.awaitRequest(pushkey).size)
        awaitPusherRemoved(identifier, pushkey)
    }

    private fun awaitPusherRemoved(
        identifier: String,
        pushkey: String,
        timeoutMillis: Long = 5000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (TestClient.pushkeysOf(identifier).contains(pushkey) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse(TestClient.pushkeysOf(identifier).contains(pushkey)) {
            "the pusher rejected by the Push Gateway should have been deregistered"
        }
    }

    @Test
    fun submitNotification_forUserWithoutAnyDevice_returns404WithUserNotFound() {
        TestClient
            .postNotification("X110413003")
            .statusCode(404)
            .body("errorCode", equalTo("USER_NOT_FOUND"))
    }

    @Test
    fun submitNotification_withTelematikIdDeclaredAsKvnr_returns400WithInvalidParam() {
        TestClient
            .postNotification("5-2-KH-AKA-1234567890")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    @Test
    fun submitNotification_forChannelOutsideTheRegistry_returns403WithChannelNotAllowed() {
        val identifier = "X110413020"
        registerEnabledDevice(identifier, "notif-unknown-channel")

        TestClient
            .postNotification(identifier, channel = "epa.unknown")
            .statusCode(403)
            .body("errorCode", equalTo("CHANNEL_NOT_ALLOWED"))
    }

    @Test
    fun submitNotification_withReferenceLongerThan128Characters_returns400WithInvalidParam() {
        val identifier = "X110413021"
        registerEnabledDevice(identifier, "notif-long-reference")

        TestClient
            .postNotification(identifier, reference = "r".repeat(129))
            .statusCode(400)
            .body("errorCode", equalTo("INVALID_PARAM"))
    }

    @Test
    fun submitNotification_repeatedWithSameIdempotencyKey_returnsTheSameNotificationIdWithoutASecondDispatch() {
        val identifier = "X110413006"
        val pushkey = "notif-idempotent"
        registerEnabledDevice(identifier, pushkey)
        val key = "it-notif-idempotent"

        val first = TestClient.postNotification(identifier, idempotencyKey = key).statusCode(202).extract()
        val second = TestClient.postNotification(identifier, idempotencyKey = key).statusCode(202).extract()

        assertEquals(first.path<String>("notification_id"), second.path<String>("notification_id"))
        assertEquals(first.path<String>("status"), second.path<String>("status"))
        assertEquals(first.path<String>("accepted_at"), second.path<String>("accepted_at"))
        pushGateway.awaitRequest(pushkey)
        Thread.sleep(300)
        assertEquals(1, pushGateway.findMatchingBatchRequests(pushkey).size)
    }
}
