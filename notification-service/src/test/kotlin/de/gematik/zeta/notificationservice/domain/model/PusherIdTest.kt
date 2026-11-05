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
import io.kotest.matchers.shouldBe

class PusherIdTest :
    DescribeSpec({
        describe("construction") {
            it("accepts a populated identifier") {
                shouldNotThrowAny {
                    PusherId(appId = "de.gematik.zeta.test", pushKey = "tok-1")
                }
            }

            it("rejects a blank pushkey") {
                val thrown =
                    shouldThrow<InvalidPusherException> {
                        PusherId(appId = "de.gematik.zeta.test", pushKey = "   ")
                    }

                thrown.message shouldBe "Blank parameters: pushkey"
            }

            it("rejects a blank app_id") {
                val thrown =
                    shouldThrow<InvalidPusherException> {
                        PusherId(appId = "", pushKey = "tok-1")
                    }

                thrown.message shouldBe "Blank parameters: app_id"
            }

            it("names both parts when both are blank, in the order the contract lists them") {
                val thrown =
                    shouldThrow<InvalidPusherException> {
                        PusherId(appId = "", pushKey = "  ")
                    }

                thrown.message shouldBe "Blank parameters: pushkey, app_id"
            }
        }
    })
