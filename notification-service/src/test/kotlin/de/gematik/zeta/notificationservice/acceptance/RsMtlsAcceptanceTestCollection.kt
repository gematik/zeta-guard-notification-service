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

import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectRsMtls
import de.gematik.zeta.notificationservice.integration.quarkusResources.RsMtlsMaterial
import de.gematik.zeta.notificationservice.testsupport.TestClient.USER_INFO_HEADER
import de.gematik.zeta.notificationservice.testsupport.TestClient.userInfo
import io.quarkus.test.common.http.TestHTTPResource
import io.restassured.RestAssured
import io.restassured.config.RestAssuredConfig
import io.restassured.config.SSLConfig
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.net.URL

abstract class RsMtlsAcceptanceTestCollection {
    @InjectRsMtls
    lateinit var mtls: RsMtlsMaterial

    @TestHTTPResource(tls = true)
    lateinit var httpsUrl: URL

    // A_29980

    @Test
    @DisplayName("A_29980: a resource server with a trusted client certificate reaches POST /notifications")
    fun trustedClientCertificateReachesSubmit() {
        https(mtls.trustedClient)
            .body(notificationFor(UNKNOWN_USER))
            .post("/notifications")
            .then()
            .statusCode(404)
            .body("errorCode", equalTo("USER_NOT_FOUND"))
    }

    @Test
    @DisplayName("A_29980: POST /notifications without a client certificate is rejected with 401")
    fun submitWithoutClientCertificateIsRejected() {
        https()
            .body(notificationFor(UNKNOWN_USER))
            .post("/notifications")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("A_29980: a trailing slash does not bypass the client-certificate check")
    fun trailingSlashDoesNotBypassTheCheck() {
        https()
            .body(notificationFor(UNKNOWN_USER))
            .post("/notifications/")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("A_29980: GET /users/{userId}/channels without a client certificate is rejected with 401")
    fun channelsWithoutClientCertificateAreRejected() {
        https()
            .get("/users/$UNKNOWN_USER/channels?id_type=kvnr")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("A_29980: a resource-server call over plain HTTP is rejected with 401, zeta-user-info notwithstanding")
    fun plainHttpIsRejected() {
        RestAssured
            .given()
            .header(USER_INFO_HEADER, userInfo(UNKNOWN_USER))
            .contentType(ContentType.JSON)
            .body(notificationFor(UNKNOWN_USER))
            .post("/notifications")
            .then()
            .statusCode(401)
    }

    @Test
    @DisplayName("A_29980: a client certificate the trust store does not hold aborts the handshake")
    fun untrustedClientCertificateAbortsTheHandshake() {
        assertThrows<IOException> {
            https(mtls.forgedClient)
                .body(notificationFor(UNKNOWN_USER))
                .post("/notifications")
        }
    }

    @Test
    @DisplayName("A_29980: an expired client certificate aborts the handshake, even when the trust store pins it")
    fun expiredClientCertificateAbortsTheHandshake() {
        assertThrows<IOException> {
            https(mtls.expiredClient)
                .body(notificationFor(UNKNOWN_USER))
                .post("/notifications")
        }
    }

    @Test
    @DisplayName("A_29980: FdV paths keep authenticating through zeta-user-info over HTTPS")
    fun fdvPathsKeepTheGatewayHeader() {
        https()
            .header(USER_INFO_HEADER, userInfo(FDV_USER))
            .get("/pushers")
            .then()
            .statusCode(200)
    }

    @Test
    @DisplayName("A_29980: on FdV paths zeta-user-info takes precedence over a client certificate")
    fun gatewayHeaderTakesPrecedenceOverAClientCertificate() {
        https(mtls.trustedClient)
            .header(USER_INFO_HEADER, userInfo(FDV_USER))
            .get("/pushers")
            .then()
            .statusCode(200)
    }

    private fun https(clientKeyStore: File? = null): RequestSpecification {
        val ssl =
            SSLConfig
                .sslConfig()
                .keystoreType("PKCS12")
                .let { config -> clientKeyStore?.let { config.keyStore(it, mtls.keyStorePassword) } ?: config }
                .relaxedHTTPSValidation()
        return RestAssured
            .given()
            .config(RestAssuredConfig.config().sslConfig(ssl))
            .baseUri("https://localhost")
            .port(httpsUrl.port)
            .contentType(ContentType.JSON)
    }

    private fun notificationFor(identifier: String): String =
        """
        {
          "user": { "id_type": "kvnr", "value": "$identifier" },
          "channel": "epa.documents.new",
          "payload": { "message": "Hello World!" }
        }
        """.trimIndent()

    private companion object {
        const val UNKNOWN_USER = "X110417001"
        const val FDV_USER = "X110417002"
    }
}
