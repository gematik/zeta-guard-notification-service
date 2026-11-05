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
package de.gematik.zeta.notificationservice.domain.error

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.throwable.shouldHaveMessage
import java.io.PrintWriter
import java.io.StringWriter

class RedactedThrowableTest :
    DescribeSpec({
        val secret = "{\"message\":\"Neuer Befund\"}"

        fun stackTraceOf(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

        describe("redacted") {
            it("drops the messages of the whole cause chain and of suppressed exceptions") {
                val original =
                    IllegalStateException("outer $secret", IllegalArgumentException("inner $secret")).apply {
                        addSuppressed(RuntimeException("suppressed $secret"))
                    }

                val printed = stackTraceOf(original.redacted())

                printed shouldNotContain secret
                printed shouldContain IllegalStateException::class.java.name
                printed shouldContain IllegalArgumentException::class.java.name
                printed shouldContain RuntimeException::class.java.name
            }

            it("keeps the original stack trace") {
                val original = IllegalStateException(secret)

                original.redacted().stackTrace.toList() shouldContainExactly original.stackTrace.toList()
            }

            it("mirrors the shape of the cause chain") {
                val redacted = RuntimeException("a", IllegalStateException("b")).redacted()

                val cause = redacted.cause.shouldNotBeNull()
                cause shouldHaveMessage "${IllegalStateException::class.java.name}: [message redacted]"
                cause.cause.shouldBeNull()
                redacted.suppressed shouldHaveSize 0
            }

            it("terminates on a cyclic cause chain") {
                val first = RuntimeException("first $secret")
                val second = IllegalStateException("second $secret", first)
                first.initCause(second)

                stackTraceOf(first.redacted()) shouldNotContain secret
            }

            it("does not wrap twice") {
                val redacted = RuntimeException(secret).redacted()

                redacted.redacted() shouldBe redacted
            }
        }
    })
