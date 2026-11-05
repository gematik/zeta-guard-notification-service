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

import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException
import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ChannelRegistryTest :
    DescribeSpec({
        val registered = ChannelId.parse("epa.documents.new")
        val unregistered = ChannelId.parse("epa.unknown")

        fun registry() = ChannelRegistry.of(listOf("epa.documents.new", "epa.consent.changed"))

        describe("contains") {
            it("is true for a configured channel and false otherwise") {
                registry().contains(registered) shouldBe true
                registry().contains(unregistered) shouldBe false
            }
        }

        describe("all") {
            it("preserves the configured order") {
                registry().all() shouldContainExactly
                    listOf(ChannelId.parse("epa.documents.new"), ChannelId.parse("epa.consent.changed"))
            }

            it("collapses duplicates in the configuration") {
                ChannelRegistry
                    .of(listOf("channel1", "channel1"))
                    .all() shouldContainExactly listOf(ChannelId.parse("channel1"))
            }
        }

        describe("require") {
            it("returns the channel when it is configured") {
                registry().require(registered) shouldBe registered
            }

            it("rejects a channel that is not configured on this instance") {
                shouldThrow<ChannelNotAllowedException> { registry().require(unregistered) }
            }
        }

        describe("of") {
            it("rejects a malformed configured channel id, so the instance fails to boot") {
                shouldThrow<InvalidChannelException> { ChannelRegistry.of(listOf("not valid!")) }
            }

            it("rejects an empty configuration, so the instance fails to boot") {
                shouldThrow<IllegalArgumentException> { ChannelRegistry.of(emptyList()) }
            }

            it("trims entries, so a comma-separated env var with spaces still boots") {
                // NOTIFICATION_CHANNELS_ALLOWED=channel1, channel2 reaches us with the space intact.
                ChannelRegistry
                    .of(listOf("channel1", " channel2"))
                    .all() shouldContainExactly listOf(ChannelId.parse("channel1"), ChannelId.parse("channel2"))
            }

            it("ignores blank entries, so a trailing comma is not fatal") {
                ChannelRegistry
                    .of(listOf("channel1", "", "  "))
                    .all() shouldContainExactly listOf(ChannelId.parse("channel1"))
            }

            it("rejects a configuration of nothing but blanks, so the instance fails to boot") {
                shouldThrow<IllegalArgumentException> { ChannelRegistry.of(listOf("", "   ")) }
            }
        }
    })
