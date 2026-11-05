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

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

data class HistoryMessage(
    val notificationId: NotificationId,
    val payload: JsonObject,
    val sentAt: Instant,
)

/**
 * The message history of one device, keyed by user *and* pushkey: two users can register the same
 * pushkey, so a pushkey alone would merge their histories.
 */
class DeviceMessageHistory private constructor(
    val userId: UserId,
    val pushKey: String,
    val messages: List<HistoryMessage>,
) {
    fun append(message: HistoryMessage): DeviceMessageHistory =
        DeviceMessageHistory(userId, pushKey, (messages + message).takeLast(MAX_MESSAGES))

    companion object {
        const val MAX_MESSAGES = 50

        fun empty(
            userId: UserId,
            pushKey: String,
        ): DeviceMessageHistory = DeviceMessageHistory(userId, pushKey, emptyList())

        fun reconstitute(
            userId: UserId,
            pushKey: String,
            messages: List<HistoryMessage>,
        ): DeviceMessageHistory = DeviceMessageHistory(userId, pushKey, messages.toList())
    }
}
