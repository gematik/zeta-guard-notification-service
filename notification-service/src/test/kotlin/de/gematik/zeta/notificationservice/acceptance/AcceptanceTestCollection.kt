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

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathTemplate
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectPushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectStorageRecordProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.StorageRecordProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.StorageRecordProbe.RawRecord
import de.gematik.zeta.notificationservice.testsupport.TestClient.CHANNEL
import de.gematik.zeta.notificationservice.testsupport.TestClient.DEFAULT_APP_ID
import de.gematik.zeta.notificationservice.testsupport.TestClient.channelsOf
import de.gematik.zeta.notificationservice.testsupport.TestClient.deregisterPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.getAvailableChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannelsOfDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotificationHistory
import de.gematik.zeta.notificationservice.testsupport.TestClient.getPushers
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotificationSuccessfullyGetId
import de.gematik.zeta.notificationservice.testsupport.TestClient.postPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.postPusherWithBody
import de.gematik.zeta.notificationservice.testsupport.TestClient.pushkeysOf
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEnabledDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.registryChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.setChannelsOfDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.setChannelsOfDeviceWithBody
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import io.restassured.RestAssured
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

/**
 * The testing suite for acceptance tests defined by AFOs and as such run twice:
 * 1. As normal @QuarkusTests with other unit tests and so on. [AcceptanceTest]
 * 2. As @QuarkusIntegrationTests in the integration test package against the native image (in the ci)
 *
 * Other acceptance test collections should follow the same pattern, one abstract class with tests and three runners.
 *
 * As such, a few things to consider:
 * We can't group the tests in nested classes, because the integration tests will run multiple instances
 * at once and break the datasource property injections (jdbcUrl and so on)
 * We can't use CDI, because the native tests won't support it.
 */
abstract class AcceptanceTestCollection {
    @InjectPushGatewayMock
    lateinit var pushGateway: PushGatewayMock

    @InjectStorageRecordProbe
    lateinit var storageRecords: StorageRecordProbe

    @BeforeEach
    fun resetPushGateway() {
        pushGateway.wireMock().resetAll()
        pushGateway.stubDefaultSuccessPlainBatch()
    }

    // A_27154
    @Test
    @DisplayName("A_27154: registration without append removes the pushkey from other users")
    fun registrationWithoutAppendRemovesPushkeyFromOtherUsers() {
        val previous = "X271540001"
        val current = "X271540002"
        val pushkey = "acc-a27154-shared-device"
        registerEnabledDevice(previous, pushkey)

        registerPusher(current, pushkey)

        assertEquals(emptyList<String>(), pushkeysOf(previous))
        assertEquals(listOf(pushkey), pushkeysOf(current))
        postNotification(previous).statusCode(404)
    }

    @Test
    @DisplayName("A_27154: registration with append keeps the pushkey of other users")
    fun registrationWithAppendKeepsPushkeyOfOtherUsers() {
        val previous = "X271540003"
        val current = "X271540004"
        val pushkey = "acc-a27154-appended-device"
        registerEnabledDevice(previous, pushkey)

        registerPusher(current, pushkey, append = true)

        assertEquals(listOf(pushkey), pushkeysOf(previous))
        assertEquals(listOf(pushkey), pushkeysOf(current))
    }

    // A_29959
    @Test
    @DisplayName("A_29959: every persisted record holds ciphertext only, under a dek from the HSM-wrapped keychain")
    fun persistedRecordsHoldCiphertextOnly() {
        val identifier = "X299590001"
        val pushkey = "acc-a29959-sealed"
        val message = "Sealed at rest"
        // Registered *with* an encryption block: `iss` is the initial shared secret and carries the
        // highest protection need of gemF_PushNotification Kap. 5.5, so it belongs in the markers below.
        postPusherWithBody(identifier, sealedPusherBody(pushkey)).statusCode(200)
        setChannelsOfDevice(identifier, pushkey).statusCode(200)
        val notificationId = postNotificationSuccessfullyGetId(identifier, message = message)
        pushGateway.awaitRequest(pushkey)

        // Reading the data back through the API first: it proves the records really carry this
        // content, so the absence of it in the rows below is encryption rather than a missing write.
        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages[0].payload.message", equalTo(message))
        assertEquals(listOf(pushkey), pushkeysOf(identifier))

        val rows =
            mapOf(
                "pusher configuration" to storageRecords.rawPusherRecord(identifier),
                "notification" to storageRecords.rawNotificationRecord(notificationId),
                "dispatch history" to storageRecords.rawHistoryRecord(identifier, pushkey),
            )
        val plaintext =
            listOf(identifier, pushkey, message, DEFAULT_APP_ID, "epa.documents.new", ISS, KEY_IDENTIFIER)

        rows.forEach { (name, row) ->
            assertNotNull(row) { "the $name should be stored, otherwise this scenario proves nothing" }
            assertSealed(name, row!!, plaintext)
        }
    }

    private fun sealedPusherBody(pushkey: String): String =
        """
        {
          "pushkey": "$pushkey",
          "app_id": "$DEFAULT_APP_ID",
          "kind": "http",
          "app_display_name": "Test App",
          "device_display_name": "Test Device",
          "lang": "de",
          "data": { "url": "http://localhost:7931/push/v1/" },
          "encryption": {
            "method": "aes-hmac-sha256",
            "time_iss_created": "2026-09",
            "iss": "$ISS",
            "key_identifier": "$KEY_IDENTIFIER"
          }
        }
        """.trimIndent()

    private fun assertSealed(
        name: String,
        row: RawRecord,
        plaintext: List<String>,
    ) {
        val payload = row.payload.decodeToString()
        plaintext.forEach {
            assertFalse(payload.contains(it)) { "the $name payload must not hold \"$it\" in the clear" }
            assertFalse(row.storageId.contains(it)) { "the $name storage id must not hold \"$it\" in the clear" }
        }
        assertTrue(PSEUDONYMIZED_ID.matches(row.storageId)) {
            "the $name storage id should be a keyed mac (A_29960), was ${row.storageId}"
        }
        assertEquals(TestKeychain.V2, row.keychainId) {
            "the $name must name the keychain entry it was sealed under"
        }
    }

    // A_29964
    @Test
    @DisplayName("A_29964: getActiveChannels endpoint should be accessible")
    fun getActiveChannels() {
        getChannels("some-user", null)
            .statusCode(400)
            .body("errorCode", containsString("INVALID_PARAM"))
    }

    @Test
    @DisplayName("A_29964: submitNotification endpoint should be accessible")
    fun submitNotification() {
        RestAssured
            .given()
            .contentType("application/json")
            .body("{}")
            .post("/notifications")
            .then()
            .statusCode(400)
            .body("errorCode", containsString("M_MISSING_PARAM"))
    }

    // A_29965
    @Test
    @DisplayName("A_29965: valid notification request is validated and returns 202 with a notification id")
    fun validRequest() {
        val identifier = "X299650001"
        registerEnabledDevice(identifier, "acc-accepted")

        postNotification(identifier)
            .statusCode(202)
            .body("notification_id", notNullValue())
            .body("status", equalTo("accepted"))
            .body("channel", equalTo(CHANNEL))
            .body("accepted_at", notNullValue())
    }

    @Test
    @DisplayName("A_29965: invalid notification request is rejected before a notification id is issued")
    fun invalidRequest() {
        val identifier = "X299650002"
        registerEnabledDevice(identifier, "acc-invalid")

        // reference can't be more than 128 characters
        postNotification(identifier, reference = "r".repeat(129))
            .statusCode(400)
            .body("errorCode", equalTo("INVALID_PARAM"))
    }

    @Test
    @DisplayName("A_29965: the call towards the Push Gateway happens asynchronously after the 202")
    fun callToPushGatewayHappensAsynchronously() {
        val identifier = "X299650003"
        val pushkey = "acc-async"
        registerEnabledDevice(identifier, pushkey)
        pushGateway.holdGatedResponses()
        pushGateway.stubDefaultSuccessPlainBatch(gated = true)

        postNotification(identifier).statusCode(202).body("status", equalTo("accepted"))

        pushGateway.releaseGatedResponses()
        assertEquals(1, pushGateway.awaitRequest(pushkey).size)
        assertFalse(pushGateway.gateExpired()) { "the 202 must not wait for the Push Gateway response" }
    }

    @Test
    @DisplayName("A_29965: valid notification request is delivered to the Push Gateway asynchronously")
    fun validNotificationIsDeliveredToPushGateway() {
        val identifier = "X299650004"
        val pushkey = "acc-delivered"
        registerEnabledDevice(identifier, pushkey)

        val notificationId =
            postNotification(
                identifier,
            ).statusCode(202)
                .extract()
                .path<String>("notification_id")

        val requests = pushGateway.awaitRequest(pushkey)
        assertEquals(1, requests.size)
        val body = requests.single().bodyAsString
        assertTrue(body.contains("Hello World!")) { "payload should be forwarded" }
        assertTrue(body.contains(notificationId)) { "batch item id should be the notification id" }
    }

    // A_29966
    @Test
    @DisplayName("A_29966: notification request without device returns 404")
    fun requestWithoutDevice() {
        postNotification("X299660001").statusCode(404)
    }

    @Test
    @DisplayName("A_29966: notification request without channel returns 202 and doesn't call the gateway")
    fun requestWithoutChannel() {
        val identifier = "X299660002"
        val pushkey = "no-channel"
        registerPusher(identifier, pushkey)

        postNotification(identifier).statusCode(202).body("status", equalTo("no_active_channel"))
        assert(pushGateway.findMatchingBatchRequests(pushkey).isEmpty()) { "no batch request should be sent" }
    }

    // A_29967
    @Test
    @DisplayName("A_29967: user is only identified by id value and id type")
    fun userIsOnlyIdentifiedByValueAndType() {
        val identifier = "X299670001"
        val pushkey = "id-value-and-type"
        registerEnabledDevice(identifier, pushkey)

        // request without specifying id type
        getChannels(identifier, null)
            .statusCode(400)
            .body("errorCode", equalTo("INVALID_PARAM"))
            .body("errorDetail", containsString("id_type"))
    }

    @Test
    @DisplayName("A_29967: a KVNR-shaped value declared as telematik-id is rejected without echoing the value")
    fun kvnrShapedValueDeclaredAsTelematikIdIsRejected() {
        val identifier = "X299670002"
        registerEnabledDevice(identifier, "id-type-mismatch")

        getChannels(identifier, "id_type=telematik-id")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body("errorDetail", not(containsString(identifier)))
    }

    // A_29971
    @Test
    @DisplayName("A_29971: exponential backoff when the push gateway fails")
    fun exponentialBackoffWhenPushGatewayFails() {
        pushGateway.wireMock().stubFor(
            post(urlPathTemplate("/notify/batch"))
                .willReturn(aResponse().withStatus(500)),
        )

        val identifier = "X299710001"
        val pushkey = "backoff"
        val requestCount = 3
        registerEnabledDevice(identifier, pushkey)
        postNotification(identifier).statusCode(202)

        val requests = pushGateway.awaitRequest(pushkey, requestCount + 1)
        assertTrue(requests.size == requestCount) { "three requests should be made before giving up" }

        val timestamps = requests.map { it.loggedDate.toInstant().toEpochMilli() }.sorted()
        val delaysBetweenAttempts = timestamps.zipWithNext { earlier, later -> later - earlier }

        assertTrue(delaysBetweenAttempts.zipWithNext().all { (delay, nextDelay) -> nextDelay > delay }) {
            "delay before each retry should grow, but observed delays were $delaysBetweenAttempts"
        }
    }

    @Test
    @DisplayName("A_29971: delete invalid pushers after pushgateway response")
    fun deleteInvalidPushers() {
        val identifier = "X299710002"
        val pushkey = "invalid-pushkey"
        registerEnabledDevice(identifier, pushkey)

        pushGateway.stubPlainBatchRejectedPushkey(pushkey)

        postNotification(identifier).statusCode(202)

        val remainingPushkeys = awaitPusherRemoved(identifier, pushkey)
        assertFalse(remainingPushkeys.contains(pushkey)) { "the rejected pusher should have been deregistered" }
    }

    @Test
    @DisplayName("A_29971: delete a rejected pusher that was not part of this dispatch")
    fun deleteRejectedPusherOutsideOfThisDispatch() {
        val identifier = "X299710003"
        val dispatched = "dispatched-pushkey"
        val untouched = "untouched-pushkey"
        registerEnabledDevice(identifier, dispatched)
        registerPusher(identifier, untouched)

        pushGateway.stubPlainBatchRejectedPushkey(untouched)

        postNotification(identifier).statusCode(202)

        val remainingPushkeys = awaitPusherRemoved(identifier, untouched)
        assertFalse(remainingPushkeys.contains(untouched)) {
            "a pushkey rejected by the Push Gateway should be deregistered even outside the dispatch it answers"
        }
        assertTrue(remainingPushkeys.contains(dispatched)) { "the dispatched pusher should have been kept" }
    }

    @Test
    @DisplayName("A_29971: delete every pusher sharing a rejected pushkey")
    fun deleteEveryPusherSharingARejectedPushkey() {
        val identifier = "X299710004"
        val pushkey = "shared-pushkey"
        registerEnabledDevice(identifier, pushkey)
        registerPusher(identifier, pushkey, appId = "$DEFAULT_APP_ID.second")

        pushGateway.stubPlainBatchRejectedPushkey(pushkey)

        postNotification(identifier).statusCode(202)

        val remainingPushkeys = awaitPusherRemoved(identifier, pushkey)
        assertTrue(remainingPushkeys.isEmpty()) {
            "both app ids registered for the rejected pushkey should have been deregistered, left: $remainingPushkeys"
        }
    }

    /**
     * Asserts exactly one dispatch reached the Push Gateway for [pushkey].
     *
     * Two waits, because one cannot do both jobs: the first gets the full timeout, so a dispatcher that
     * is merely slow cannot satisfy this vacuously with zero requests; the second only has to outlast the
     * gap between two dispatches of the same submission, so it uses the much shorter
     * [NO_SECOND_DISPATCH_MILLIS] instead of waiting out the default timeout.
     */
    private fun assertDispatchedOnce(
        pushkey: String,
        message: String,
    ) {
        assertEquals(1, pushGateway.awaitRequest(pushkey).size) { message }
        assertEquals(
            1,
            pushGateway
                .awaitRequest(pushkey, expectedCount = 2, timeoutMillis = NO_SECOND_DISPATCH_MILLIS)
                .size,
        ) { message }
    }

    private fun awaitPusherRemoved(
        identifier: String,
        pushkey: String,
        timeoutMillis: Long = 5000,
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var pushkeys = pushkeysOf(identifier)
        while (pushkey in pushkeys && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            pushkeys = pushkeysOf(identifier)
        }
        return pushkeys
    }

    // A_29972
    @Test
    @DisplayName("A_29972: GET /channels/{pushkey} returns the device configuration")
    fun getChannelsOfDeviceReturnsConfiguration() {
        val identifier = "X299720001"
        val pushkey = "device-config"
        registerPusher(identifier, pushkey)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.enabled)
            .statusCode(200)

        assertEquals("enabled", channelStatus(identifier, pushkey))
    }

    @Test
    @DisplayName("A_29972: POST to /channels/{pushkey} updates device configuration")
    fun postChannelsOfDeviceUpdatesConfiguration() {
        val identifier = "X299720002"
        val pushkey = "device-update"
        registerPusher(identifier, pushkey)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.enabled)
            .statusCode(200)

        assertEquals("enabled", channelStatus(identifier, pushkey))

        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.disabled)
            .statusCode(200)

        assertEquals("disabled", channelStatus(identifier, pushkey))
    }

    @Test
    @DisplayName("A_29972: unset channels have not_set status")
    fun notSetChannel() {
        val identifier = "X299720003"
        val pushkey = "device-not-set"
        registerPusher(identifier, pushkey)

        assertEquals("not_set", channelStatus(identifier, pushkey))
    }

    private fun channelStatus(
        identifier: String,
        pushkey: String,
    ): String? = channelsOf(identifier, pushkey)[CHANNEL]

    // A_29973
    @Test
    @DisplayName("A_29973: history api returns notification history")
    fun historyCheck() {
        val identifier = "X299730001"
        val pushkey = "history"
        registerEnabledDevice(identifier, pushkey)

        postNotification(identifier, message = "History check 1").statusCode(202)
        postNotification(identifier, message = "History check 2").statusCode(202)
        postNotification(identifier, message = "History check 3").statusCode(202)

        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(3))
            .body("messages[0].payload.message", equalTo("History check 1"))
            .body("messages[1].payload.message", equalTo("History check 2"))
            .body("messages[2].payload.message", equalTo("History check 3"))
    }

    // A_29975
    @Test
    @DisplayName("A_29975: GET /pushers requires a valid access token")
    fun getPushersRequiresAccessToken() {
        val identifier = "X299750001"

        getPushers(identifier, authenticated = false).statusCode(401)
        getPushers(identifier).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: POST /pushers/set requires a valid access token")
    fun postPusherRequiresAccessToken() {
        val identifier = "X299750002"
        val pushkey = "acc-fdv-post-pusher"

        postPusher(identifier, pushkey, authenticated = false).statusCode(401)
        postPusher(identifier, pushkey).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: GET /channels requires a valid access token")
    fun getAvailableChannelsRequiresAccessToken() {
        val identifier = "X299750003"

        getAvailableChannels(identifier, authenticated = false).statusCode(401)
        getAvailableChannels(identifier).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: GET /channels/{pushkey} requires a valid access token")
    fun getChannelsOfDeviceRequiresAccessToken() {
        val identifier = "X299750004"
        val pushkey = "acc-fdv-channels-of-device"
        registerPusher(identifier, pushkey)

        getChannelsOfDevice(identifier, pushkey, authenticated = false).statusCode(401)
        getChannelsOfDevice(identifier, pushkey).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: POST /channels/{pushkey} requires a valid access token")
    fun postChannelsOfDeviceRequiresAccessToken() {
        val identifier = "X299750005"
        val pushkey = "acc-fdv-post-channels"
        registerPusher(identifier, pushkey)

        setChannelsOfDevice(identifier, pushkey, authenticated = false).statusCode(401)
        setChannelsOfDevice(identifier, pushkey).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: GET /history/{notification_id} requires a valid access token")
    fun getNotificationRequiresAccessToken() {
        val identifier = "X299750006"
        val pushkey = "acc-fdv-notification"
        registerEnabledDevice(identifier, pushkey)
        val notificationId =
            postNotification(identifier).statusCode(202).extract().path<String>("notification_id")

        getNotification(identifier, notificationId, authenticated = false).statusCode(401)
        getNotification(identifier, notificationId).statusCode(200)
    }

    @Test
    @DisplayName("A_29975: GET /history/device/{pushkey} requires a valid access token")
    fun getNotificationHistoryRequiresAccessToken() {
        val identifier = "X299750007"
        val pushkey = "acc-fdv-history"
        registerPusher(identifier, pushkey)

        getNotificationHistory(identifier, pushkey, authenticated = false).statusCode(401)
        getNotificationHistory(identifier, pushkey).statusCode(200)
    }

    // A_29985
    @Test
    @DisplayName("A_29985: channel outside the static registry is denied with 403 CHANNEL_NOT_ALLOWED")
    fun channelOutsideRegistryReturns403() {
        val identifier = "X299850001"
        val pushkey = "acc-a29985-not-allowed"
        registerPusher(identifier, pushkey)

        setChannelsOfDevice(identifier, pushkey, channel = "epa.unknown")
            .statusCode(403)
            .body("errorCode", equalTo("CHANNEL_NOT_ALLOWED"))
    }

    @Test
    @DisplayName("A_29985: notification for a channel outside the static registry returns 403 with CHANNEL_NOT_ALLOWED")
    fun notificationForChannelOutsideRegistryReturns403() {
        val identifier = "X299850002"
        registerEnabledDevice(identifier, "acc-a29985-unknown-channel")

        postNotification(identifier, channel = "epa.unknown")
            .statusCode(403)
            .body("errorCode", equalTo("CHANNEL_NOT_ALLOWED"))
    }

    @Test
    @DisplayName("A_29985: notification for a syntactically invalid channel id returns 400 with M_INVALID_PARAM")
    fun notificationForSyntacticallyInvalidChannelReturns400() {
        val identifier = "X299850003"

        postNotification(identifier, channel = "not a valid channel")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    // A_29986
    @Test
    @DisplayName("A_29986: GET /pushers is accessible and returns the caller's registered pushers")
    fun getPushersHasNoInputToValidate() {
        // GET /pushers takes no path or query parameters, so there is no invalid input to
        // exercise here — this is a reachability check instead.
        val identifier = "X299860001"
        val pushkey = "acc-a29986-get-pushers"
        registerPusher(identifier, pushkey)

        assertTrue(pushkeysOf(identifier).contains(pushkey))
    }

    @Test
    @DisplayName("A_29986: POST /pushers/set without a pushkey returns 400 M_MISSING_PARAM")
    fun postPusherWithoutPushkeyReturns400MissingParam() {
        val identifier = "X299860002"

        postPusherWithBody(
            identifier,
            """{"kind":"http","app_id":"de.gematik.zeta.test"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", containsString("pushkey"))
    }

    @Test
    @DisplayName("A_29986: GET /channels is accessible and returns the channel registry")
    fun getChannelsHasNoInputToValidate() {
        // GET /channels takes no path or query parameters, so there is no invalid input to
        // exercise here — this is a reachability check instead.
        val identifier = "X299860003"

        assertTrue(registryChannels(identifier).containsKey(CHANNEL))
    }

    @Test
    @DisplayName("A_29986: GET /channels/{pushkey} for an unregistered pushkey returns 404 PUSHER_NOT_FOUND")
    fun getChannelsOfDeviceForUnknownPushkeyReturns404() {
        val identifier = "X299860004"

        getChannelsOfDevice(identifier, "acc-a29986-unknown-pushkey")
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
    }

    @Test
    @DisplayName("A_29986: POST /channels/{pushkey} without a status field returns 400 M_MISSING_PARAM")
    fun postChannelsOfDeviceWithoutStatusReturns400MissingParam() {
        val identifier = "X299860005"
        val pushkey = "acc-a29986-post-channels"
        registerPusher(identifier, pushkey)

        setChannelsOfDeviceWithBody(identifier, pushkey, """{"channels":[{"id":"$CHANNEL"}]}""")
            .statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", equalTo("Missing parameters: status"))
    }

    @Test
    @DisplayName(
        "A_29986: GET /history/{notification_id} with a syntactically invalid id returns 404 NOTIFICATION_NOT_FOUND",
    )
    fun getNotificationForMalformedIdReturns404NotificationNotFound() {
        val identifier = "X299860006"

        getNotification(identifier, "not-a-valid-notification-id")
            .statusCode(404)
            .body("errorCode", equalTo("NOTIFICATION_NOT_FOUND"))
    }

    @Test
    @DisplayName("A_29986: GET /history/device/{pushkey} for an unregistered pushkey returns 404 PUSHER_NOT_FOUND")
    fun getNotificationsForUnknownPushkeyReturns404PusherNotFound() {
        val identifier = "X299860007"

        getNotificationHistory(identifier, "acc-a29986-unknown-pushkey")
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
    }

    // A_29988
    @Test
    @DisplayName("A_29988: notification processing data is unreadable and deleted once the retention period elapsed")
    fun processingDataIsDeletedAfterTheRetentionPeriod() {
        val identifier = "X299880001"
        registerEnabledDevice(identifier, "retention-processing")
        val notificationId = postNotificationSuccessfullyGetId(identifier)

        getNotification(identifier, notificationId).statusCode(200)
        assertTrue(storageRecords.notificationExists(notificationId)) {
            "the notification should be stored while its retention period runs"
        }

        storageRecords.expireNotification(notificationId)

        getNotification(identifier, notificationId)
            .statusCode(404)
            .body("errorCode", equalTo("NOTIFICATION_NOT_FOUND"))

        storageRecords.purgeExpired()
        assertFalse(storageRecords.notificationExists(notificationId)) {
            "the notification record should be deleted once its retention period elapsed"
        }
    }

    @Test
    @DisplayName("A_29988: an idempotency receipt is replayed within the retention period and forgotten after it")
    fun idempotencyReceiptIsReplayedOnlyWithinTheRetentionPeriod() {
        val identifier = "X299880002"
        val pushkey = "retention-idempotency"
        val idempotencyKey = "acc-a29988-key"
        registerEnabledDevice(identifier, pushkey)

        val firstId = postNotificationSuccessfullyGetId(identifier, idempotencyKey = idempotencyKey)
        val replayedId = postNotificationSuccessfullyGetId(identifier, idempotencyKey = idempotencyKey)

        assertEquals(firstId, replayedId) { "a retransmission within the retention period should replay the receipt" }
        assertDispatchedOnce(pushkey, "a replayed submission must not be dispatched a second time")

        storageRecords.expireIdempotencyReceipt(identifier, CHANNEL, idempotencyKey)
        storageRecords.purgeExpired()

        val afterRetentionId = postNotificationSuccessfullyGetId(identifier, idempotencyKey = idempotencyKey)

        assertNotEquals(firstId, afterRetentionId) {
            "once the retention period elapsed the receipt is forgotten and a new notification id is issued"
        }
        assertEquals(2, pushGateway.awaitRequest(pushkey, expectedCount = 2).size) {
            "the resubmission after the retention period should be dispatched again"
        }
    }

    @Test
    @DisplayName("A_29988: message history is unaffected by the processing-data retention period")
    fun messageHistoryOutlivesTheProcessingDataRetention() {
        val identifier = "X299880003"
        val pushkey = "retention-history"
        registerEnabledDevice(identifier, pushkey)
        val before = System.currentTimeMillis()
        val notificationId = postNotificationSuccessfullyGetId(identifier, message = "Survives the retention period")
        val after = System.currentTimeMillis()

        storageRecords.expireNotification(notificationId)
        storageRecords.purgeExpired()

        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(1))
            .body("messages[0].payload.message", equalTo("Survives the retention period"))

        assertTrue(storageRecords.historyRecordExists(identifier, pushkey)) {
            "the history record should have survived the purge"
        }
        val retention = 30.days.inWholeMilliseconds
        val expiresAt = storageRecords.historyRecordExpiresAt(identifier, pushkey)
        assertTrue(expiresAt in before + retention..after + retention) {
            "history data follows the operator configuration, so it must expire 30 days after its first entry"
        }
    }

    @Test
    @DisplayName("A_29988: message history is unreadable and deleted once the history retention period elapsed")
    fun messageHistoryIsDeletedAfterTheHistoryRetention() {
        val identifier = "X299880004"
        val pushkey = "retention-history-expired"
        registerEnabledDevice(identifier, pushkey)
        postNotificationSuccessfullyGetId(identifier)

        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(1))

        storageRecords.expireHistory(identifier, pushkey)

        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(0))

        storageRecords.purgeExpired()

        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(0))
        assertFalse(storageRecords.historyRecordExists(identifier, pushkey)) {
            "the history record should be deleted once its retention period elapsed"
        }
    }

    // A_29989
    @Test
    @DisplayName("A_29989: deregistering a pusher deletes its push configuration")
    fun deregisteringAPusherDeletesItsPushConfiguration() {
        val identifier = "X299890001"
        val pushkey = "acc-a29989-config"
        registerEnabledDevice(identifier, pushkey)

        // Guards the assertions below, which an unregistered device would satisfy just as well.
        assertEquals(listOf(pushkey), pushkeysOf(identifier))
        assertEquals("enabled", channelStatus(identifier, pushkey))
        getChannels(identifier, "id_type=kvnr").statusCode(200)

        deregisterPusher(identifier, pushkey).statusCode(200)

        assertEquals(emptyList<String>(), pushkeysOf(identifier)) {
            "the deregistered pusher must be gone from the caller's registrations"
        }
        getChannelsOfDevice(identifier, pushkey)
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
        getChannels(identifier, "id_type=kvnr")
            .statusCode(404)
            .body("errorCode", equalTo("USER_NOT_FOUND"))
    }

    @Test
    @DisplayName("A_29989: deregistering the last pusher deletes the stored push configuration")
    fun deregisteringTheLastPusherDeletesTheStoredPushConfiguration() {
        val identifier = "X299890002"
        val pushkey = "acc-a29989-record"
        registerEnabledDevice(identifier, pushkey)

        assertTrue(storageRecords.pusherRecordExists(identifier)) {
            "the push configuration should be stored while the device is registered"
        }

        deregisterPusher(identifier, pushkey).statusCode(200)

        // No purgeExpired() here, unlike the A_29988 retention scenarios: pushers records carry no
        // TTL, so deregistration is the only thing that ever deletes them, and it deletes at once.
        assertFalse(storageRecords.pusherRecordExists(identifier)) {
            "deregistering the last pusher must delete the stored push configuration"
        }
    }

    @Test
    @DisplayName("A_29989: deregistering one pusher deletes only that device's configuration")
    fun deregisteringOnePusherDeletesOnlyThatDevicesConfiguration() {
        val identifier = "X299890003"
        val keptPushkey = "acc-a29989-kept"
        val removedPushkey = "acc-a29989-removed"
        registerEnabledDevice(identifier, keptPushkey)
        registerEnabledDevice(identifier, removedPushkey)

        deregisterPusher(identifier, removedPushkey).statusCode(200)

        assertEquals(listOf(keptPushkey), pushkeysOf(identifier)) {
            "deregistering one device must leave the user's other registrations alone"
        }
        assertEquals("enabled", channelStatus(identifier, keptPushkey)) {
            "the surviving device must keep its own channel configuration"
        }
        getChannelsOfDevice(identifier, removedPushkey)
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
        assertTrue(storageRecords.pusherRecordExists(identifier)) {
            "the record must survive while the user still has a registered device"
        }
    }

    @Test
    @DisplayName("A_29989: a deregistered device receives no further notifications")
    fun aDeregisteredDeviceReceivesNoFurtherNotifications() {
        val identifier = "X299890004"
        // Neither pushkey may be a substring of the other: findMatchingBatchRequests matches on the request
        // body, so an overlap would let the removed device's batch satisfy the kept device's await below.
        val keptPushkey = "acc-a29989-target-kept"
        val removedPushkey = "acc-a29989-target-dropped"
        registerEnabledDevice(identifier, keptPushkey)
        registerEnabledDevice(identifier, removedPushkey)

        deregisterPusher(identifier, removedPushkey).statusCode(200)

        postNotification(identifier).statusCode(202)

        // Awaiting the surviving device first proves the dispatch ran, so the assertion below cannot
        // pass just because nothing had been dispatched yet.
        assertEquals(1, pushGateway.awaitRequest(keptPushkey).size) {
            "the still-registered device should have received the notification"
        }
        assertTrue(pushGateway.findMatchingBatchRequests(removedPushkey).isEmpty()) {
            "a deregistered device must no longer be a dispatch target"
        }
    }

    @Test
    @DisplayName(
        "A_29989: re-registering a deregistered pushkey starts without its former channel configuration",
    )
    fun reRegisteringADeregisteredPushkeyStartsWithoutItsFormerChannelConfiguration() {
        val identifier = "X299890005"
        val siblingPushkey = "acc-a29989-sibling"
        val pushkey = "acc-a29989-rereg"
        // The sibling device keeps the record alive, so this pins the pruning of the deregistered
        // device's channel configuration rather than the deletion of the whole record.
        registerEnabledDevice(identifier, siblingPushkey)
        registerEnabledDevice(identifier, pushkey)

        deregisterPusher(identifier, pushkey).statusCode(200)
        assertTrue(storageRecords.pusherRecordExists(identifier)) {
            "the sibling device should have kept the stored push configuration alive"
        }

        registerPusher(identifier, pushkey)

        // Re-registering an existing pusher deliberately keeps the user's channel choices, so this
        // only holds if the deregistration really deleted them rather than merely hiding them.
        assertEquals("not_set", channelStatus(identifier, pushkey)) {
            "a re-registered pushkey must not inherit the channel configuration of the deleted one"
        }
    }

    @Test
    @DisplayName("A_29989: deregistering a pusher deletes its message history, a re-registration starts empty")
    fun deregisteringAPusherDeletesItsMessageHistory() {
        val identifier = "X299890006"
        val pushkey = "acc-a29989-history"
        registerEnabledDevice(identifier, pushkey)
        postNotificationSuccessfullyGetId(identifier)
        assertEquals(1, pushGateway.awaitRequest(pushkey).size)
        assertTrue(storageRecords.historyRecordExists(identifier, pushkey)) {
            "the history should be stored while the device is registered"
        }

        deregisterPusher(identifier, pushkey).statusCode(200)

        assertFalse(storageRecords.historyRecordExists(identifier, pushkey)) {
            "deregistering a pusher must delete its message history"
        }
        registerPusher(identifier, pushkey)
        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(0))
    }

    // A_29992
    @Test
    @DisplayName("A_29992: retransmitting with the same idempotency key is dispatched to the Push Gateway once")
    fun retransmissionWithTheSameIdempotencyKeyIsDispatchedOnce() {
        val identifier = "X299920001"
        val pushkey = "acc-a29992-retransmit"
        val idempotencyKey = "acc-a29992-retransmit-key"
        registerEnabledDevice(identifier, pushkey)

        val first = postNotification(identifier, idempotencyKey = idempotencyKey).statusCode(202).extract()
        val second = postNotification(identifier, idempotencyKey = idempotencyKey).statusCode(202).extract()
        val third = postNotification(identifier, idempotencyKey = idempotencyKey).statusCode(202).extract()

        // The contract promises the *same* receipt, not merely a successful one, so every field of it
        // is compared — a fresh submission would differ in all three.
        listOf("notification_id", "status", "accepted_at").forEach { field ->
            assertEquals(first.path<String>(field), second.path<String>(field)) {
                "a retransmission must replay the original $field"
            }
            assertEquals(first.path<String>(field), third.path<String>(field)) {
                "every retransmission must replay the original $field"
            }
        }

        // A_29988 covers the same replay from the retention angle; here it is the deduplication itself
        // that is under test.
        assertDispatchedOnce(pushkey, "the retransmissions must not reach the Push Gateway a second time")
        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(1))
    }

    @Test
    @DisplayName("A_29992: submissions without an idempotency key are not deduplicated")
    fun submissionsWithoutAnIdempotencyKeyAreNotDeduplicated() {
        val identifier = "X299920002"
        val pushkey = "acc-a29992-unkeyed"
        registerEnabledDevice(identifier, pushkey)

        // Identical in every respect except the missing header — which is the whole point: without it
        // the service cannot tell a retransmission from a second, genuinely new notification.
        val first = postNotificationSuccessfullyGetId(identifier)
        val second = postNotificationSuccessfullyGetId(identifier)

        assertNotEquals(first, second) {
            "without an Idempotency-Key each submission is a new notification"
        }
        assertEquals(2, pushGateway.awaitRequest(pushkey, expectedCount = 2).size) {
            "both unkeyed submissions must be dispatched"
        }
    }

    @Test
    @DisplayName("A_29992: different idempotency keys are submitted independently")
    fun differentIdempotencyKeysAreSubmittedIndependently() {
        val identifier = "X299920003"
        val pushkey = "acc-a29992-distinct"
        registerEnabledDevice(identifier, pushkey)

        val first = postNotificationSuccessfullyGetId(identifier, idempotencyKey = "acc-a29992-distinct-a")
        val second = postNotificationSuccessfullyGetId(identifier, idempotencyKey = "acc-a29992-distinct-b")

        assertNotEquals(first, second) { "distinct keys describe distinct submissions" }
        assertEquals(2, pushGateway.awaitRequest(pushkey, expectedCount = 2).size) {
            "a different key must not suppress the dispatch"
        }
    }

    @Test
    @DisplayName("A_29992: an idempotency key is scoped to the user it was submitted for")
    fun anIdempotencyKeyIsScopedToItsUser() {
        val sharedKey = "acc-a29992-shared-key"
        val firstIdentifier = "X299920004"
        val secondIdentifier = "X299920005"
        val firstPushkey = "acc-a29992-user-one"
        val secondPushkey = "acc-a29992-user-two"
        registerEnabledDevice(firstIdentifier, firstPushkey)
        registerEnabledDevice(secondIdentifier, secondPushkey)

        val first = postNotificationSuccessfullyGetId(firstIdentifier, idempotencyKey = sharedKey)
        val second = postNotificationSuccessfullyGetId(secondIdentifier, idempotencyKey = sharedKey)

        // Resource Servers pick their own keys, so two of them may collide by accident. Treating that as
        // a retransmission would silently swallow the second user's notification.
        assertNotEquals(first, second) { "a key reused for another user must not replay the first receipt" }
        assertEquals(1, pushGateway.awaitRequest(firstPushkey).size) {
            "the first user's notification must be dispatched"
        }
        assertEquals(1, pushGateway.awaitRequest(secondPushkey).size) {
            "the second user's notification must be dispatched despite the colliding key"
        }
    }

    @Test
    @DisplayName("A_29992: an idempotency key is scoped to the channel it was submitted for")
    fun anIdempotencyKeyIsScopedToItsChannel() {
        val identifier = "X299920006"
        val pushkey = "acc-a29992-channels"
        val sharedKey = "acc-a29992-channel-key"
        registerEnabledDevice(identifier, pushkey)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.enabled, OTHER_CHANNEL).statusCode(200)

        val first = postNotificationSuccessfullyGetId(identifier, idempotencyKey = sharedKey)
        val second =
            postNotificationSuccessfullyGetId(identifier, idempotencyKey = sharedKey, channel = OTHER_CHANNEL)

        assertNotEquals(first, second) {
            "a key reused on another channel must not replay the first receipt"
        }
        assertEquals(2, pushGateway.awaitRequest(pushkey, expectedCount = 2).size) {
            "both channels must be dispatched despite the shared key"
        }
    }

    @Test
    @DisplayName("A_29992: a retransmission with a changed payload replays the original submission")
    fun retransmissionWithAChangedPayloadReplaysTheOriginal() {
        val identifier = "X299920007"
        val pushkey = "acc-a29992-changed-payload"
        val idempotencyKey = "acc-a29992-changed-payload-key"
        registerEnabledDevice(identifier, pushkey)

        val first = postNotificationSuccessfullyGetId(identifier, "Original", idempotencyKey)
        val second = postNotificationSuccessfullyGetId(identifier, "Changed", idempotencyKey)

        assertEquals(first, second) { "the key alone identifies the submission, so the receipt is replayed" }

        // The history is appended once per *processed* submission, so a single entry carrying the first
        // payload proves the second body was never processed — no second dispatch, and no overwrite.
        getNotificationHistory(identifier, pushkey)
            .statusCode(200)
            .body("messages.size()", equalTo(1))
            .body("messages[0].payload.message", equalTo("Original"))
    }

    @Test
    @DisplayName("A_29992: an idempotency key longer than the contract allows is rejected")
    fun anOverlongIdempotencyKeyIsRejected() {
        val identifier = "X299920008"
        // Registering the device first: without it a 400 could just as well come from an unknown user.
        registerEnabledDevice(identifier, "acc-a29992-overlong")

        // The RS contract caps Idempotency-Key at 64 characters.
        postNotification(identifier, idempotencyKey = "k".repeat(65))
            .statusCode(400)
            .body("errorCode", equalTo("INVALID_PARAM"))
    }

    private companion object {
        /** A second channel from the `%test` registry, for the channel-scoping scenario. */
        const val OTHER_CHANNEL = "channel1"

        /** HMAC-SHA256 hex, as `HmacPseudonymizer` produces it. */
        val PSEUDONYMIZED_ID = Regex("[0-9a-f]{64}")

        /** The initial shared secret of the encrypted-push registration — `iss` in the FdV contract. */
        const val ISS = "a1b2c3d4e5f6071829304a5b6c7d8e9f00112233445566778899aabbccddeeff"
        const val KEY_IDENTIFIER = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"

        /**
         * Budget for proving that a *second* dispatch never happens. It only has to outlast the gap
         * between two dispatches of the same submission, so it does not need [PushGatewayMock.awaitRequest]'s
         * default timeout — see [assertDispatchedOnce].
         */
        const val NO_SECOND_DISPATCH_MILLIS = 1_000L
    }
}
