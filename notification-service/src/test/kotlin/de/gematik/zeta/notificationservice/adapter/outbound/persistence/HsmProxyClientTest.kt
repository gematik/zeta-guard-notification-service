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
package de.gematik.zeta.notificationservice.adapter.outbound.persistence

import com.google.protobuf.ByteString
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zetaguard.hsmproxy.grpc.HsmProxyGrpcClient
import de.gematik.zetaguard.hsmproxy.v1.DecryptResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class HsmProxyClientTest :
    DescribeSpec({
        val kekId = "notification-service-keychain-kek-aesgcm-v1"
        val aad = "storage-id".toByteArray()

        describe("unwrapKeys") {
            it("splits the blob into iv, ciphertext and tag and passes the stored kek id") {
                val storedKekId = "notification-service-keychain-kek-aesgcm-v0"
                val ciphertext = slot<ByteArray>()
                val iv = slot<ByteArray>()
                val tag = slot<ByteArray>()
                val associatedData = slot<ByteArray>()
                val grpcClient = mockk<HsmProxyGrpcClient>()
                every {
                    grpcClient.decrypt(
                        storedKekId,
                        capture(ciphertext),
                        capture(iv),
                        capture(tag),
                        capture(associatedData),
                    )
                } returns
                    DecryptResponse
                        .newBuilder()
                        .setPlaintext(ByteString.copyFrom(ByteArray(32) { 9 }))
                        .build()
                val blob = ByteArray(12) { 1 } + ByteArray(32) { 2 } + ByteArray(16) { 3 }

                val plaintext = HsmProxyClient(grpcClient).unwrapKeys(storedKekId, blob, aad)

                plaintext shouldBe ByteArray(32) { 9 }
                iv.captured shouldBe ByteArray(12) { 1 }
                ciphertext.captured shouldBe ByteArray(32) { 2 }
                tag.captured shouldBe ByteArray(16) { 3 }
                associatedData.captured shouldBe aad
            }

            it("rejects blobs that cannot contain iv, ciphertext and tag") {
                shouldThrow<IllegalStateException> {
                    HsmProxyClient(mockk<HsmProxyGrpcClient>()).unwrapKeys(kekId, ByteArray(28), aad)
                }
            }

            it("maps grpc failures to HsmProxyException") {
                val grpcClient = mockk<HsmProxyGrpcClient>()
                every { grpcClient.decrypt(kekId, any(), any(), any(), any()) } throws
                    StatusRuntimeException(Status.UNAVAILABLE)

                shouldThrow<HsmProxyException> {
                    HsmProxyClient(grpcClient).unwrapKeys(kekId, ByteArray(12 + 64 + 16), aad)
                }
            }
        }
    })
