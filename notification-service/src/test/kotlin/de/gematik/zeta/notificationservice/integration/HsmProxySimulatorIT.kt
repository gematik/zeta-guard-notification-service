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
package de.gematik.zeta.notificationservice.integration

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.HsmProxyClient
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyPort
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithHsmProxySimulator
import de.gematik.zetaguard.hsmproxy.grpc.HsmProxyGrpcClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Round-trips the real client against the HSM proxy simulator. The client is built from the same
 * `hsm-proxy.endpoint` the application uses, so this also covers the transport wiring rather than
 * only the blob packing (which
 * [de.gematik.zeta.notificationservice.adapter.outbound.persistence.HsmProxyClientTest] pins down
 * against a mock). The service only unwraps; the wrap here is the `Encrypt` the keychain generator makes.
 */
@QuarkusTest
@WithHsmProxySimulator
@WithDatabase
class HsmProxySimulatorIT {
    private val random = SecureRandom()

    private fun randomKeys(): ByteArray = ByteArray(64).also(random::nextBytes)

    @Test
    fun wrapAndUnwrapRoundtrip() =
        withRealHsmProxyClient { hsmProxy, wrap ->
            val keys = randomKeys()
            val aad = "storage-id-1".toByteArray()

            val wrapped = wrap(KEK_ID, keys, aad)

            wrapped.size shouldBe 12 + 64 + 16
            hsmProxy.unwrapKeys(KEK_ID, wrapped, aad) shouldBe keys
        }

    @Test
    fun wrappingTheSameKeysTwiceYieldsDifferentBlobs() =
        withRealHsmProxyClient { hsmProxy, wrap ->
            val keys = randomKeys()
            val aad = "storage-id-2".toByteArray()

            val first = wrap(KEK_ID, keys, aad)
            val second = wrap(KEK_ID, keys, aad)

            first.contentEquals(second) shouldBe false
            hsmProxy.unwrapKeys(KEK_ID, first, aad) shouldBe keys
            hsmProxy.unwrapKeys(KEK_ID, second, aad) shouldBe keys
        }

    @Test
    fun unwrapWithWrongAadFails() =
        withRealHsmProxyClient { hsmProxy, wrap ->
            val wrapped = wrap(KEK_ID, randomKeys(), "storage-id-3".toByteArray())

            shouldThrow<HsmProxyException> {
                hsmProxy.unwrapKeys(KEK_ID, wrapped, "storage-id-other".toByteArray())
            }
        }

    @Test
    fun unwrapTamperedBlobFails() =
        withRealHsmProxyClient { hsmProxy, wrap ->
            val aad = "storage-id-4".toByteArray()
            val wrapped = wrap(KEK_ID, randomKeys(), aad)
            val tampered = wrapped.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }

            shouldThrow<HsmProxyException> {
                hsmProxy.unwrapKeys(KEK_ID, tampered, aad)
            }
        }

    @Test
    fun aBlobSealedUnderOneKekDoesNotOpenUnderAnother() =
        withRealHsmProxyClient { hsmProxy, wrap ->
            val aad = "storage-id-5".toByteArray()
            val wrapped = wrap(OTHER_KEK_ID, randomKeys(), aad)

            shouldThrow<HsmProxyException> {
                hsmProxy.unwrapKeys(KEK_ID, wrapped, aad)
            }
        }

    private fun withRealHsmProxyClient(block: (HsmProxyPort, (String, ByteArray, ByteArray) -> ByteArray) -> Unit) {
        val endpoint = ConfigProvider.getConfig().getValue("hsm-proxy.endpoint", String::class.java)
        val grpcClient = HsmProxyGrpcClient(endpoint)
        val client = HsmProxyClient(grpcClient)
        try {
            block(client) { kekId, keys, aad ->
                val response = grpcClient.encrypt(kekId, keys, aad)
                response.iv.toByteArray() + response.ciphertext.toByteArray() + response.tag.toByteArray()
            }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val KEK_ID = "notification-service-keychain-kek-aesgcm-v1"
        const val OTHER_KEK_ID = "notification-service-keychain-kek-aesgcm-v2"
    }
}
