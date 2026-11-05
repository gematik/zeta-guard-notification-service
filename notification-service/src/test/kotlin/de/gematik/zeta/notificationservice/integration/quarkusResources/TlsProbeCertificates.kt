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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Server key material for [TlsProbeServer], generated fresh per test run — nothing is checked in.
 *
 * Three self-signed `CN=localhost` identities, all with a `localhost` SAN so the Push Gateway client's
 * hostname verification (which stays on, see `RestPushGatewayClient.buildClient`) is satisfied:
 *  - **RSA 3072** — serves the `TLS_ECDHE_RSA_*` suites,
 *  - **EC P-256** — serves the `TLS_ECDHE_ECDSA_*` suites,
 *  - **RSA 2048** — the undersized key for the key-length scenario (TR-02102-2 Tab. 14 asks for 3000 bit).
 *
 * The first two share one [SSLContext] so JSSE picks between them by negotiated cipher suite; the
 * undersized one gets its own so it is only ever presented when a test asks for it.
 *
 * All three go into the PEM handed to the service as `push-gateway.trusted-ca-paths`, which is why they
 * are marked as CAs: a self-signed certificate has to be usable as a trust anchor here.
 */
internal object TlsProbeCertificates {
    private const val RSA_KEY_SIZE = 3072
    private const val WEAK_RSA_KEY_SIZE = 2048
    private val VALIDITY_DURATION_IN_DAYS = Duration.ofDays(365)
    private val STORE_PASSWORD = "probe".toCharArray()

    data class Material(
        val standardContext: SSLContext,
        val weakRsaContext: SSLContext,
        val trustedPem: String,
    )

    fun generate(): Material {
        val rsa = generateRsa(RSA_KEY_SIZE)
        val ec = generateEc()
        val weakRsa = generateRsa(WEAK_RSA_KEY_SIZE)

        val rsaCert = selfSigned(rsa, "SHA256withRSA")
        val ecCert = selfSigned(ec, "SHA256withECDSA")
        val weakRsaCert = selfSigned(weakRsa, "SHA256withRSA")

        return Material(
            standardContext =
                sslContext(mapOf("rsa" to (rsa to rsaCert), "ec" to (ec to ecCert))),
            weakRsaContext = sslContext(mapOf("weak-rsa" to (weakRsa to weakRsaCert))),
            trustedPem = listOf(rsaCert, ecCert, weakRsaCert).joinToString("") { toPem(it) },
        )
    }

    private fun sslContext(entries: Map<String, Pair<KeyPair, X509Certificate>>): SSLContext {
        val store =
            KeyStore.getInstance("PKCS12").apply {
                load(null, STORE_PASSWORD)
                entries.forEach { (alias, entry) ->
                    val (keyPair, certificate) = entry
                    setKeyEntry(alias, keyPair.private, STORE_PASSWORD, arrayOf(certificate))
                }
            }

        val keyManagers =
            KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, STORE_PASSWORD) }
                .keyManagers

        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, SecureRandom()) }
    }

    internal fun selfSigned(
        keyPair: KeyPair,
        signatureAlgorithm: String,
        notAfter: Instant = Instant.now().plus(VALIDITY_DURATION_IN_DAYS),
    ): X509Certificate {
        val now = Instant.now()
        val subject = X500Name("CN=localhost")
        val builder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(notAfter.minus(VALIDITY_DURATION_IN_DAYS.plusDays(1))),
                Date.from(notAfter),
                subject,
                keyPair.public,
            ).addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, "localhost")),
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(0))
                .addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment or KeyUsage.keyCertSign),
                )

        val provider = BouncyCastleProvider()
        val signer = JcaContentSignerBuilder(signatureAlgorithm).setProvider(provider).build(keyPair.private)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
    }

    internal fun generateRsa(keySize: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(keySize) }.generateKeyPair()

    private fun generateEc(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    internal fun toPem(certificate: X509Certificate): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}
