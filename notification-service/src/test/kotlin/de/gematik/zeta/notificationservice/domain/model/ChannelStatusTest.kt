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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ChannelStatusTest :
    DescribeSpec({
        describe("isActive") {
            it("is true only for ENABLED, so not_set behaves exactly like disabled") {
                ChannelStatus.ENABLED.isActive() shouldBe true
                ChannelStatus.DISABLED.isActive() shouldBe false
                ChannelStatus.NOT_SET.isActive() shouldBe false
            }
        }

        describe("value") {
            it("carries the wire tokens of the gematik push notification concept") {
                ChannelStatus.ENABLED.value shouldBe "enabled"
                ChannelStatus.DISABLED.value shouldBe "disabled"
                ChannelStatus.NOT_SET.value shouldBe "not_set"
            }
        }

        describe("from") {
            it("round-trips every status token") {
                ChannelStatus.entries.forEach { ChannelStatus.from(it.value) shouldBe it }
            }

            it("returns null for an unrecognised token instead of throwing") {
                ChannelStatus.from("paused").shouldBeNull()
                ChannelStatus.from("ENABLED").shouldBeNull()
            }
        }
    })
