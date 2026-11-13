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

/**
 * The users that hold a registration for one pusher. It is the reverse of [PushersCollection], which is stored
 * per user, and it decides who may register or deregister the pusher: a user other than the ones listed here is
 * rejected with 403 (A_29978). Normally that is one user; pushers shared through `append: true` before A_29978
 * can still list several.
 *
 * Registrations made before this index existed are not listed here until their owner registers them again.
 */
class PushkeyOwners private constructor(
    val pusherId: PusherId,
    val owners: Set<UserId>,
) {
    fun add(userId: UserId): PushkeyOwners = if (userId in owners) this else PushkeyOwners(pusherId, owners + userId)

    fun release(users: Set<UserId>): PushkeyOwners =
        if (owners.none { it in users }) this else PushkeyOwners(pusherId, owners - users)

    fun othersThan(userId: UserId): Set<UserId> = owners - userId

    fun markedForRemoval(): Boolean = owners.isEmpty()

    companion object {
        fun create(
            pusherId: PusherId,
            owner: UserId,
        ): PushkeyOwners = PushkeyOwners(pusherId, setOf(owner))

        fun reconstitute(
            pusherId: PusherId,
            owners: Iterable<UserId>,
        ): PushkeyOwners = PushkeyOwners(pusherId, owners.toSet())
    }
}
