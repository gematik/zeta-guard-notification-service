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
package de.gematik.zeta.notificationservice.application.repository.encryption

import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V1
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PayloadSealerWithDekTest :
    DescribeSpec({
        fun sealer(): PayloadSealerWithDek = PayloadSealerWithDek(TestKeychain.keychain())

        describe("seal") {
            it("stores salt, iv and ciphertext under the dek of the pseudonym's entry") {
                val payload = """{"secret":"payload"}"""

                val record = sealer().seal("storage-1", V2, payload)

                record.id shouldBe "storage-1"
                record.payload shouldNotContain "secret"
                Base64.getDecoder().decode(record.payload).size shouldBe 32 + 12 + payload.length + 16
                record.keychainId shouldBe V2
            }

            it("seals under the pseudonym's entry, not the one taking the writes") {
                sealer().seal("storage-2", V1, "payload").keychainId shouldBe V1
            }

            it("refuses an entry the keychain does not hold") {
                shouldThrow<IllegalStateException> {
                    sealer().seal("storage-11", "notification-service-keychain-v3", "payload")
                }
            }

            it("draws a fresh salt and iv for every seal") {
                val sealer = sealer()

                val first = Base64.getDecoder().decode(sealer.seal("storage-3", V1, "same").payload)
                val second = Base64.getDecoder().decode(sealer.seal("storage-3", V1, "same").payload)

                first.copyOfRange(0, 32) shouldNotBe second.copyOfRange(0, 32)
                first.copyOfRange(32, 44) shouldNotBe second.copyOfRange(32, 44)
            }

            it("encrypts under HKDF-Expand(dek, label || salt), never under the dek itself") {
                val record = sealer().seal("storage-4", V2, "payload")
                val sealed = Base64.getDecoder().decode(record.payload)
                val recordKey = ByteArray(32)
                HKDFBytesGenerator(SHA256Digest()).apply {
                    init(
                        HKDFParameters.skipExtractParameters(
                            TestKeychain.dekOf(V2),
                            "notification-service record key".encodeToByteArray() + sealed.copyOfRange(0, 32),
                        ),
                    )
                    generateBytes(recordKey, 0, recordKey.size)
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recordKey, "AES"), GCMParameterSpec(128, sealed, 32, 12))
                cipher.updateAAD("storage-4".encodeToByteArray())

                cipher.doFinal(sealed, 44, sealed.size - 44).decodeToString() shouldBe "payload"
            }
        }

        describe("open") {
            it("decrypts what seal produced") {
                val sealer = sealer()
                val payload = """{"userId":"X110411675","pushers":[]}"""

                sealer.open(sealer.seal("storage-5", V1, payload)) shouldBe payload
            }

            it("opens a record under each entry of the keychain") {
                val older = sealer().seal("storage-6", V1, "older")
                val newer = sealer().seal("storage-6", V2, "newer")

                sealer().open(older) shouldBe "older"
                sealer().open(newer) shouldBe "newer"
            }

            it("treats a truncated or non-base64 payload as a broken record, not as a bad request") {
                val sealer = sealer()
                val record = sealer.seal("storage-10", V1, "payload")
                val truncated = Base64.getEncoder().encodeToString(ByteArray(60))

                shouldThrow<IllegalStateException> { sealer.open(record.copy(payload = truncated)) }
                shouldThrow<IllegalStateException> { sealer.open(record.copy(payload = "%%%")) }
            }

            it("rejects a sealed payload moved to another record id") {
                val sealer = sealer()
                val record = sealer.seal("storage-7", V1, "payload")

                shouldThrow<AEADBadTagException> {
                    sealer.open(record.copy(id = "storage-8"))
                }
            }

            it("rejects a record under a dek the keychain does not list") {
                val sealer = sealer()
                val record =
                    sealer
                        .seal(
                            "storage-9",
                            V2,
                            "payload",
                        ).copy(keychainId = "notification-service-keychain-v0")

                shouldThrow<IllegalStateException> { sealer.open(record) }
            }
        }
    })
