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

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.YearMonth

class PushKeyDerivationTest :
    DescribeSpec({
        val iss = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2".hexToByteArray()
        val october = YearMonth.parse("2023-10")
        val november = YearMonth.parse("2023-11")

        describe("derive") {
            it("derives the shared secret and the AES key of the initial month from the ISS") {
                val keys = PushKeyDerivation.derive(iss, october)

                keys.sharedSecret.toHexString() shouldBe
                    "185fed66ea5cabbe00147bbd298b5dab0ed41b57ab254d35897b3a4504306e3b"
                keys.aesKey.toHexString() shouldBe
                    "3b4adcd58dea98db8e9cb0f5763fcd04fe932d67926cc04b20ba2a2f304ffff9"
            }

            it("derives the following month from the previous month's shared secret") {
                val octoberKeys = PushKeyDerivation.derive(iss, october)

                val novemberKeys = PushKeyDerivation.derive(octoberKeys.sharedSecret, november)

                novemberKeys.sharedSecret.toHexString() shouldBe
                    "0c8662d90b04818afb317406fe7fcfcf8d103cd9bc6ad7847890d28620e85ec3"
                novemberKeys.aesKey.toHexString() shouldBe
                    "39aa5dacd538f53f4b956d84c9b8f2e26933274d160b9fd1a263a27681c6331b"
            }

            it("returns two keys of 32 bytes each") {
                val keys = PushKeyDerivation.derive(iss, october)

                keys.sharedSecret.size shouldBe PushKeyDerivation.KEY_LENGTH
                keys.aesKey.size shouldBe PushKeyDerivation.KEY_LENGTH
                PushKeyDerivation.KEY_LENGTH shouldBe 32
            }

            it("derives different keys for different months") {
                val octoberKeys = PushKeyDerivation.derive(iss, october)
                val novemberKeys = PushKeyDerivation.derive(iss, november)

                octoberKeys.sharedSecret.toHexString() shouldNotBe novemberKeys.sharedSecret.toHexString()
                octoberKeys.aesKey.toHexString() shouldNotBe novemberKeys.aesKey.toHexString()
            }

            it("leaves the input secret untouched") {
                val before = iss.copyOf()

                PushKeyDerivation.derive(iss, october)

                iss.contentEquals(before).shouldBeTrue()
            }
        }
    })
