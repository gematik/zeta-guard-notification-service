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

import de.gematik.zeta.notificationservice.domain.error.InvalidUserIdException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class UserIdTest :
    DescribeSpec({
        describe("parse") {
            it("classifies a KVNR (one uppercase letter + 9 digits)") {
                val userId = UserId.parse("X110411675")

                userId.shouldBeInstanceOf<Kvnr>()
                userId.value shouldBe "X110411675"
            }

            it("classifies a dot/hyphen-structured value as a Telematik-ID") {
                val userId = UserId.parse("1-2.3_4")

                userId.shouldBeInstanceOf<TelematikId>()
                userId.value shouldBe "1-2.3_4"
            }

            it("prefers KVNR over the broader Telematik-ID pattern when both match") {
                // "A123456789" matches both regexes; the narrower KVNR must win.
                UserId.parse("A123456789").shouldBeInstanceOf<Kvnr>()
            }

            it("rejects an identifier that matches neither pattern") {
                shouldThrow<InvalidUserIdException> { UserId.parse("!!bad!!") }
            }

            it("rejects a blank identifier") {
                shouldThrow<InvalidUserIdException> { UserId.parse("") }
            }

            it("falls back to Telematik-ID when the KVNR pattern is one digit short") {
                // "A12345678" is a letter + 8 digits: too short for KVNR, but a valid Telematik-ID.
                UserId.parse("A12345678").shouldBeInstanceOf<TelematikId>()
            }

            it("falls back to Telematik-ID when the leading letter is lowercase") {
                // KVNR requires an uppercase A-Z lead, so a lowercase lead resolves to Telematik-ID.
                UserId.parse("a123456789").shouldBeInstanceOf<TelematikId>()
            }

            it("rejects an identifier that starts with a special character") {
                // Telematik-ID allows '.', '_' and '-' in the body, but the first character must be alphanumeric.
                shouldThrow<InvalidUserIdException> { UserId.parse("_abc") }
            }

            it("accepts a Telematik-ID at the maximum length of 128 characters") {
                UserId.parse("A".repeat(128)).shouldBeInstanceOf<TelematikId>()
            }

            it("rejects an identifier longer than 128 characters") {
                shouldThrow<InvalidUserIdException> { UserId.parse("A".repeat(129)) }
            }
        }
    })
