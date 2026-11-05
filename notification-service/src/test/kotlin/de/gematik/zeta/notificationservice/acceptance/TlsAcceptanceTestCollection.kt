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

import de.gematik.zeta.notificationservice.integration.quarkusResources.ClientHello
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectTlsProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.TlsProbeConnection
import de.gematik.zeta.notificationservice.integration.quarkusResources.TlsProbeServer
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotificationSuccessfullyGetId
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerEnabledDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A_29991 — "Der Notification Service MUSS für alle TLS-/mTLS-Verbindungen (RS-Schnittstelle sowie Push
 * Gateway) die in [gemSpec_Krypt] zugelassenen TLS-Versionen, Cipher-Suiten und Schlüssellängen verwenden."
 *
 * **Scope.** This collection covers the Push Gateway leg. The FdV-facing leg is terminated *upstream* of
 * this service, at the PEP. The RS-facing leg is the service's own HTTPS listener (A_29980): it takes its
 * protocols and suites from `quarkus.tls` in `application.yaml`, and `RsMtlsAcceptanceTestCollection`
 * exercises its client-certificate check; curves and key sizes are not pinned there.
 *
 * The outbound leg is different: `RestPushGatewayClient` builds its own REST client per allowed base URL, so
 * the TLS parameters it offers and accepts are the service's own behaviour. `TlsProbeServer` stands in for
 * the Push Gateway and records both.
 *
 * **Why this is not a group inside [AcceptanceTestCollection]**: `push-gateway.allowed-base-urls` is read
 * once at startup, so pointing the client at an HTTPS probe cannot coexist with the plain-HTTP
 * `PushGatewayMock` in the same application instance. Same reasoning as `HistoryDeactivatedAcceptanceTest`.
 * They still run twice like [AcceptanceTestCollection]:
 *  * 1. As normal @QuarkusTests with other unit tests and so on. [TlsAcceptanceTest]
 *  * 2. As @QuarkusIntegrationTests against the built native image in the ci. [TlsAcceptanceIT]
 *
 * Requirements exercised below (gemSpec_Krypt, latest):
 *  - GS-A_4385 / A_18467 / A_18464 / GS-A_4387 / GS-A_5035 — TLS 1.2 required, TLS 1.3 optional,
 *    TLS 1.1, TLS 1.0 and SSL forbidden.
 *  - GS-A_4384-03 / A_17124-03 — mandated cipher suites and ECDHE curves, ECDSA preferred over RSA.
 *  - A_17322 — only permitted suites and versions may be *offered*, not merely used.
 *  - GS-A_4359-02 / gemSpec_Krypt §4 / A_28976 — minimum key lengths per [TR-02102-2, Tab. 14].
 *
 * A_21275-01 (no SHA-1 in handshake signatures) is deliberately not asserted: its own Erläuterung states
 * that merely listing SHA-1 in `signature_algorithms` "soll nicht als fehlerhaftes Verhalten gelten", which
 * is exactly what every Java TLS stack does, and the signatures actually produced are not observable here.
 */
abstract class TlsAcceptanceTestCollection {
    @InjectTlsProbe
    lateinit var probe: TlsProbeServer

    @BeforeEach
    fun resetProbe() {
        probe.reset()
    }

    // A_29991

    @Test
    @DisplayName("A_29991: only TLS 1.2 and TLS 1.3 are offered to the Push Gateway")
    fun onlyPermittedTlsVersionsAreOffered() {
        val hello = helloOf(dispatchTo("0001"))

        assertTrue(ClientHello.TLS_1_2 in hello.offeredVersions) {
            "GS-A_4385 requires TLS 1.2 support, offered: ${versions(hello.offeredVersions)}"
        }
        assertEquals(
            emptySet<Int>(),
            hello.offeredVersions - setOf(ClientHello.TLS_1_2, ClientHello.TLS_1_3),
        ) {
            "TLS 1.1 (A_18464), TLS 1.0 (GS-A_4387) and SSL (GS-A_5035) must not be offered, " +
                "offered: ${versions(hello.offeredVersions)}"
        }
        assertEquals(ClientHello.TLS_1_2, hello.legacyVersion) {
            "the record-layer legacy_version must not advertise a forbidden version"
        }
    }

    @Test
    @DisplayName("A_29991: the cipher suites mandated by gemSpec_Krypt are offered")
    fun mandatedCipherSuitesAreOffered() {
        val hello = helloOf(dispatchTo("0002"))
        val offered = hello.offeredCipherSuites.toSet()

        assertEquals(emptySet<Int>(), MANDATED_TLS12_SUITES - offered) {
            "GS-A_4384-03 and A_17124-03 mandate ${suites(MANDATED_TLS12_SUITES)}, " +
                "offered: ${suites(offered)}"
        }
        if (ClientHello.TLS_1_3 in hello.offeredVersions) {
            assertTrue(TLS13_AES_128_GCM_SHA256 in offered) {
                "A_18467 allows TLS 1.3 only when TLS_AES_128_GCM_SHA256 is supported, " +
                    "offered: ${suites(offered)}"
            }
        }
    }

    @Test
    @DisplayName("A_29991: no cipher suite outside the permitted set is offered")
    fun noCipherSuiteOutsideThePermittedSetIsOffered() {
        val hello = helloOf(dispatchTo("0003"))

        // A_17322 constrains what is *offered*, not only what is negotiated, so the whole cipher_suites
        // list has to stay inside TR-02102-2. The signalling values carry no cryptography and are exempt.
        val forbidden =
            hello.offeredCipherSuites
                .filterNot { it in PERMITTED_SUITES }
                .filterNot { it == TLS_EMPTY_RENEGOTIATION_INFO_SCSV || isGrease(it) }
                .toSet()

        assertEquals(emptySet<Int>(), forbidden) {
            "A_17322 permits only cipher suites from [TR-02102-2, §3.3.1.1 / Tab. 13], but these were " +
                "offered: ${suites(forbidden)}"
        }
    }

    @Test
    @DisplayName("A_29991: the elliptic curves mandated by gemSpec_Krypt are offered")
    fun mandatedEllipticCurvesAreOffered() {
        val hello = helloOf(dispatchTo("0004"))

        assertEquals(emptySet<Int>(), MANDATED_GROUPS - hello.offeredGroups.toSet()) {
            "GS-A_4384-03 point 3 requires P-256 and P-384 for the ephemeral ECDH key exchange, " +
                "offered: ${groups(hello.offeredGroups)}"
        }
    }

    @Test
    @DisplayName("A_29991: no group outside the permitted set is offered")
    fun noGroupOutsideThePermittedSetIsOffered() {
        val hello = helloOf(dispatchTo("0005"))

        // GS-A_4384-03 forbids *using* any curve outside P-256/P-384/brainpool. The server picks from the
        // whole supported_groups list (and in TLS 1.3 the key share decides), so the client can only ensure
        // that by not offering one. Finite-field groups are held to [TR-02102-2, Tab. 6/10] and the 3000-bit
        // DH floor of Tab. 14, which leaves ffdhe3072 and ffdhe4096.
        val permitted = PERMITTED_GROUPS + PERMITTED_FFDHE_GROUPS
        val forbidden = hello.offeredGroups.filterNot { it in permitted || isGrease(it) }.toSet()

        assertEquals(emptySet<Int>(), forbidden) {
            "GS-A_4384-03 point 3 and TR-02102-2 permit only ${groups(permitted)}, " +
                "but these were offered: ${groups(forbidden)}"
        }
    }

    @Test
    @DisplayName("A_29991: the mandated ECDSA cipher suites are preferred over every RSA cipher suite")
    fun mandatedEcdsaCipherSuitesArePreferredOverEveryRsaOne() {
        val hello = helloOf(dispatchTo("0006"))
        val offered = hello.offeredCipherSuites

        // A_17124-03 point 3 names "die eben genannten Ciphersuiten" — C02B and C02C — and puts them ahead of
        // any RSA-based suite, which includes DHE_RSA and static-RSA suites as well as ECDHE_RSA.
        assertTrue(MANDATED_ECDSA_SUITES.all { it in offered }) {
            "A_17124-03 requires ${suites(MANDATED_ECDSA_SUITES)} to be supported, offered: ${suites(offered)}"
        }

        val lastMandatedEcdsa = MANDATED_ECDSA_SUITES.maxOf { offered.indexOf(it) }
        val firstRsa = offered.indexOfFirst { it in RSA_AUTHENTICATED_SUITES }
        assertTrue(firstRsa < 0 || lastMandatedEcdsa < firstRsa) {
            "A_17124-03 point 3 requires ${suites(MANDATED_ECDSA_SUITES)} to be listed ahead of every RSA " +
                "suite, offered: ${suites(offered)}"
        }
    }

    @Test
    @DisplayName("A_29991: TLS 1.2 with TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 is negotiated successfully")
    fun tls12WithEcdheRsaAes128GcmIsNegotiated() {
        assertNegotiates("0007", TLS_1_2_NAME, "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")
    }

    @Test
    @DisplayName("A_29991: TLS 1.2 with TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 is negotiated successfully")
    fun tls12WithEcdheRsaAes256GcmIsNegotiated() {
        assertNegotiates("0008", TLS_1_2_NAME, "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384")
    }

    @Test
    @DisplayName("A_29991: TLS 1.2 with TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 is negotiated successfully")
    fun tls12WithEcdheEcdsaAes128GcmIsNegotiated() {
        assertNegotiates("0009", TLS_1_2_NAME, "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
    }

    @Test
    @DisplayName("A_29991: TLS 1.3 with TLS_AES_128_GCM_SHA256 is negotiated successfully")
    fun tls13WithAes128GcmIsNegotiated() {
        assertNegotiates("0010", TLS_1_3_NAME, "TLS_AES_128_GCM_SHA256")
    }

    @Test
    @DisplayName("A_29991: a Push Gateway offering only a forbidden cipher suite is not talked to")
    fun aGatewayOfferingOnlyAForbiddenCipherSuiteIsNotTalkedTo() {
        // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA (SHA-1 HMAC) is absent from [TR-02102-2, §3.3.1.1]. It has to be a
        // suite the JDK still enables: jdk.tls.disabledAlgorithms applies to the probe's server side as well,
        // so a JDK-disabled suite such as TLS_RSA_* would make the handshake fail on the probe's side and the
        // scenario pass whatever the service offers. For the same reason TLS 1.0/1.1 are covered by the
        // offer-side assertion in onlyPermittedTlsVersionsAreOffered() instead.
        probe.serve(
            protocols = listOf(TLS_1_2_NAME),
            cipherSuites = listOf("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"),
        )
        val connection = dispatchTo("0011")

        assertFalse(connection.handshakeSucceeded) {
            "A_17322 forbids using a cipher suite outside the permitted set"
        }
        assertNull(connection.httpRequest) { "no notification may be sent over a non-conformant channel" }
    }

    @Test
    @DisplayName("A_29991: a Push Gateway presenting an undersized RSA key is not talked to")
    fun aGatewayPresentingAnUndersizedRsaKeyIsNotTalkedTo() {
        // RSA-2048 was permitted by GS-A_4359-02 / Tab_KRYPT_002 only "bis Ende 2025"; since then the 120-bit
        // level of gemSpec_Krypt §4 applies, and A_28976 points to [TR-02102-2, Tab. 14]: RSA at least 3000
        // bit. The JDK's default jdk.certpath.disabledAlgorithms only blocks "RSA keySize < 1024", so a
        // 2048-bit peer is accepted unless the service raises that floor itself — which RestPushGatewayClient
        // does with a leaf-key check in its TrustManager.
        probe.serve(
            protocols = listOf(TLS_1_2_NAME),
            cipherSuites = listOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
            certificate = TlsProbeServer.ServerCert.WEAK_RSA,
        )
        val connection = dispatchTo("0012")

        assertFalse(connection.handshakeSucceeded) {
            "TR-02102-2 Tab. 14 (via A_28976 and gemSpec_Krypt §4) requires at least 3000-bit RSA keys, " +
                "a 2048-bit peer must be refused"
        }
        assertNull(connection.httpRequest) { "no notification may be sent over a non-conformant channel" }
    }

    /**
     * Registers a device pointing at the probe, submits a notification and waits for the resulting outbound
     * connection. `sequence` keeps the KVNR and the pushkey unique per scenario.
     *
     * Never assert on the *number* of connections: a refused handshake is retried
     * (`push-gateway.retry.give-up-after`), so a rejecting scenario produces several of them.
     */
    private fun dispatchTo(sequence: String): TlsProbeConnection {
        val identifier = "X29991$sequence"
        registerEnabledDevice(identifier, "acc-a29991-$sequence", url = probe.baseUrl())
        postNotificationSuccessfullyGetId(identifier)

        return probe.awaitConnection() ?: fail("the service never opened a connection to the Push Gateway")
    }

    private fun assertNegotiates(
        sequence: String,
        protocol: String,
        cipherSuite: String,
    ) {
        probe.serve(protocols = listOf(protocol), cipherSuites = listOf(cipherSuite))
        val connection = dispatchTo(sequence)

        assertTrue(connection.handshakeSucceeded) {
            "gemSpec_Krypt mandates support for $protocol with $cipherSuite"
        }
        assertNotNull(connection.httpRequest) {
            "the notification must be delivered over $protocol with $cipherSuite"
        }
    }

    private fun helloOf(connection: TlsProbeConnection): ClientHello =
        connection.clientHello ?: fail("the probe captured no parsable ClientHello")

    private companion object {
        const val TLS_1_2_NAME = "TLSv1.2"
        const val TLS_1_3_NAME = "TLSv1.3"

        const val TLS_EMPTY_RENEGOTIATION_INFO_SCSV = 0x00FF
        const val TLS13_AES_128_GCM_SHA256 = 0x1301

        // The four blocks below are the perfect-forward-secrecy cipher suites of TR-02102-2 §3.3.1.1
        // (numbered "Tabelle 1" in the edition gemSpec_Krypt cites, "Tabelle 3" in the current one) —
        // the only TLS 1.2 suites GS-A_4384-03 allows on top of the ones it mandates outright.

        /** ECDHE_ECDSA: AES 128/256 in CBC-SHA2, GCM and CCM. The CCM_8 variants are not listed. */
        val ECDHE_ECDSA_SUITES = setOf(0xC023, 0xC024, 0xC02B, 0xC02C, 0xC0AC, 0xC0AD)

        val ECDHE_RSA_SUITES = setOf(0xC027, 0xC028, 0xC02F, 0xC030)

        val DHE_DSS_SUITES = setOf(0x0040, 0x006A, 0x00A2, 0x00A3)

        val DHE_RSA_SUITES = setOf(0x0067, 0x006B, 0x009E, 0x009F, 0xC09E, 0xC09F)

        /** TLS 1.3 suites from [TR-02102-2, Tab. 13] — notably excluding ChaCha20-Poly1305 (0x1303). */
        val TLS13_SUITES = setOf(0x1301, 0x1302, 0x1304)

        val PERMITTED_SUITES =
            ECDHE_ECDSA_SUITES + ECDHE_RSA_SUITES + DHE_DSS_SUITES + DHE_RSA_SUITES + TLS13_SUITES

        /** GS-A_4384-03 point 2 (ECDHE_RSA) and A_17124-03 point 2 (ECDHE_ECDSA). */
        val MANDATED_TLS12_SUITES = setOf(0xC02F, 0xC030, 0xC02B, 0xC02C)

        /** A_17124-03 point 2: TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 and ..._AES_256_GCM_SHA384. */
        val MANDATED_ECDSA_SUITES = MANDATED_TLS12_SUITES intersect ECDHE_ECDSA_SUITES

        /** secp256r1 (P-256) and secp384r1 (P-384). */
        val MANDATED_GROUPS = setOf(0x0017, 0x0018)

        /**
         * P-256, P-384 plus the optional brainpool curves, in both their TLS 1.2 ([RFC-7027]) and their
         * TLS 1.3 ([RFC-8734]) code points. Deliberately narrower than TR-02102-2, which also recommends
         * secp521r1 and the ffdhe groups: GS-A_4384-03 says "Andere Kurven [...] DÜRFEN NICHT verwendet
         * werden" and lists only these.
         */
        val PERMITTED_GROUPS =
            MANDATED_GROUPS + setOf(0x001A, 0x001B, 0x001C, 0x001F, 0x0020, 0x0021)

        /** ffdhe3072 and ffdhe4096 — [TR-02102-2, Tab. 6/10]; ffdhe2048 is below the 3000-bit DH floor. */
        val PERMITTED_FFDHE_GROUPS = setOf(0x0101, 0x0102)

        val GROUP_NAMES =
            mapOf(
                0x0017 to "secp256r1",
                0x0018 to "secp384r1",
                0x0019 to "secp521r1",
                0x001A to "brainpoolP256r1",
                0x001B to "brainpoolP384r1",
                0x001C to "brainpoolP512r1",
                0x001F to "brainpoolP256r1tls13",
                0x0020 to "brainpoolP384r1tls13",
                0x0021 to "brainpoolP512r1tls13",
                0x001D to "x25519",
                0x001E to "x448",
                0x0100 to "ffdhe2048",
                0x0101 to "ffdhe3072",
                0x0102 to "ffdhe4096",
                0x0103 to "ffdhe6144",
                0x0104 to "ffdhe8192",
            )

        /**
         * Names for the suites a JSSE/Netty client is known to offer on top of the permitted set, so a
         * failing assertion reads as a requirement violation rather than a list of numbers.
         */
        val SUITE_NAMES =
            mapOf(
                0x0032 to "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
                0x0033 to "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
                0x0038 to "TLS_DHE_DSS_WITH_AES_256_CBC_SHA",
                0x0039 to "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
                0x009C to "TLS_RSA_WITH_AES_128_GCM_SHA256",
                0x009D to "TLS_RSA_WITH_AES_256_GCM_SHA384",
                0x1303 to "TLS_CHACHA20_POLY1305_SHA256",
                0xC009 to "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                0xC00A to "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                0xC013 to "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                0xC014 to "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                0xC02B to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                0xC02C to "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                0xC02F to "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                0xC030 to "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                0xCCA8 to "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                0xCCA9 to "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                0xCCAA to "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            )

        /** Every suite authenticated by RSA: the permitted ECDHE_RSA/DHE_RSA ones plus the known others. */
        val RSA_AUTHENTICATED_SUITES =
            ECDHE_RSA_SUITES + DHE_RSA_SUITES + SUITE_NAMES.filterValues { "_RSA_" in it }.keys

        val VERSION_NAMES =
            mapOf(
                ClientHello.SSL_3_0 to "SSLv3",
                ClientHello.TLS_1_0 to "TLSv1.0",
                ClientHello.TLS_1_1 to "TLSv1.1",
                ClientHello.TLS_1_2 to "TLSv1.2",
                ClientHello.TLS_1_3 to "TLSv1.3",
            )

        /** GREASE (RFC 8701) values are deliberate nonsense a client sends to keep middleboxes honest. */
        fun isGrease(value: Int): Boolean = (value shr 8) == (value and 0xFF) && (value and 0x0F0F) == 0x0A0A

        fun hex(value: Int): String = "0x%04X".format(value)

        fun suites(values: Collection<Int>): String =
            if (values.isEmpty()) "none" else values.joinToString { SUITE_NAMES[it] ?: hex(it) }

        fun groups(values: Collection<Int>): String =
            if (values.isEmpty()) "none" else values.joinToString { GROUP_NAMES[it] ?: hex(it) }

        fun versions(values: Collection<Int>): String =
            if (values.isEmpty()) "none" else values.joinToString { VERSION_NAMES[it] ?: hex(it) }
    }
}
