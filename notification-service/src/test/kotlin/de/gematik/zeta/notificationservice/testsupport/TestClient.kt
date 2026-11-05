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
package de.gematik.zeta.notificationservice.testsupport

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.util.Base64

/**
 * Shared REST-assured helper for api calls in tests.
 */
object TestClient {
    const val USER_INFO_HEADER = "zeta-user-info"
    const val CHANNEL = "epa.documents.new"
    const val DEFAULT_APP_ID = "de.gematik.zeta.test"

    // url is passed to the allowlist when the mock is created, so we need to reconstruct it here.
    private const val DEFAULT_URL = "http://localhost:7931/push/v1/"

    fun registerEnabledDevice(
        identifier: String,
        pushkey: String,
        url: String = DEFAULT_URL,
    ) {
        registerPusher(identifier, pushkey, url = url)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.enabled)
            .statusCode(200)
    }

    fun registerPusher(
        identifier: String,
        pushkey: String,
        appId: String = DEFAULT_APP_ID,
        url: String = DEFAULT_URL,
        append: Boolean? = null,
    ) {
        postPusher(identifier, pushkey, appId, url, append = append).statusCode(200)
    }

    fun postPusher(
        identifier: String,
        pushkey: String,
        appId: String = DEFAULT_APP_ID,
        url: String = DEFAULT_URL,
        authenticated: Boolean = true,
        append: Boolean? = null,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "pushkey": "$pushkey",
                  "app_id": "$appId",
                  "kind": "http",
                  "app_display_name": "Test App",
                  "device_display_name": "Test Device",
                  "lang": "de",
                  "data": { "url": "$url" }
                  ${append?.let { ""","append": $it""" } ?: ""}
                }
                """.trimIndent(),
            ).`when`()
            .post("pushers/set")
            .then()
    }

    fun postPusherWithBody(
        identifier: String,
        body: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec
            .contentType(ContentType.JSON)
            .body(body)
            .`when`()
            .post("pushers/set")
            .then()
    }

    fun postPusherWithEncryption(
        identifier: String,
        pushkey: String,
        iss: String,
        timeIssCreated: String,
        keyIdentifier: String,
        appId: String = DEFAULT_APP_ID,
        url: String = DEFAULT_URL,
    ): ValidatableResponse =
        postPusherWithBody(
            identifier,
            """
            {
              "pushkey": "$pushkey",
              "app_id": "$appId",
              "kind": "http",
              "app_display_name": "Test App",
              "device_display_name": "Test Device",
              "lang": "de",
              "data": { "url": "$url" },
              "encryption": {
                "method": "aes-hmac-sha256",
                "time_iss_created": "$timeIssCreated",
                "iss": "$iss",
                "key_identifier": "$keyIdentifier"
              }
            }
            """.trimIndent(),
        )

    fun registerEncryptedDevice(
        identifier: String,
        pushkey: String,
        iss: String,
        timeIssCreated: String,
        keyIdentifier: String,
    ) {
        postPusherWithEncryption(identifier, pushkey, iss, timeIssCreated, keyIdentifier).statusCode(200)
        setChannelsOfDevice(identifier, pushkey).statusCode(200)
    }

    fun postNotificationWithPayload(
        identifier: String,
        payload: String,
        channel: String = CHANNEL,
    ): ValidatableResponse =
        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .body(
                """
                {
                  "user": { "id_type": "kvnr", "value": "$identifier" },
                  "channel": "$channel",
                  "payload": $payload
                }
                """.trimIndent(),
            ).`when`()
            .post("/notifications")
            .then()

    fun deregisterPusher(
        identifier: String,
        pushkey: String,
        appId: String = DEFAULT_APP_ID,
    ): ValidatableResponse =
        postPusherWithBody(
            identifier,
            """
            {
              "pushkey": "$pushkey",
              "app_id": "$appId",
              "kind": null
            }
            """.trimIndent(),
        )

    fun postNotificationSuccessfullyGetId(
        identifier: String,
        message: String = "Hello World!",
        idempotencyKey: String? = null,
        channel: String = CHANNEL,
        reference: String = "trigger-42",
    ): String =
        postNotification(identifier, message, idempotencyKey, channel, reference)
            .statusCode(202)
            .extract()
            .path("notification_id")

    fun postNotification(
        identifier: String,
        message: String = "Hello World!",
        idempotencyKey: String? = null,
        channel: String = CHANNEL,
        reference: String = "trigger-42",
    ): ValidatableResponse =
        RestAssured
            .given()
            .contentType(ContentType.JSON)
            .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
            .body(
                """
                {
                  "user": { "id_type": "kvnr", "value": "$identifier" },
                  "channel": "$channel",
                  "payload": { "message": "$message", "document_id": "123" },
                  "reference": "$reference"
                }
                """.trimIndent(),
            ).`when`()
            .post("/notifications")
            .then()

    fun getChannels(
        identifier: String,
        query: String?,
    ): ValidatableResponse =
        RestAssured
            .get("users/$identifier/channels" + (query?.let { "?$it" } ?: ""))
            .then()

    fun getAvailableChannels(
        identifier: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec.`when`().get("/channels").then()
    }

    fun getChannelsOfDevice(
        identifier: String,
        pushkey: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec.`when`().get("/channels/$pushkey").then()
    }

    private fun buildChannelBody(
        channel: String,
        status: ChannelsInner.Status,
    ) = """{"channels":[{"id":"$channel","status":"$status"}]}"""

    fun setChannelsOfDevice(
        identifier: String,
        pushkey: String,
        status: ChannelsInner.Status = ChannelsInner.Status.enabled,
        channel: String = CHANNEL,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec
            .contentType(ContentType.JSON)
            .body(buildChannelBody(channel, status))
            .`when`()
            .post("/channels/$pushkey")
            .then()
    }

    fun setChannelsOfDeviceWithBody(
        identifier: String,
        pushkey: String,
        body: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec
            .contentType(ContentType.JSON)
            .body(body)
            .`when`()
            .post("/channels/$pushkey")
            .then()
    }

    private fun channelMapFrom(response: ValidatableResponse): Map<String, String> {
        val extracted = response.statusCode(200).extract()
        val ids = extracted.jsonPath().getList<String>("channels.id")
        val statuses = extracted.jsonPath().getList<String>("channels.status")
        return ids.zip(statuses).toMap(LinkedHashMap())
    }

    fun registryChannels(identifier: String): Map<String, String> = channelMapFrom(getAvailableChannels(identifier))

    fun channelsOf(
        identifier: String,
        pushkey: String,
    ): Map<String, String> = channelMapFrom(getChannelsOfDevice(identifier, pushkey))

    fun getPushers(
        identifier: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec.`when`().get("/pushers").then()
    }

    fun pushkeysOf(identifier: String): List<String> =
        getPushers(identifier)
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("pushers.pushkey")

    fun userInfo(identifier: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                """{"identifier":"$identifier","professionOID":"1.2.276.0.76.4.16","commonName":"Test Institution"}"""
                    .toByteArray(),
            )

    fun getNotificationHistory(
        identifier: String,
        pushkey: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec.`when`().get("/history/device/$pushkey").then()
    }

    fun getNotification(
        identifier: String,
        notificationId: String,
        authenticated: Boolean = true,
    ): ValidatableResponse {
        val spec = RestAssured.given()
        if (authenticated) spec.header(USER_INFO_HEADER, userInfo(identifier))
        return spec.`when`().get("/history/$notificationId").then()
    }
}
