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
import io.kotest.matchers.shouldBe

class ChannelStatusAggregationTest :
    DescribeSpec({
        val channel = ChannelId.parse("epa.documents.new")
        val other = ChannelId.parse("epa.consent.changed")

        fun device(vararg statuses: Pair<ChannelId, ChannelStatus>) = DeviceChannelConfig.of(statuses.toMap())

        describe("statusOf") {
            it("is ENABLED as soon as one device has the channel enabled") {
                val configs =
                    listOf(
                        device(channel to ChannelStatus.DISABLED),
                        device(channel to ChannelStatus.ENABLED),
                    )

                ChannelStatusAggregation.statusOf(configs, channel) shouldBe ChannelStatus.ENABLED
            }

            it("is ENABLED regardless of device order") {
                val enabled = device(channel to ChannelStatus.ENABLED)
                val disabled = device(channel to ChannelStatus.DISABLED)

                ChannelStatusAggregation.statusOf(listOf(enabled, disabled), channel) shouldBe ChannelStatus.ENABLED
                ChannelStatusAggregation.statusOf(listOf(disabled, enabled), channel) shouldBe ChannelStatus.ENABLED
            }

            it("is ENABLED when the other device only has it not_set") {
                val configs =
                    listOf(
                        device(channel to ChannelStatus.NOT_SET),
                        device(channel to ChannelStatus.ENABLED),
                    )

                ChannelStatusAggregation.statusOf(configs, channel) shouldBe ChannelStatus.ENABLED
            }

            it("treats not_set identically to disabled: one device not_set, one disabled is not enabled") {
                val configs =
                    listOf(
                        device(channel to ChannelStatus.NOT_SET),
                        device(channel to ChannelStatus.DISABLED),
                    )

                val status = ChannelStatusAggregation.statusOf(configs, channel)

                status shouldBe ChannelStatus.DISABLED
                status.isActive() shouldBe false
            }

            it("is not active when a registered channel is enabled on no device") {
                val configs = listOf(device(other to ChannelStatus.ENABLED), DeviceChannelConfig.EMPTY)

                val status = ChannelStatusAggregation.statusOf(configs, channel)

                status shouldBe ChannelStatus.NOT_SET
                status.isActive() shouldBe false
            }

            it("is NOT_SET when only explicit not_set choices exist") {
                val configs = listOf(device(channel to ChannelStatus.NOT_SET))

                ChannelStatusAggregation.statusOf(configs, channel) shouldBe ChannelStatus.NOT_SET
            }

            it("is NOT_SET for a user without any device configuration") {
                ChannelStatusAggregation.statusOf(emptyList(), channel) shouldBe ChannelStatus.NOT_SET
            }
        }

        describe("statusesOf") {
            it("matches statusOf per channel, so there is only one aggregation rule") {
                val configs =
                    listOf(
                        device(channel to ChannelStatus.ENABLED, other to ChannelStatus.NOT_SET),
                        device(channel to ChannelStatus.DISABLED, other to ChannelStatus.DISABLED),
                    )
                val channels = listOf(channel, other, ChannelId.parse("never.configured"))

                val statuses = ChannelStatusAggregation.statusesOf(configs, channels)

                statuses shouldBe channels.associateWith { ChannelStatusAggregation.statusOf(configs, it) }
            }

            it("preserves the order of the requested channels") {
                val channels = listOf(other, channel)

                ChannelStatusAggregation
                    .statusesOf(emptyList(), channels)
                    .keys
                    .toList() shouldBe channels
            }
        }
    })
