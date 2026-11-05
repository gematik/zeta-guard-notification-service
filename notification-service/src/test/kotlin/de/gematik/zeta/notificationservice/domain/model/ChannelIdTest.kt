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
import io.kotest.matchers.shouldBe

class ChannelIdTest :
    DescribeSpec({
        describe("parse") {
            it("accepts dotted, hyphenated and underscored identifiers") {
                ChannelId.parse("epa.documents.new").value shouldBe "epa.documents.new"
                ChannelId.parse("channel1").value shouldBe "channel1"
                ChannelId.parse("a").value shouldBe "a"
                ChannelId.parse("A0._-").value shouldBe "A0._-"
            }

            it("accepts the maximum length of 128 characters") {
                val maxLength = "a" + "b".repeat(127)

                ChannelId.parse(maxLength).value shouldBe maxLength
            }

            it("rejects an empty identifier") {
                shouldThrow<InvalidChannelException> { ChannelId.parse("") }
            }

            it("rejects more than 128 characters") {
                shouldThrow<InvalidChannelException> { ChannelId.parse("a" + "b".repeat(128)) }
            }

            it("rejects a leading separator") {
                shouldThrow<InvalidChannelException> { ChannelId.parse(".leading") }
                shouldThrow<InvalidChannelException> { ChannelId.parse("-leading") }
                shouldThrow<InvalidChannelException> { ChannelId.parse("_leading") }
            }

            it("rejects whitespace and slashes") {
                shouldThrow<InvalidChannelException> { ChannelId.parse("has space") }
                shouldThrow<InvalidChannelException> { ChannelId.parse("has/slash") }
            }

            it("rejects a comma, which would silently split the configured registry list") {
                shouldThrow<InvalidChannelException> { ChannelId.parse("first,second") }
            }
        }

        describe("reconstitute") {
            it("accepts a stored identifier that parse would reject") {
                ChannelId.reconstitute("no longer allowed").value shouldBe "no longer allowed"
            }
        }
    })
