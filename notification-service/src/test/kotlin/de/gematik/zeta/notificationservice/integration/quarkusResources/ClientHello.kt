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

import java.nio.ByteBuffer

/**
 * The parts of a TLS ClientHello that gemSpec_Krypt puts requirements on.
 *
 * gemSpec_Krypt constrains what a product may *offer*, not only what it ends up using (A_17322), so the
 * assertions in the TLS acceptance tests need the raw offer rather than the negotiated outcome. JSSE exposes
 * no hook for that, hence this parser: [TlsProbeServer] captures the first TLS record verbatim before it
 * hands the connection to the SSL engine, and this reads the fields out of it.
 *
 * Only the fields the acceptance tests assert on are decoded; everything else is skipped over.
 * See [RFC-8446 §4.1.2](https://www.rfc-editor.org/rfc/rfc8446#section-4.1.2) for the wire format.
 */
data class ClientHello(
    /** The `legacy_version` field. Pinned to TLS 1.2 by every client that supports TLS 1.3. */
    val legacyVersion: Int,
    /**
     * `cipher_suites`, in the order offered — the order carries the client's preference
     * (gemSpec_Krypt A_17124 point 3).
     */
    val offeredCipherSuites: List<Int>,
    /** The `supported_versions` extension, or `legacy_version` alone when the extension is absent. */
    val offeredVersions: Set<Int>,
    /** The `supported_groups` extension: the ECDHE curves on offer (gemSpec_Krypt GS-A_4384 point 3). */
    val offeredGroups: List<Int>,
    /** The `signature_algorithms` extension. */
    val offeredSignatureAlgorithms: Set<Int>,
) {
    companion object {
        const val SSL_3_0 = 0x0300
        const val TLS_1_0 = 0x0301
        const val TLS_1_1 = 0x0302
        const val TLS_1_2 = 0x0303
        const val TLS_1_3 = 0x0304

        private const val RECORD_TYPE_HANDSHAKE = 0x16
        private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
        private const val RANDOM_BYTES = 32

        private const val EXT_SUPPORTED_GROUPS = 0x000A
        private const val EXT_SIGNATURE_ALGORITHMS = 0x000D
        private const val EXT_SUPPORTED_VERSIONS = 0x002B

        /**
         * Parses [record], a complete TLS record whose payload is a ClientHello handshake message —
         * i.e. exactly what [TlsProbeServer] reads off the wire first, header included.
         */
        fun parse(record: ByteArray): ClientHello {
            val buf = ByteBuffer.wrap(record)

            require(u8(buf) == RECORD_TYPE_HANDSHAKE) { "not a TLS handshake record" }
            u16(buf) // record-layer legacy version, uninformative
            u16(buf) // record length
            require(u8(buf) == HANDSHAKE_TYPE_CLIENT_HELLO) { "not a ClientHello" }
            u24(buf) // handshake length

            val legacyVersion = u16(buf)
            skip(buf, RANDOM_BYTES)
            skip(buf, u8(buf)) // legacy_session_id
            val cipherSuites = u16List(buf, u16(buf))
            skip(buf, u8(buf)) // legacy_compression_methods

            val extensions = parseExtensions(buf)

            return ClientHello(
                legacyVersion = legacyVersion,
                offeredCipherSuites = cipherSuites,
                offeredVersions =
                    extensions[EXT_SUPPORTED_VERSIONS]?.toSet() ?: setOf(legacyVersion),
                offeredGroups = extensions[EXT_SUPPORTED_GROUPS].orEmpty(),
                offeredSignatureAlgorithms = extensions[EXT_SIGNATURE_ALGORITHMS].orEmpty().toSet(),
            )
        }

        private fun parseExtensions(buf: ByteBuffer): Map<Int, List<Int>> {
            if (!buf.hasRemaining()) return emptyMap()

            val totalLength = u16(buf)
            val end = buf.position() + totalLength
            val parsed = mutableMapOf<Int, List<Int>>()
            while (buf.position() < end) {
                val id = u16(buf)
                val length = u16(buf)
                val next = buf.position() + length
                when (id) {
                    // supported_versions is the odd one out: a one-byte length prefix, not two.
                    EXT_SUPPORTED_VERSIONS -> parsed[id] = u16List(buf, u8(buf))

                    EXT_SUPPORTED_GROUPS, EXT_SIGNATURE_ALGORITHMS -> parsed[id] = u16List(buf, u16(buf))
                }
                buf.position(next)
            }
            return parsed
        }

        private fun u16List(
            buf: ByteBuffer,
            byteLength: Int,
        ): List<Int> = (0 until byteLength / 2).map { u16(buf) }

        private fun u8(buf: ByteBuffer): Int = buf.get().toInt() and 0xFF

        private fun u16(buf: ByteBuffer): Int = (u8(buf) shl 8) or u8(buf)

        private fun u24(buf: ByteBuffer): Int = (u16(buf) shl 8) or u8(buf)

        private fun skip(
            buf: ByteBuffer,
            count: Int,
        ) {
            buf.position(buf.position() + count)
        }
    }
}
