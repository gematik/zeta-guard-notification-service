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

import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.testsupport.TestClient.getPushers
import de.gematik.zeta.notificationservice.testsupport.TestClient.postPusherWithBody
import de.gematik.zeta.notificationservice.testsupport.TestClient.pushkeysOf
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Endpoint-level coverage for the pusher setup API (`gemF_PushNotification`, `A_27104`).
 *
 * Identifiers are drawn from the `X1104120xx` range on purpose: every `@QuarkusTest` class in a run shares the same
 * Postgres instance, and `ChannelResourceTest` already occupies `X1104110xx`.
 */
@QuarkusTest
@WithDatabase
class PusherResourceTest {
    // --- Registration and listing (A_27104) ---

    @Test
    fun postPusher_withValidPayload_returns200AndEmptyObject() {
        // The contract's 200 response is literally an empty object.
        postPusherWithBody("X110412001", pusherBody(pushkey = "tok-valid"))
            .statusCode(200)
            .body(equalTo("{}"))
    }

    @Test
    fun postPusher_thenGetPushers_returnsRegisteredPusherWithAllFields() {
        val identifier = "X110412002"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-echo")).statusCode(200)

        getPushers(identifier)
            .statusCode(200)
            .body("pushers.size()", equalTo(1))
            .body("pushers[0].pushkey", equalTo("tok-echo"))
            .body("pushers[0].app_id", equalTo("de.gematik.zeta.test"))
            .body("pushers[0].kind", equalTo("http"))
            .body("pushers[0].app_display_name", equalTo("Test App"))
            .body("pushers[0].device_display_name", equalTo("Test Device"))
            .body("pushers[0].lang", equalTo("de"))
            .body("pushers[0].data.url", equalTo("https://push.example/gateway"))
            .body("pushers[0].data.format", equalTo("event_id_only"))
            .body("pushers[0].append", equalTo(false))
    }

    @Test
    fun postPusher_withAppendTrue_thenGetPushers_echoesAppend() {
        val identifier = "X110412032"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-echo-append", append = true)).statusCode(200)

        getPushers(identifier)
            .statusCode(200)
            .body("pushers[0].append", equalTo(true))
    }

    @Test
    fun postPusher_neverEchoesTheEncryptionSecret() {
        // `iss` is the initial shared secret. The response schema has no encryption field; assert it stays out.
        val identifier = "X110412003"
        postPusherWithBody(
            identifier,
            pusherBody(pushkey = "tok-secret", encryption = VALID_ENCRYPTION),
        ).statusCode(200)

        getPushers(identifier).statusCode(200).body(not(containsString(ISS)))
    }

    @Test
    fun postPusher_twiceForSameAppIdAndPushkey_updatesInsteadOfDuplicating() {
        val identifier = "X110412004"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-dup", deviceDisplayName = "First")).statusCode(200)
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-dup", deviceDisplayName = "Second")).statusCode(200)

        getPushers(identifier)
            .statusCode(200)
            .body("pushers.size()", equalTo(1))
            .body("pushers[0].device_display_name", equalTo("Second"))
    }

    @Test
    fun postPusher_differingOnlyInAppId_registersBoth() {
        val identifier = "X110412005"
        postPusherWithBody(
            identifier,
            pusherBody(pushkey = "tok-shared", appId = "de.gematik.zeta.ios"),
        ).statusCode(200)
        postPusherWithBody(
            identifier,
            pusherBody(pushkey = "tok-shared", appId = "de.gematik.zeta.android"),
        ).statusCode(200)

        assertEquals(2, pushkeysOf(identifier).size)
    }

    // --- Input validation (A_29986) ---

    @Test
    fun postPusher_withoutPushkey_returns400MissingParam() {
        postPusherWithBody(
            "X110412006",
            """{"kind":"http","app_id":"de.gematik.zeta.test"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", containsString("pushkey"))
            // The generated DTO's fully-qualified name must not reach the client.
            .body("errorDetail", not(containsString("de.gematik.zeta.notificationservice")))
    }

    @Test
    fun postPusher_withoutKind_returns400MissingParam() {
        postPusherWithBody(
            "X110412007",
            """{"pushkey":"tok-nokind","app_id":"de.gematik.zeta.test"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", containsString("kind"))
    }

    @Test
    fun postPusher_withoutAppId_returns400MissingParam() {
        postPusherWithBody(
            "X110412008",
            """{"pushkey":"tok-noappid","kind":"http"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", containsString("app_id"))
    }

    @Test
    fun postPusher_withoutLangAndData_returns400ListingBothFields() {
        // Wording taken verbatim from the contract's 400 example for POST /pushers/set.
        postPusherWithBody(
            "X110412009",
            """
            {
              "pushkey": "tok-partial",
              "app_id": "de.gematik.zeta.test",
              "kind": "http",
              "app_display_name": "Test App",
              "device_display_name": "Test Device"
            }
            """.trimIndent(),
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", equalTo("Missing parameters: lang, data"))
    }

    @Test
    fun postPusher_withAllConditionalFieldsMissing_listsThemInContractOrder() {
        postPusherWithBody(
            "X110412010",
            """{"pushkey":"tok-bare","app_id":"de.gematik.zeta.test","kind":"http"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body(
                "errorDetail",
                equalTo("Missing parameters: app_display_name, device_display_name, lang, data"),
            )
    }

    @Test
    fun postPusher_withBlankPushkey_returns400InvalidParam() {
        // Present but empty, so a format violation: M_INVALID_PARAM, not the M_MISSING_PARAM of an absent key.
        postPusherWithBody("X110412011", pusherBody(pushkey = "   "))
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body("errorDetail", containsString("pushkey"))
    }

    @Test
    fun postPusher_withBlankAppId_returns400InvalidParam() {
        postPusherWithBody("X110412012", pusherBody(pushkey = "tok-blank-app", appId = ""))
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body("errorDetail", containsString("app_id"))
    }

    @Test
    fun postPusher_withBlankPushkeyAndNullKind_returns400InvalidParam() {
        // The deregistration path addresses a pusher by the same pair, so it rejects a blank one the same way.
        // Before the check moved into PusherId this answered 200, because kind: null never reached the factory.
        postPusherWithBody("X110412028", deletionBody(pushkey = "  "))
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body("errorDetail", containsString("pushkey"))
    }

    @Test
    fun postPusher_withHttpKindAndNoDataUrl_returns400InvalidParam() {
        // Present but inconsistent, so M_INVALID_PARAM rather than M_MISSING_PARAM.
        postPusherWithBody(
            "X110412013",
            """
            {
              "pushkey": "tok-nourl",
              "app_id": "de.gematik.zeta.test",
              "kind": "http",
              "app_display_name": "Test App",
              "device_display_name": "Test Device",
              "lang": "de",
              "data": { "format": "event_id_only" }
            }
            """.trimIndent(),
        ).statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    @Test
    fun postPusher_withIncompleteEncryption_returns400AndDoesNotEchoIss() {
        // `key_identifier` is missing. The failure must name the field without echoing the shared secret.
        postPusherWithBody(
            "X110412014",
            pusherBody(
                pushkey = "tok-partial-enc",
                encryption = """{"method":"aes-hmac-sha256","time_iss_created":"2026-07","iss":"$ISS"}""",
            ),
        ).statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body(not(containsString(ISS)))
    }

    @Test
    fun postPusher_withMalformedJson_returns400WithoutEchoingBody() {
        // A truncated body must not come back as an error detail: it can carry the pushkey and the shared secret.
        postPusherWithBody(
            "X110412015",
            """{"pushkey":"tok-broken","kind":"http","app_id":"x","iss":"$ISS" """,
        ).statusCode(400)
            .body(not(containsString(ISS)))
            .body(not(containsString("tok-broken")))
    }

    @Test
    fun postPusher_withWronglyTypedField_returns400WithoutEchoingBody() {
        postPusherWithBody(
            "X110412016",
            """{"pushkey":123,"kind":"http","app_id":"de.gematik.zeta.test"}""",
        ).statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
            .body("errorDetail", not(containsString("de.gematik.zeta.notificationservice")))
    }

    // --- User binding: only the authenticated user's pushers (A_29967) ---

    @Test
    fun getPushers_returnsOnlyPushersOfHeaderUser() {
        val owner = "X110412017"
        val other = "X110412018"
        postPusherWithBody(owner, pusherBody(pushkey = "tok-owner")).statusCode(200)
        postPusherWithBody(other, pusherBody(pushkey = "tok-other")).statusCode(200)

        assertEquals(listOf("tok-owner"), pushkeysOf(owner))
        assertEquals(listOf("tok-other"), pushkeysOf(other))
    }

    @Test
    fun getPushers_forUserWithoutPushers_returnsEmptyList() {
        getPushers("X110412019").statusCode(200).body("pushers.size()", equalTo(0))
    }

    /**
     * The ticket asked for `403` on foreign access. There is no reachable code path for it: the user is taken only
     * from the `zeta-user-info` header, never from a request parameter, and a record is addressed by a logical id
     * derived from that user. The FDV contract declares no `403` on the pusher paths either.
     *
     * Reporting "exists, but not yours" differently from "does not exist" would also make the endpoint an oracle for
     * probing arbitrary APNs/FCM device tokens (`A_29978`). So the object binding is proven by isolation instead: a
     * foreign deregistration is a no-op that leaves the owner's data untouched and reveals nothing.
     */
    @Test
    fun postPusher_withKindNull_forOtherUsersPusher_leavesOwnersPusherIntact() {
        val owner = "X110412020"
        val attacker = "X110412021"
        postPusherWithBody(owner, pusherBody(pushkey = "tok-protected")).statusCode(200)

        postPusherWithBody(attacker, deletionBody(pushkey = "tok-protected")).statusCode(200)

        assertEquals(listOf("tok-protected"), pushkeysOf(owner))
    }

    @Test
    fun postPusher_forUnknownPusherOfKnownUser_isIndistinguishableFromUnknownUser() {
        // Both answer 200: a differential response would leak whether a KVNR has any pushers at all (A_29978).
        val known = "X110412022"
        postPusherWithBody(known, pusherBody(pushkey = "tok-known")).statusCode(200)

        postPusherWithBody(known, deletionBody(pushkey = "tok-never-registered")).statusCode(200)
        postPusherWithBody("X110412023", deletionBody(pushkey = "tok-never-registered")).statusCode(200)
    }

    @Test
    fun postPusher_sameAppIdAndPushkeyForTwoUsers_withoutAppend_keepsOnlyLatestUser() {
        // The contract's default `append: false` asks the FD to drop other users' pushers sharing app_id+pushkey.
        val first = "X110412024"
        val second = "X110412025"
        postPusherWithBody(first, pusherBody(pushkey = "tok-collision")).statusCode(200)
        postPusherWithBody(second, pusherBody(pushkey = "tok-collision")).statusCode(200)

        assertEquals(emptyList<String>(), pushkeysOf(first))
        assertEquals(listOf("tok-collision"), pushkeysOf(second))
    }

    @Test
    fun postPusher_sameAppIdAndPushkeyForTwoUsers_withAppendTrue_keepsBothRegistrations() {
        val first = "X110412030"
        val second = "X110412031"
        postPusherWithBody(first, pusherBody(pushkey = "tok-shared-device")).statusCode(200)
        postPusherWithBody(second, pusherBody(pushkey = "tok-shared-device", append = true)).statusCode(200)

        assertEquals(listOf("tok-shared-device"), pushkeysOf(first))
        assertEquals(listOf("tok-shared-device"), pushkeysOf(second))
    }

    // --- Deregistration (A_29989) ---

    @Test
    fun postPusher_withKindNull_removesPusherFromGetPushers() {
        val identifier = "X110412026"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-doomed")).statusCode(200)

        postPusherWithBody(identifier, deletionBody(pushkey = "tok-doomed")).statusCode(200)

        getPushers(identifier).statusCode(200).body("pushers.size()", equalTo(0))
    }

    @Test
    fun postPusher_withKindNull_forUnknownUser_returns200() {
        // Deregistration is idempotent: the desired end state already holds.
        postPusherWithBody("X110412027", deletionBody(pushkey = "tok-absent")).statusCode(200)
    }

    @Test
    fun postPusher_withKindNull_twice_returns200BothTimes() {
        val identifier = "X110412028"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-twice")).statusCode(200)

        postPusherWithBody(identifier, deletionBody(pushkey = "tok-twice")).statusCode(200)
        postPusherWithBody(identifier, deletionBody(pushkey = "tok-twice")).statusCode(200)
    }

    @Test
    fun postPusher_withKindNull_forOneOfTwoPushers_keepsTheOther() {
        val identifier = "X110412029"
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-keep")).statusCode(200)
        postPusherWithBody(identifier, pusherBody(pushkey = "tok-drop")).statusCode(200)

        postPusherWithBody(identifier, deletionBody(pushkey = "tok-drop")).statusCode(200)

        assertEquals(listOf("tok-keep"), pushkeysOf(identifier))
    }

    // --- Contract shape (A_29972) ---

    @Test
    fun getPushers_returnsErrorShapeWithErrorCodeOnInvalidUser() {
        // Every error body follows the contract's `error` schema: {errorCode, errorDetail}.
        getPushers("not-a-kvnr!!")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    private fun pusherBody(
        pushkey: String,
        appId: String = "de.gematik.zeta.test",
        deviceDisplayName: String = "Test Device",
        encryption: String? = null,
        append: Boolean? = null,
    ): String =
        """
        {
          "pushkey": "$pushkey",
          "app_id": "$appId",
          "kind": "http",
          "app_display_name": "Test App",
          "device_display_name": "$deviceDisplayName",
          "lang": "de",
          "data": { "url": "https://push.example/gateway", "format": "event_id_only" }
          ${encryption?.let { ""","encryption": $it""" } ?: ""}
          ${append?.let { ""","append": $it""" } ?: ""}
        }
        """.trimIndent()

    private fun deletionBody(
        pushkey: String,
        appId: String = "de.gematik.zeta.test",
    ): String = """{"pushkey":"$pushkey","app_id":"$appId","kind":null}"""

    private companion object {
        /** A recognisable stand-in for the initial shared secret, used to assert it is never echoed. */
        const val ISS = "a1b2c3d4e5f6071829304a5b6c7d8e9f00112233445566778899aabbccddeeff"
        val VALID_ENCRYPTION =
            """
            {"method":"aes-hmac-sha256","time_iss_created":"2026-07","iss":"$ISS",
             "key_identifier":"3f2504e0-4f89-11d3-9a0c-0305e82c3301"}
            """.trimIndent()
    }
}
