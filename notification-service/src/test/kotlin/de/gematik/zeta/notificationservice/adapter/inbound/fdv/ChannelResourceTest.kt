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
import de.gematik.zeta.notificationservice.testsupport.TestClient.channelsOf
import de.gematik.zeta.notificationservice.testsupport.TestClient.deregisterPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.getAvailableChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannelsOfDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.registryChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.setChannelsOfDeviceWithBody
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@WithDatabase
class ChannelResourceTest {
    @Test
    fun getChannels_authenticated_returnsRegistryChannelsAsNotSet() {
        val channels = registryChannels("X110411001")

        assertEquals(listOf("channel1", "channel2", "epa.documents.new"), channels.keys.toList())
        assertEquals(setOf("not_set"), channels.values.toSet())
    }

    @Test
    fun getChannels_withoutUserInfo_returns401() {
        getAvailableChannels("ignored", authenticated = false).statusCode(401)
    }

    @Test
    fun getChannels_forUserWithoutPushers_returnsSameRegistry() {
        assertEquals(registryChannels("X110411002"), registryChannels("X110411003"))
    }

    @Test
    fun getChannelsOfDevice_forUnconfiguredDevice_returnsAllChannelsNotSet() {
        val identifier = "X110411004"
        registerPusher(identifier, "key-unconfigured")

        val channels = channelsOf(identifier, "key-unconfigured")

        assertEquals(listOf("channel1", "channel2", "epa.documents.new"), channels.keys.toList())
        assertEquals(setOf("not_set"), channels.values.toSet())
    }

    @Test
    fun getChannelsOfDevice_afterPartialConfiguration_reportsStoredAndNotSetStatuses() {
        val identifier = "X110411005"
        registerPusher(identifier, "key-partial")
        setChannelsOfDeviceWithBody(identifier, "key-partial", channelsBody("channel1" to "enabled")).statusCode(200)

        assertEquals(
            mapOf("channel1" to "enabled", "channel2" to "not_set", "epa.documents.new" to "not_set"),
            channelsOf(identifier, "key-partial"),
        )
    }

    @Test
    fun getChannelsOfDevice_forUnknownPushkey_returns404WithPusherNotFound() {
        val identifier = "X110411006"
        registerPusher(identifier, "key-known")

        getChannelsOfDevice(identifier, "key-unknown")
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
    }

    @Test
    fun getChannelsOfDevice_forUserWithoutAnyPusher_returns404WithPusherNotFound() {
        getChannelsOfDevice("X110411007", "key-none")
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
    }

    @Test
    fun postChannelsOfDevice_withSingleChannel_returns200AndEmptyObject() {
        val identifier = "X110411008"
        registerPusher(identifier, "key-single")

        setChannelsOfDeviceWithBody(identifier, "key-single", channelsBody("channel1" to "enabled"))
            .statusCode(200)
            .body(equalTo("{}"))
    }

    @Test
    fun postChannelsOfDevice_withChannelAbsentFromBody_leavesItUnchanged() {
        val identifier = "X110411009"
        registerPusher(identifier, "key-merge")
        setChannelsOfDeviceWithBody(
            identifier,
            "key-merge",
            channelsBody("channel1" to "enabled", "channel2" to "disabled"),
        ).statusCode(200)

        setChannelsOfDeviceWithBody(identifier, "key-merge", channelsBody("channel1" to "disabled")).statusCode(200)

        val channels = channelsOf(identifier, "key-merge")
        assertEquals("disabled", channels["channel1"])
        assertEquals("disabled", channels["channel2"])
    }

    @Test
    fun postChannelsOfDevice_withEmptyObject_isNoOp() {
        val identifier = "X110411010"
        registerPusher(identifier, "key-empty-object")
        setChannelsOfDeviceWithBody(
            identifier,
            "key-empty-object",
            channelsBody("channel1" to "enabled"),
        ).statusCode(200)

        setChannelsOfDeviceWithBody(identifier, "key-empty-object", "{}").statusCode(200)

        assertEquals("enabled", channelsOf(identifier, "key-empty-object")["channel1"])
    }

    @Test
    fun postChannelsOfDevice_withEmptyChannelArray_isNoOp() {
        val identifier = "X110411011"
        registerPusher(identifier, "key-empty-array")
        setChannelsOfDeviceWithBody(
            identifier,
            "key-empty-array",
            channelsBody("channel1" to "enabled"),
        ).statusCode(200)

        setChannelsOfDeviceWithBody(identifier, "key-empty-array", """{"channels":[]}""").statusCode(200)

        assertEquals("enabled", channelsOf(identifier, "key-empty-array")["channel1"])
    }

    @Test
    fun postChannelsOfDevice_withNotSetStatus_resetsAnEnabledChannel() {
        val identifier = "X110411012"
        registerPusher(identifier, "key-not-set")
        setChannelsOfDeviceWithBody(identifier, "key-not-set", channelsBody("channel1" to "enabled")).statusCode(200)

        setChannelsOfDeviceWithBody(identifier, "key-not-set", channelsBody("channel1" to "not_set")).statusCode(200)

        assertEquals("not_set", channelsOf(identifier, "key-not-set")["channel1"])
    }

    @Test
    fun postChannelsOfDevice_withDuplicateChannelId_returns400() {
        val identifier = "X110411013"
        registerPusher(identifier, "key-duplicate")

        setChannelsOfDeviceWithBody(
            identifier,
            "key-duplicate",
            channelsBody("channel1" to "enabled", "channel1" to "disabled"),
        ).statusCode(400)
    }

    @Test
    fun postChannelsOfDevice_forUnknownPushkey_returns404() {
        val identifier = "X110411014"
        registerPusher(identifier, "key-owned")

        setChannelsOfDeviceWithBody(identifier, "key-foreign", channelsBody("channel1" to "enabled")).statusCode(404)
    }

    @Test
    fun postChannelsOfDevice_withEmptyObjectForUnknownPushkey_returns404() {
        val identifier = "X110411015"
        registerPusher(identifier, "key-owned-empty-object")

        setChannelsOfDeviceWithBody(identifier, "key-absent", "{}").statusCode(404)
    }

    // ----- POST /channels/{pushkey}: error mapping -----

    @Test
    fun postChannelsOfDevice_withUnregisteredChannelId_returns403WithChannelNotAllowed() {
        val identifier = "X110411016"
        registerPusher(identifier, "key-unregistered")

        setChannelsOfDeviceWithBody(identifier, "key-unregistered", channelsBody("epa.unknown" to "enabled"))
            .statusCode(403)
            .body("errorCode", equalTo("CHANNEL_NOT_ALLOWED"))
    }

    @Test
    fun postChannelsOfDevice_withMalformedChannelId_returns400WithMInvalidParam() {
        val identifier = "X110411017"
        registerPusher(identifier, "key-malformed")

        setChannelsOfDeviceWithBody(identifier, "key-malformed", channelsBody("NOT VALID!" to "enabled"))
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    @Test
    fun postChannelsOfDevice_withUnknownStatusValue_returns400() {
        val identifier = "X110411018"
        registerPusher(identifier, "key-bad-status")

        setChannelsOfDeviceWithBody(
            identifier,
            "key-bad-status",
            """{"channels":[{"id":"channel1","status":"paused"}]}""",
        ).statusCode(400)
    }

    @Test
    fun postChannelsOfDevice_withMissingStatusField_returns400WithMissingParam() {
        // The contract's 400 example for this operation is errorCode M_MISSING_PARAM with
        // errorDetail "Missing parameters: <field>".
        val identifier = "X110411019"
        registerPusher(identifier, "key-missing-status")

        setChannelsOfDeviceWithBody(identifier, "key-missing-status", """{"channels":[{"id":"channel1"}]}""")
            .statusCode(400)
            .body("errorCode", equalTo("M_MISSING_PARAM"))
            .body("errorDetail", equalTo("Missing parameters: status"))
    }

    @Test
    fun postChannelsOfDevice_withMalformedJson_returns400() {
        val identifier = "X110411020"
        registerPusher(identifier, "key-bad-json")

        setChannelsOfDeviceWithBody(identifier, "key-bad-json", "{").statusCode(400)
    }

    @Test
    fun postChannelsOfDevice_withoutUserInfo_returns401() {
        setChannelsOfDeviceWithBody(
            "X110411099",
            "key-anonymous",
            channelsBody("channel1" to "enabled"),
            authenticated = false,
        ).statusCode(401)
    }

    // ----- Error precedence -----

    @Test
    fun postChannelsOfDevice_withMalformedChannelIdAndUnknownPushkey_returns400() {
        // Body validation precedes the device lookup, so the format violation wins over the 404.
        setChannelsOfDeviceWithBody(
            "X110411021",
            "key-unknown",
            channelsBody("NOT VALID!" to "enabled"),
        ).statusCode(400)
    }

    @Test
    fun postChannelsOfDevice_withUnregisteredChannelIdAndUnknownPushkey_returns403() {
        // Registry membership is user-independent static configuration, so it is validated before the
        // device lookup: 403 CHANNEL_NOT_ALLOWED wins over the 404 the unknown pushkey would give.
        setChannelsOfDeviceWithBody("X110411022", "key-unknown", channelsBody("epa.unknown" to "enabled"))
            .statusCode(403)
            .body("errorCode", equalTo("CHANNEL_NOT_ALLOWED"))
    }

    @Test
    fun postChannelsOfDevice_withMalformedAndUnregisteredChannelIds_returns400() {
        val identifier = "X110411023"
        registerPusher(identifier, "key-precedence")

        setChannelsOfDeviceWithBody(
            identifier,
            "key-precedence",
            channelsBody("epa.unknown" to "enabled", "NOT VALID!" to "enabled"),
        ).statusCode(400)
    }

    @Test
    fun getChannelsOfDevice_withoutUserInfoAndUnknownPushkey_returns401() {
        getChannelsOfDevice("X110411099", "key-unknown", authenticated = false).statusCode(401)
    }

    @Test
    fun postChannelsOfDevice_withUnregisteredChannelAmongValidOnes_persistsNothing() {
        val identifier = "X110411024"
        registerPusher(identifier, "key-atomic-403")
        setChannelsOfDeviceWithBody(identifier, "key-atomic-403", channelsBody("channel1" to "enabled")).statusCode(200)

        setChannelsOfDeviceWithBody(
            identifier,
            "key-atomic-403",
            channelsBody("channel2" to "enabled", "epa.unknown" to "enabled"),
        ).statusCode(403)

        val channels = channelsOf(identifier, "key-atomic-403")
        assertEquals("enabled", channels["channel1"])
        assertEquals("not_set", channels["channel2"])
    }

    @Test
    fun postChannelsOfDevice_withMalformedChannelIdAmongValidOnes_persistsNothing() {
        val identifier = "X110411025"
        registerPusher(identifier, "key-atomic-400")
        setChannelsOfDeviceWithBody(identifier, "key-atomic-400", channelsBody("channel1" to "enabled")).statusCode(200)

        setChannelsOfDeviceWithBody(
            identifier,
            "key-atomic-400",
            channelsBody("channel2" to "enabled", "NOT VALID!" to "enabled"),
        ).statusCode(400)

        val channels = channelsOf(identifier, "key-atomic-400")
        assertEquals("enabled", channels["channel1"])
        assertEquals("not_set", channels["channel2"])
    }

    @Test
    fun getChannelsOfDevice_forPushkeyOfAnotherUser_returns403() {
        val owner = "X110411026"
        val other = "X110411027"
        registerPusher(owner, "key-owner-read")

        getChannelsOfDevice(other, "key-owner-read")
            .statusCode(403)
            .body("errorCode", equalTo("M_FORBIDDEN"))
    }

    @Test
    fun postChannelsOfDevice_forPushkeyOfAnotherUser_returns403AndLeavesItUnchanged() {
        val owner = "X110411028"
        val other = "X110411029"
        registerPusher(owner, "key-owner-write")
        setChannelsOfDeviceWithBody(owner, "key-owner-write", channelsBody("channel1" to "enabled")).statusCode(200)

        setChannelsOfDeviceWithBody(other, "key-owner-write", channelsBody("channel1" to "disabled"))
            .statusCode(403)
            .body("errorCode", equalTo("M_FORBIDDEN"))

        assertEquals("enabled", channelsOf(owner, "key-owner-write")["channel1"])
    }

    @Test
    fun getChannelsOfDevice_forPushkeyItsOwnerDeregistered_returns404() {
        val owner = "X110411030"
        val other = "X110411031"
        registerPusher(owner, "key-released")
        deregisterPusher(owner, "key-released").statusCode(200)

        getChannelsOfDevice(other, "key-released")
            .statusCode(404)
            .body("errorCode", equalTo("PUSHER_NOT_FOUND"))
    }

    @Test
    fun postChannelsOfDevice_withTwoAppIdsSharingPushkey_sharesOneConfiguration() {
        val identifier = "X110411032"
        registerPusher(identifier, "key-two-apps", appId = "app.one")
        registerPusher(identifier, "key-two-apps", appId = "app.two")

        setChannelsOfDeviceWithBody(identifier, "key-two-apps", channelsBody("channel1" to "enabled")).statusCode(200)

        assertEquals("enabled", channelsOf(identifier, "key-two-apps")["channel1"])
    }

    private fun channelsBody(vararg channels: Pair<String, String>): String =
        channels.joinToString(
            separator = ",",
            prefix = """{"channels":[""",
            postfix = "]}",
        ) { (id, status) -> """{"id":"$id","status":"$status"}""" }
}
