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

import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class DeviceChannelConfigTest :
    DescribeSpec({
        val first = ChannelId.parse("channel1")
        val second = ChannelId.parse("channel2")

        describe("statusOf") {
            it("reports NOT_SET for a channel the device never configured") {
                DeviceChannelConfig.EMPTY.statusOf(first) shouldBe ChannelStatus.NOT_SET
            }

            it("reports the stored status") {
                val config = DeviceChannelConfig.of(mapOf(first to ChannelStatus.ENABLED))

                config.statusOf(first) shouldBe ChannelStatus.ENABLED
            }
        }

        describe("merge") {
            it("leaves channels absent from the change set unchanged") {
                val config =
                    DeviceChannelConfig.of(
                        mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.DISABLED),
                    )

                val result = config.merge(mapOf(first to ChannelStatus.DISABLED))

                result.statusOf(first) shouldBe ChannelStatus.DISABLED
                result.statusOf(second) shouldBe ChannelStatus.DISABLED
            }

            it("adds channels that were not configured before") {
                val result = DeviceChannelConfig.EMPTY.merge(mapOf(second to ChannelStatus.ENABLED))

                result.statusOf(second) shouldBe ChannelStatus.ENABLED
            }

            it("returns the same instance for an empty change set") {
                val config = DeviceChannelConfig.of(mapOf(first to ChannelStatus.ENABLED))

                config.merge(emptyMap()) shouldBeSameInstanceAs config
            }

            it("returns the same instance when every requested status is already in effect") {
                val config =
                    DeviceChannelConfig.of(
                        mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.DISABLED),
                    )

                config.merge(
                    mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.DISABLED),
                ) shouldBeSameInstanceAs config
            }

            it("returns the same instance when not_set is requested for a channel that was never set") {
                val config = DeviceChannelConfig.of(mapOf(first to ChannelStatus.ENABLED))

                config.merge(mapOf(second to ChannelStatus.NOT_SET)) shouldBeSameInstanceAs config
            }

            it("drops a channel that is set back to not_set") {
                val config = DeviceChannelConfig.of(mapOf(first to ChannelStatus.ENABLED))

                val result = config.merge(mapOf(first to ChannelStatus.NOT_SET))

                result.statusOf(first) shouldBe ChannelStatus.NOT_SET
                result.statuses shouldNotContainKey first
                result.isEmpty() shouldBe true
            }
        }

        describe("of") {
            it("drops not_set entries, because an absent channel already means not_set") {
                val config =
                    DeviceChannelConfig.of(
                        mapOf(first to ChannelStatus.NOT_SET, second to ChannelStatus.ENABLED),
                    )

                config.statuses shouldBe mapOf(second to ChannelStatus.ENABLED)
                config.statusOf(first) shouldBe ChannelStatus.NOT_SET
            }
        }

        describe("changesOf") {
            it("collapses entries into a change set") {
                val changes =
                    DeviceChannelConfig.changesOf(
                        listOf(
                            ChannelConfiguration(first, ChannelStatus.ENABLED),
                            ChannelConfiguration(second, ChannelStatus.NOT_SET),
                        ),
                    )

                changes shouldBe mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.NOT_SET)
            }

            it("yields an empty change set for no entries") {
                DeviceChannelConfig.changesOf(emptyList()).shouldBeEmpty()
            }

            it("rejects a channel listed twice with contradicting statuses") {
                shouldThrow<InvalidChannelException> {
                    DeviceChannelConfig.changesOf(
                        listOf(
                            ChannelConfiguration(first, ChannelStatus.ENABLED),
                            ChannelConfiguration(first, ChannelStatus.DISABLED),
                        ),
                    )
                }
            }

            it("rejects a channel listed twice even with the same status") {
                shouldThrow<InvalidChannelException> {
                    DeviceChannelConfig.changesOf(
                        listOf(
                            ChannelConfiguration(first, ChannelStatus.ENABLED),
                            ChannelConfiguration(first, ChannelStatus.ENABLED),
                        ),
                    )
                }
            }
        }
    })
