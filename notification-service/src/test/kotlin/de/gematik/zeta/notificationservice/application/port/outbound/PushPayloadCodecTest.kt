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
package de.gematik.zeta.notificationservice.application.port.outbound

import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer

class PushPayloadCodecTest :
    DescribeSpec({
        fun fillCountOf(padded: ByteArray): Int = ByteBuffer.wrap(padded, 4, 2).short.toInt() and 0xFFFF

        fun payloadOfSize(bytes: Int): JsonObject {
            val json = buildJsonObject { put("message", JsonPrimitive("a".repeat(bytes - 14))) }
            PushPayloadCodec.serialize(json).size shouldBe bytes
            return json
        }

        describe("serialize") {
            it("produces compact UTF-8 JSON") {
                val bytes = PushPayloadCodec.serialize(buildJsonObject { put("message", JsonPrimitive("Hallo Wörld")) })

                bytes.decodeToString() shouldBe """{"message":"Hallo Wörld"}"""
            }
        }

        describe("pad") {
            it("pads an empty message to 1024 bytes and writes the fill count into the header") {
                val padded = PushPayloadCodec.pad(ByteArray(0))

                padded.size shouldBe PushPayloadCodec.PADDED_LENGTH
                padded.copyOfRange(0, 4).decodeToString() shouldBe "PNM1"
                fillCountOf(padded) shouldBe PushPayloadCodec.MAX_MESSAGE_BYTES
                padded.copyOfRange(6, 1024).all { it == 0x20.toByte() }.shouldBeTrue()
            }

            it("pads a message of 1018 bytes without any fill bytes") {
                val message = ByteArray(PushPayloadCodec.MAX_MESSAGE_BYTES) { 'a'.code.toByte() }

                val padded = PushPayloadCodec.pad(message)

                padded.size shouldBe PushPayloadCodec.PADDED_LENGTH
                fillCountOf(padded) shouldBe 0
                padded.copyOfRange(6, 1024).contentEquals(message).shouldBeTrue()
            }

            it("keeps the message at the end of the block") {
                val padded = PushPayloadCodec.pad("abc".encodeToByteArray())

                fillCountOf(padded) shouldBe 1015
                padded.copyOfRange(1021, 1024).decodeToString() shouldBe "abc"
            }

            it("rejects a message above 1018 bytes") {
                shouldThrow<PayloadTooLargeException> { PushPayloadCodec.pad(ByteArray(1019)) }
            }
        }

        describe("requireEncryptable") {
            it("accepts a payload that serializes to 1018 bytes") {
                shouldNotThrowAny { PushPayloadCodec.requireEncryptable(payloadOfSize(1018)) }
            }

            it("rejects a payload that serializes to 1019 bytes") {
                shouldThrow<PayloadTooLargeException> { PushPayloadCodec.requireEncryptable(payloadOfSize(1019)) }
            }
        }
    })
