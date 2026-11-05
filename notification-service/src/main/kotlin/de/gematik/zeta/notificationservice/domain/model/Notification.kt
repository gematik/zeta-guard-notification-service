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

data class Notification(
    val id: NotificationId,
    val userId: UserId,
    val channel: ChannelId,
    val prio: Priority,
    val payload: JsonObject,
    val reference: String?,
    val status: NotificationStatus,
    val acceptedAt: Instant,
) {
    fun isOwnedBy(userId: UserId): Boolean = this.userId == userId

    // The payload is the notification text the Resource Server sent, i.e. health data in the clear; the
    // generated toString would put it into any log line or exception message that interpolates the object.
    // A_29990
    override fun toString(): String =
        "Notification(id=$id, channel=$channel, prio=$prio, status=$status, acceptedAt=$acceptedAt)"
}
