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

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.testsupport.TestClient.CHANNEL
import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import de.gematik.zeta.notificationservice.testsupport.TestClient.setChannelsOfDevice
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

@QuarkusTest
@WithDatabase
class UserChannelsResourceTest {
    @Test
    fun getActiveChannels_forUserWithEnabledChannel_returnsOnlyEnabledChannels() {
        val identifier = "X110415001"
        registerPusher(identifier, "channels-enabled")
        setChannelsOfDevice(identifier, "channels-enabled", ChannelsInner.Status.enabled, CHANNEL).statusCode(200)

        getChannels(identifier, "id_type=kvnr")
            .statusCode(200)
            .body("user.id_type", equalTo("kvnr"))
            .body("user.value", equalTo(identifier))
            .body("has_registered_devices", equalTo(true))
            .body("registered_device_count", equalTo(1))
            .body("channels", hasSize<Any>(1))
            .body("channels[0].id", equalTo(CHANNEL))
            .body("channels[0].status", equalTo("enabled"))
    }

    @Test
    fun getActiveChannels_withIncludeInactive_returnsAllConfiguredChannels() {
        val identifier = "X110415002"
        val pushkey = "channels-inactive"
        registerPusher(identifier, pushkey)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.enabled, CHANNEL).statusCode(200)
        setChannelsOfDevice(identifier, pushkey, ChannelsInner.Status.disabled, "channel1").statusCode(200)

        getChannels(identifier, query = "id_type=kvnr&include_inactive=true")
            .statusCode(200)
            .body("channels", hasSize<Any>(3))
            .body("channels.find { it.id == '$CHANNEL' }.status", equalTo("enabled"))
            .body("channels.find { it.id == 'channel1' }.status", equalTo("disabled"))
            .body("channels.find { it.id == 'channel2' }.status", equalTo("not_set"))
    }

    @Test
    fun getActiveChannels_withTwoDevices_reportsTheDeviceCount() {
        val identifier = "X110415003"
        registerPusher(identifier, "channels-device-1")
        registerPusher(identifier, "channels-device-2")
        setChannelsOfDevice(identifier, "channels-device-1", ChannelsInner.Status.enabled, CHANNEL).statusCode(200)

        getChannels(identifier, "id_type=kvnr")
            .statusCode(200)
            .body("has_registered_devices", equalTo(true))
            .body("registered_device_count", equalTo(2))
    }

    @Test
    fun getActiveChannels_withTelematikId_returns200EchoingTheIdType() {
        val identifier = "5-2-KH-AKA-1234567890"
        registerPusher(identifier, "channels-telematik")
        setChannelsOfDevice(identifier, "channels-telematik", ChannelsInner.Status.enabled, CHANNEL).statusCode(200)

        getChannels(identifier, query = "id_type=telematik-id")
            .statusCode(200)
            .body("user.id_type", equalTo("telematik-id"))
            .body("user.value", equalTo(identifier))
            .body("channels[0].id", equalTo(CHANNEL))
    }

    @Test
    fun getActiveChannels_forUnknownUser_returns404WithUserNotFound() {
        getChannels("X110415404", "id_type=kvnr")
            .statusCode(404)
            .body("errorCode", equalTo("USER_NOT_FOUND"))
    }

    @Test
    fun getActiveChannels_withInvalidIdType_returns400WithInvalidParam() {
        getChannels("X110415001", query = "id_type=iban")
            .statusCode(400)
            .body("errorCode", equalTo("INVALID_PARAM"))
    }

    @Test
    fun getActiveChannels_withMalformedUserId_returns400() {
        getChannels(".invalid", query = "id_type=telematik-id")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }

    @Test
    fun getActiveChannels_withTelematikIdDeclaredAsKvnr_returns400WithInvalidParam() {
        getChannels("5-2-KH-AKA-1234567890", query = "id_type=kvnr")
            .statusCode(400)
            .body("errorCode", equalTo("M_INVALID_PARAM"))
    }
}
