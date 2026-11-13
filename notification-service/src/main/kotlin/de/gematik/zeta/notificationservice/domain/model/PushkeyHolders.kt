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

import de.gematik.zeta.notificationservice.domain.error.NotificationServiceException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException

/**
 * The users that hold at least one pusher with this pushkey, under whatever app ID. [PushkeyOwners] answers that
 * for one app ID and pushkey; the channel and history endpoints address a device by its pushkey alone, so they
 * need this one to tell another user's pushkey (403, A_29978) from one nobody holds (404).
 *
 * Pushkeys registered before this index existed are not listed here until their owner registers them again; until
 * then another user asking for them gets the 404 of an unknown pushkey, never their data.
 */
class PushkeyHolders private constructor(
    val pushKey: String,
    val holders: Set<UserId>,
) {
    fun add(userId: UserId): PushkeyHolders = if (userId in holders) this else PushkeyHolders(pushKey, holders + userId)

    fun release(userId: UserId): PushkeyHolders =
        if (userId !in holders) this else PushkeyHolders(pushKey, holders - userId)

    fun markedForRemoval(): Boolean = holders.isEmpty()

    companion object {
        fun create(
            pushKey: String,
            holder: UserId,
        ): PushkeyHolders = PushkeyHolders(pushKey, setOf(holder))

        fun reconstitute(
            pushKey: String,
            holders: Iterable<UserId>,
        ): PushkeyHolders = PushkeyHolders(pushKey, holders.toSet())

        /** The error for a pushkey [userId] holds no pusher for: 403 if another user holds one, 404 otherwise. */
        fun errorFor(
            holders: PushkeyHolders?,
            userId: UserId,
        ): NotificationServiceException =
            if (holders != null && holders.holders.any { it != userId }) {
                PusherOfOtherUserException()
            } else {
                PusherNotFoundException()
            }
    }
}
