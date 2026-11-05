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
package de.gematik.zeta.notificationservice.adapter.inbound.security

import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.testsupport.TestClient.USER_INFO_HEADER
import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannelsOfDevice
import de.gematik.zeta.notificationservice.testsupport.TestClient.getNotificationHistory
import de.gematik.zeta.notificationservice.testsupport.TestClient.getPushers
import de.gematik.zeta.notificationservice.testsupport.TestClient.postPusher
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import org.junit.jupiter.api.Test

@QuarkusTest
@WithDatabase
class FdvResourceSecurityTest {
    @Test
    fun getPushers_withoutUserInfo_returns401() {
        getPushers(IDENTIFIER, authenticated = false).statusCode(401)
    }

    @Test
    fun getChannels_withoutUserInfo_returns401() {
        getChannelsOfDevice(IDENTIFIER, "device-1", authenticated = false).statusCode(401)
    }

    @Test
    fun getHistory_withoutUserInfo_returns401() {
        getNotificationHistory(IDENTIFIER, "device-1", authenticated = false).statusCode(401)
    }

    @Test
    fun getPushers_withUserInfoMissingIdentifier_returns401() {
        RestAssured
            .given()
            .header(
                USER_INFO_HEADER,
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"professionOID":"1.2.3","commonName":"Test"}""".toByteArray()),
            ).`when`()
            .get("/pushers")
            .then()
            .statusCode(401)
    }

    @Test
    fun getPushers_withMalformedUserInfo_returns401() {
        RestAssured
            .given()
            .header(USER_INFO_HEADER, "@@@not-base64@@@")
            .`when`()
            .get("/pushers")
            .then()
            .statusCode(401)
    }

    @Test
    fun getPushers_withValidBase64ButNotJson_returns401() {
        RestAssured
            .given()
            .header(
                USER_INFO_HEADER,
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("not-json".toByteArray()),
            ).`when`()
            .get("/pushers")
            .then()
            .statusCode(401)
    }

    @Test
    fun getPushers_withUserInfo_returnsOk() {
        getPushers(IDENTIFIER).statusCode(200)
    }

    @Test
    fun postPusher_withUserInfo_returnsOk() {
        postPusher(IDENTIFIER, "device-1", url = "https://push.example/gateway").statusCode(200)
    }

    private companion object {
        const val IDENTIFIER = "X110411675"
    }
}
