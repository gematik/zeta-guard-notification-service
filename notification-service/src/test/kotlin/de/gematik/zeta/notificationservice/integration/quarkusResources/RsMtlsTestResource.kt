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
package de.gematik.zeta.notificationservice.integration.quarkusResources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

data class RsMtlsMaterial(
    val trustedClient: File,
    val forgedClient: File,
    val expiredClient: File,
    val keyStorePassword: String,
)

class RsMtlsTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var directory: Path
    private lateinit var material: RsMtlsMaterial

    override fun start(): Map<String, String> {
        directory = Files.createTempDirectory("rs-mtls")
        val server = TlsProbeCertificates.generateRsa(RSA_KEY_SIZE)
        val trusted = TlsProbeCertificates.generateRsa(RSA_KEY_SIZE)
        val forged = TlsProbeCertificates.generateRsa(RSA_KEY_SIZE)
        val expired = TlsProbeCertificates.generateRsa(RSA_KEY_SIZE)
        val trustedCertificate = TlsProbeCertificates.selfSigned(trusted, SIGNATURE_ALGORITHM)
        val expiredCertificate =
            TlsProbeCertificates.selfSigned(expired, SIGNATURE_ALGORITHM, Instant.now().minus(1, ChronoUnit.DAYS))

        material =
            RsMtlsMaterial(
                trustedClient = keyStore("trusted-client.p12", trusted, trustedCertificate),
                forgedClient =
                    keyStore("forged-client.p12", forged, TlsProbeCertificates.selfSigned(forged, SIGNATURE_ALGORITHM)),
                expiredClient = keyStore("expired-client.p12", expired, expiredCertificate),
                keyStorePassword = STORE_PASSWORD,
            )
        return mapOf(
            "quarkus.tls.key-store.pem.rs.cert" to
                write(
                    "server.crt",
                    TlsProbeCertificates.toPem(TlsProbeCertificates.selfSigned(server, SIGNATURE_ALGORITHM)),
                ),
            "quarkus.tls.key-store.pem.rs.key" to write("server.key", privateKeyPem(server)),
            "quarkus.tls.trust-store.pem.certs" to
                write(
                    "trusted-client.crt",
                    TlsProbeCertificates.toPem(trustedCertificate) + TlsProbeCertificates.toPem(expiredCertificate),
                ),
            "quarkus.http.auth.permission.rs.enabled" to "true",
        )
    }

    override fun inject(testInjector: QuarkusTestResourceLifecycleManager.TestInjector?) {
        testInjector?.injectIntoFields(
            material,
            AnnotatedAndMatchesType(InjectRsMtls::class.java, RsMtlsMaterial::class.java),
        )
    }

    override fun stop() {
        if (::directory.isInitialized) directory.toFile().deleteRecursively()
    }

    private fun write(
        name: String,
        content: String,
    ): String = Files.writeString(directory.resolve(name), content).toString()

    private fun keyStore(
        name: String,
        keyPair: KeyPair,
        certificate: X509Certificate,
    ): File {
        val file = directory.resolve(name).toFile()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("client", keyPair.private, STORE_PASSWORD.toCharArray(), arrayOf(certificate))
        file.outputStream().use { store.store(it, STORE_PASSWORD.toCharArray()) }
        return file
    }

    private fun privateKeyPem(keyPair: KeyPair): String {
        val body = Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private companion object {
        const val RSA_KEY_SIZE = 3072
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val STORE_PASSWORD = "rs-mtls"
        const val PEM_LINE_LENGTH = 64
    }
}
