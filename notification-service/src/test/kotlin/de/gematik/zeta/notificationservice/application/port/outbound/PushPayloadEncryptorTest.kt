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
package de.gematik.zeta.notificationservice.application.port.outbound

import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.YearMonth
import java.util.Base64
import javax.crypto.AEADBadTagException

class PushPayloadEncryptorTest :
    DescribeSpec({
        val material = EncryptedPushTestSupport.material(month = "2026-08")
        val payload =
            buildJsonObject {
                put("message", JsonPrimitive("Neues Dokument"))
                put("document_id", JsonPrimitive("123"))
            }
        val encryptor = PushPayloadEncryptor()

        describe("encrypt") {
            it("produces 1404 characters of Base64 and names the month of the key") {
                val encrypted = encryptor.encrypt(payload, material)

                encrypted.cipherText.length shouldBe EncryptedPushTestSupport.CIPHERTEXT_LENGTH
                Base64.getDecoder().decode(encrypted.cipherText).size shouldBe 12 + 1024 + 16
                encrypted.timeMessageEncrypted shouldBe "2026-08"
            }

            it("uses a fresh IV for every message") {
                encryptor.encrypt(payload, material).cipherText shouldNotBe
                    encryptor.encrypt(payload, material).cipherText
            }

            it("round-trips through decryption and unpadding") {
                val encrypted = encryptor.encrypt(payload, material)

                EncryptedPushTestSupport.decryptToPadded(encrypted.cipherText, material.aesKey).size shouldBe 1024
                EncryptedPushTestSupport.decrypt(encrypted.cipherText, material.aesKey) shouldBe payload
            }

            it("cannot be opened with the key of another month") {
                val encrypted = encryptor.encrypt(payload, material)
                val nextMonth = material.ratchetTo(YearMonth.parse("2026-09"))

                shouldThrow<AEADBadTagException> {
                    EncryptedPushTestSupport.decryptToPadded(encrypted.cipherText, nextMonth.aesKey)
                }
            }

            it("rejects a payload above 1018 bytes before encrypting") {
                val oversized = buildJsonObject { put("message", JsonPrimitive("a".repeat(1005))) }

                shouldThrow<PayloadTooLargeException> { encryptor.encrypt(oversized, material) }
            }
        }
    })
