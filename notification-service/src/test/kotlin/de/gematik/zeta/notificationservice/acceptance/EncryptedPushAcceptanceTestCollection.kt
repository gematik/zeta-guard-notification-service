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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectPushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayMock
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport.ISS
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport.KEY_IDENTIFIER
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport.OTHER_ISS
import de.gematik.zeta.notificationservice.testsupport.TestClient.CHANNEL
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotification
import de.gematik.zeta.notificationservice.testsupport.TestClient.postPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.pushkeysOf
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEncryptedDevice
import io.restassured.RestAssured
import io.restassured.http.ContentType
import kotlinx.serialization.json.JsonPrimitive
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.YearMonth
import java.time.ZoneOffset

abstract class EncryptedPushAcceptanceTestCollection {
    @InjectPushGatewayMock
    lateinit var pushGateway: PushGatewayMock

    private val objectMapper = jacksonObjectMapper()

    private val currentMonth: String get() = YearMonth.now(ZoneOffset.UTC).toString()

    @BeforeEach
    fun resetPushGateway() {
        pushGateway.wireMock().resetAll()
        pushGateway.stubDefaultSuccessEncryptedBatch()
        pushGateway.stubDefaultSuccessPlainBatch()
    }

    // A_29968
    @Test
    @DisplayName("A_29968: the resource server cannot switch encryption off for a notification")
    fun resourceServerCannotSwitchEncryptionOff() {
        val identifier = "X299680001"
        val pushkey = "enc-rs-flag"
        registerEncryptedDevice(identifier, pushkey, ISS, currentMonth, KEY_IDENTIFIER)

        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "user": { "id_type": "kvnr", "value": "$identifier" },
                  "channel": "$CHANNEL",
                  "payload": { "message": "Klartext erwuenscht" },
                  "encrypt": false
                }
                """.trimIndent(),
            ).`when`()
            .post("/notifications")
            .then()
            .statusCode(202)

        assertEquals(1, pushGateway.awaitEncryptedRequest(pushkey).size)
        assertTrue(pushGateway.findMatchingBatchRequests(pushkey).isEmpty()) { "nothing may reach the plain endpoint" }
    }

    @Test
    @DisplayName("A_29968: a registration without an encryption block is rejected while encryption is enabled")
    fun registrationWithoutEncryptionIsRejected() {
        val identifier = "X299680002"

        postPusher(identifier, "enc-missing")
            .statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", containsString("encryption"))

        assertEquals(emptyList<String>(), pushkeysOf(identifier))
    }

    // A_29970
    @Test
    @DisplayName(
        "A_29970: the message is encrypted with the key derived from the initial shared secret and opens on the client",
    )
    fun messageIsEncryptedWithTheDerivedKey() {
        val identifier = "X299700001"
        val pushkey = "enc-derived"
        val message = "Neues Dokument"
        registerEncryptedDevice(identifier, pushkey, ISS, currentMonth, KEY_IDENTIFIER)

        postNotification(identifier, message = message).statusCode(202)

        val item = singleEncryptedItem(pushkey)
        assertEquals(EncryptedPushTestSupport.CIPHERTEXT_LENGTH, item["ciphertext"].asText().length)
        assertEquals(currentMonth, item["time_message_encrypted"].asText())
        assertEquals(KEY_IDENTIFIER, item["key_identifier"].asText())
        val opened =
            EncryptedPushTestSupport.decrypt(
                item["ciphertext"].asText(),
                EncryptedPushTestSupport.aesKeyFor(ISS, currentMonth, currentMonth),
            )
        assertEquals(JsonPrimitive(message), opened["message"])
    }

    // A_29971
    @Test
    @DisplayName(
        "A_29971: encrypted notifications use /notifyEncrypted/batch only, one item per device, even for one device",
    )
    fun encryptedDispatchUsesTheBatchEndpointOnly() {
        val identifier = "X299710001"
        val first = "enc-device-1"
        val second = "enc-device-2"
        registerEncryptedDevice(identifier, first, ISS, currentMonth, KEY_IDENTIFIER)
        registerEncryptedDevice(identifier, second, OTHER_ISS, currentMonth, "second-key-identifier")

        postNotification(identifier, message = "an beide").statusCode(202)

        val requests = pushGateway.awaitEncryptedRequest(first)
        assertEquals(1, requests.size)
        val notifications = objectMapper.readTree(requests.single().bodyAsString)["notifications"]
        assertEquals(2, notifications.size())
        assertEquals(
            setOf(first, second),
            notifications.map { it["notification"]["device"]["pushkey"].asText() }.toSet(),
        )
        assertEquals(2, notifications.map { it["id"].asText() }.toSet().size)
        assertEquals(2, notifications.map { it["notification"]["ciphertext"].asText() }.toSet().size)
        assertTrue(pushGateway.findMatchingBatchRequests(first).isEmpty()) { "nothing may reach the plain endpoint" }
    }

    // A_29984
    @Test
    @DisplayName("A_29984: neither the message nor the user identifier reaches the Push Gateway in the clear")
    fun noPlaintextTowardsThePushGateway() {
        val identifier = "X299840001"
        val pushkey = "enc-no-pii"
        val message = "Vertraulicher Befund"
        registerEncryptedDevice(identifier, pushkey, ISS, currentMonth, KEY_IDENTIFIER)

        postNotification(identifier, message = message).statusCode(202)

        val body = pushGateway.awaitEncryptedRequest(pushkey).single().bodyAsString
        assertFalse(body.contains(message)) { "the message must not reach the Push Gateway in the clear" }
        assertFalse(body.contains(identifier)) { "the KVNR must not reach the Push Gateway" }
        assertTrue(body.contains(pushkey)) { "the device is addressed by its pushkey" }
    }

    private fun singleEncryptedItem(pushkey: String): JsonNode =
        objectMapper
            .readTree(pushGateway.awaitEncryptedRequest(pushkey).single().bodyAsString)["notifications"]
            .single()["notification"]
}
