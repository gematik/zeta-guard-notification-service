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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.YearMonth
import kotlin.time.Instant

class PusherKeyMaterialTest :
    DescribeSpec({
        val iss = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
        val keyIdentifier = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"
        val octoberSecret = "185fed66ea5cabbe00147bbd298b5dab0ed41b57ab254d35897b3a4504306e3b"
        val octoberKey = "3b4adcd58dea98db8e9cb0f5763fcd04fe932d67926cc04b20ba2a2f304ffff9"
        val novemberSecret = "0c8662d90b04818afb317406fe7fcfcf8d103cd9bc6ad7847890d28620e85ec3"
        val novemberKey = "39aa5dacd538f53f4b956d84c9b8f2e26933274d160b9fd1a263a27681c6331b"

        fun encryption(timeIssCreated: String = "2023-10"): PusherEncryption =
            PusherEncryption.create(
                method = "aes-hmac-sha256",
                timeIssCreated = timeIssCreated,
                iss = iss,
                keyIdentifier = keyIdentifier,
            )

        fun material(
            month: String = "2023-10",
            sharedSecret: String = octoberSecret,
            aesKey: String = octoberKey,
        ): PusherKeyMaterial =
            PusherKeyMaterial(
                keyIdentifier = keyIdentifier,
                month = YearMonth.parse(month),
                sharedSecret = sharedSecret.hexToByteArray(),
                aesKey = aesKey.hexToByteArray(),
            )

        describe("initial") {
            it("derives the material of time_iss_created from the ISS") {
                val initial = PusherKeyMaterial.initial(encryption())

                initial.keyIdentifier shouldBe keyIdentifier
                initial.month shouldBe YearMonth.parse("2023-10")
                initial.sharedSecret.toHexString() shouldBe octoberSecret
                initial.aesKey.toHexString() shouldBe octoberKey
            }
        }

        describe("ratchetTo") {
            it("derives one step forward for the following month") {
                val ratcheted = material().ratchetTo(YearMonth.parse("2023-11"))

                ratcheted.month shouldBe YearMonth.parse("2023-11")
                ratcheted.sharedSecret.toHexString() shouldBe novemberSecret
                ratcheted.aesKey.toHexString() shouldBe novemberKey
                ratcheted.keyIdentifier shouldBe keyIdentifier
            }

            it("derives step by step for a month further ahead") {
                val twoSteps = material().ratchetTo(YearMonth.parse("2023-12"))
                val oneByOne = material().ratchetTo(YearMonth.parse("2023-11")).ratchetTo(YearMonth.parse("2023-12"))

                twoSteps.month shouldBe YearMonth.parse("2023-12")
                twoSteps shouldBe oneByOne
                twoSteps.sharedSecret.toHexString() shouldNotBe novemberSecret
            }

            it("returns the same instance for the current month") {
                val current = material()

                current.ratchetTo(YearMonth.parse("2023-10")) shouldBeSameInstanceAs current
            }

            it("never derives backwards") {
                val current = material()

                current.ratchetTo(YearMonth.parse("2023-09")) shouldBeSameInstanceAs current
            }
        }

        describe("constructor") {
            it("rejects a shared secret that is not 32 bytes") {
                shouldThrow<IllegalArgumentException> {
                    PusherKeyMaterial(keyIdentifier, YearMonth.parse("2023-10"), ByteArray(31), ByteArray(32))
                }
            }

            it("rejects an AES key that is not 32 bytes") {
                shouldThrow<IllegalArgumentException> {
                    PusherKeyMaterial(keyIdentifier, YearMonth.parse("2023-10"), ByteArray(32), ByteArray(33))
                }
            }
        }

        describe("equality") {
            it("compares the key bytes by content") {
                material() shouldBe material()
                material().hashCode() shouldBe material().hashCode()
            }

            it("differs when the AES key differs") {
                material() shouldNotBe material(aesKey = novemberKey)
            }

            it("differs when the month differs") {
                material() shouldNotBe material(month = "2023-11")
            }
        }

        describe("toString") {
            it("does not expose the key bytes") {
                val text = material().toString()

                text shouldNotContain octoberSecret
                text shouldNotContain octoberKey
                text shouldNotContain octoberSecret.hexToByteArray().contentToString()
                text shouldNotContain octoberKey.hexToByteArray().contentToString()
            }
        }

        describe("monthOf") {
            it("takes the month in UTC, not in the system time zone") {
                PusherKeyMaterial.monthOf(Instant.parse("2026-01-31T23:30:00Z")) shouldBe YearMonth.parse("2026-01")
            }

            it("switches months exactly at midnight UTC") {
                PusherKeyMaterial.monthOf(Instant.parse("2026-02-01T00:00:00Z")) shouldBe YearMonth.parse("2026-02")
            }
        }
    })
