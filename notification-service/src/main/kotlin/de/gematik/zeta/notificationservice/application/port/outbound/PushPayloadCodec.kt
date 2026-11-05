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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer

object PushPayloadCodec {
    const val PADDED_LENGTH = 1024
    const val MAX_MESSAGE_BYTES = PADDED_LENGTH - 4 - 2
    private const val FILL: Byte = 0x20

    fun serialize(payload: JsonObject): ByteArray =
        Json.encodeToString(JsonObject.serializer(), payload).encodeToByteArray()

    fun requireEncryptable(payload: JsonObject) {
        if (serialize(payload).size > MAX_MESSAGE_BYTES) throw PayloadTooLargeException(MAX_MESSAGE_BYTES)
    }

    fun pad(message: ByteArray): ByteArray {
        if (message.size > MAX_MESSAGE_BYTES) throw PayloadTooLargeException(MAX_MESSAGE_BYTES)
        val fill = MAX_MESSAGE_BYTES - message.size
        return ByteBuffer
            .allocate(PADDED_LENGTH)
            .put("PNM1".toByteArray(Charsets.US_ASCII))
            .putShort(fill.toShort())
            .put(ByteArray(fill) { FILL })
            .put(message)
            .array()
    }
}
