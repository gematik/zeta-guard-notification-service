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
package de.gematik.zeta.notificationservice.domain.model

import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.YearMonth

class PusherEncryptionTest :
    DescribeSpec({
        val iss = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
        val keyIdentifier = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"

        fun create(
            method: String? = "aes-hmac-sha256",
            timeIssCreated: String? = "2023-10",
            issValue: String? = iss,
            keyId: String? = keyIdentifier,
        ): PusherEncryption =
            PusherEncryption.create(
                method = method,
                timeIssCreated = timeIssCreated,
                iss = issValue,
                keyIdentifier = keyId,
            )

        fun rejected(block: () -> PusherEncryption): String =
            shouldThrow<InvalidPusherException> { block() }.message.shouldNotBeNull()

        describe("create") {
            it("accepts a well-formed block and exposes month and ISS bytes") {
                val encryption = create()

                encryption.month() shouldBe YearMonth.parse("2023-10")
                encryption.issBytes().toHexString() shouldBe iss
                encryption.issBytes().size shouldBe 32
                encryption.keyIdentifier shouldBe keyIdentifier
            }

            it("accepts an upper-case hexadecimal ISS") {
                create(issValue = iss.uppercase()).issBytes().toHexString() shouldBe iss
            }

            it("reports a missing field by name") {
                rejected { create(method = null) } shouldContain "method"
                rejected { create(timeIssCreated = null) } shouldContain "time_iss_created"
                rejected { create(issValue = null) } shouldContain "iss"
                rejected { create(keyId = null) } shouldContain "key_identifier"
            }

            it("rejects a method other than aes-hmac-sha256") {
                rejected { create(method = "aes-gcm") } shouldContain "method"
            }

            it("rejects time_iss_created that is not yyyy-MM") {
                listOf("2026-9", "2026-13", "2026-00", "September 2026", "2026-09-01", "").forEach { raw ->
                    rejected { create(timeIssCreated = raw) } shouldContain "time_iss_created"
                }
                shouldNotThrowAny { create(timeIssCreated = "2026-09") }
            }

            it("rejects an ISS that is not 64 hexadecimal characters, without echoing it") {
                listOf(iss.dropLast(1), iss + "0", "g" + iss.drop(1)).forEach { bad ->
                    val message = rejected { create(issValue = bad) }

                    message shouldContain "iss"
                    message shouldNotContain bad
                }
                rejected { create(issValue = "") } shouldContain "iss"
            }

            it("rejects a blank or overlong key_identifier") {
                rejected { create(keyId = " ") } shouldContain "key_identifier"
                rejected { create(keyId = "k".repeat(129)) } shouldContain "key_identifier"
                shouldNotThrowAny { create(keyId = "k".repeat(128)) }
            }
        }

        describe("toString") {
            it("does not expose the ISS") {
                create().toString() shouldNotContain iss
            }
        }
    })
