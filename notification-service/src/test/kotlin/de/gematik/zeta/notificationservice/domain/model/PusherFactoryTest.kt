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
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class PusherFactoryTest :
    DescribeSpec({
        val factory = PusherFactory()
        val pusherId = PusherId(appId = "app.one", pushKey = "key-1")

        describe("create") {
            it("returns a RegisteredPusher for a valid http pusher") {
                val pusher =
                    factory.create(
                        pusherId = pusherId,
                        kind = "http",
                        appDisplayName = "App",
                        deviceDisplayName = "Device",
                        lang = "de",
                        data = PusherData(url = "https://push.example/gateway"),
                    )

                pusher.pusherId shouldBe pusherId
                pusher.kind shouldBe "http"
                pusher.appDisplayName shouldBe "App"
                pusher.data.url shouldBe "https://push.example/gateway"
            }

            it("rejects a registered pusher without app_display_name") {
                val thrown =
                    shouldThrow<MissingPusherFieldsException> {
                        factory.create(
                            pusherId = pusherId,
                            kind = "http",
                            appDisplayName = null,
                            deviceDisplayName = "Device",
                            lang = "de",
                            data = PusherData(url = "https://push.example/gateway"),
                        )
                    }

                thrown.fields shouldContainExactly listOf("app_display_name")
            }

            it("reports every missing field at once, in the order the contract lists them") {
                val thrown =
                    shouldThrow<MissingPusherFieldsException> {
                        factory.create(
                            pusherId = pusherId,
                            kind = "http",
                            appDisplayName = null,
                            deviceDisplayName = null,
                            lang = null,
                            data = null,
                        )
                    }

                thrown.fields shouldContainExactly
                    listOf("app_display_name", "device_display_name", "lang", "data")
                thrown.message shouldBe
                    "Missing parameters: app_display_name, device_display_name, lang, data"
            }

            it("treats a blank required field as missing") {
                val thrown =
                    shouldThrow<MissingPusherFieldsException> {
                        factory.create(
                            pusherId = pusherId,
                            kind = "http",
                            appDisplayName = "   ",
                            deviceDisplayName = "Device",
                            lang = "",
                            data = PusherData(url = "https://push.example/gateway"),
                        )
                    }

                thrown.fields shouldContainExactly listOf("app_display_name", "lang")
            }

            it("rejects an http pusher without data url") {
                shouldThrow<InvalidPusherException> {
                    factory.create(
                        pusherId = pusherId,
                        kind = "http",
                        appDisplayName = "App",
                        deviceDisplayName = "Device",
                        lang = "de",
                        data = PusherData(url = null),
                    )
                }
            }
        }
    })
