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
package de.gematik.zeta.notificationservice.application.usecase.managepusher

import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.application.repository.PushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.PushkeyOwnersRepository
import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.PushkeyHolders
import de.gematik.zeta.notificationservice.domain.model.PushkeyOwners
import de.gematik.zeta.notificationservice.domain.model.UserId
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.YearMonth
import kotlin.time.Clock

@ApplicationScoped
class ManagePusherService(
    private val pushersRepository: PushersRepository,
    private val pushkeyOwnersRepository: PushkeyOwnersRepository,
    private val pushkeyHoldersRepository: PushkeyHoldersRepository,
    private val deviceMessageHistoryRepository: DeviceMessageHistoryRepository,
    private val clock: Clock,
    @param:ConfigProperty(name = "notification.encryption.enabled") private val encryptionEnabled: Boolean,
) : ManagePusherUseCase {
    override fun get(userId: UserId): List<Pusher> = pushersRepository.findBy(userId)?.pushers.orEmpty()

    override fun register(
        userId: UserId,
        pusher: Pusher,
    ) {
        val material = pusher.keyMaterial
        val registered =
            when {
                !encryptionEnabled -> pusher.withKeyMaterial(null)
                material == null -> throw MissingPusherFieldsException(listOf("encryption"))
                else -> pusher.withKeyMaterial(withinReach(material))
            }
        val pusherId = registered.pusherId
        val pushKey = pusherId.pushKey
        val current = pushersRepository.findBy(userId)
        claim(userId, pusherId, heldAlready = current?.pusher(pusherId) != null)
        if (current?.channelConfigOf(pushKey) == null) {
            deviceMessageHistoryRepository.delete(userId, pushKey)
        }
        pushersRepository.update(userId) { existing ->
            existing?.register(registered) ?: PushersCollection.create(userId, registered)
        }
        pushkeyHoldersRepository.updateHolders(pushKey) { existing ->
            existing?.add(userId) ?: PushkeyHolders.create(pushKey, userId)
        }
    }

    /**
     * Records [userId] as an owner of the pusher, or rejects the registration if another user owns it (A_29978).
     *
     * The check runs inside the optimistic update of the owner index, so of two users registering the same pusher
     * at once exactly one gets it. A user who already holds the pusher may always register it again: pushers shared
     * through `append: true` before A_29978, or registered before the index existed, have more than one holder, and
     * each of them keeps managing its own registration. `append` itself no longer has any effect.
     */
    private fun claim(
        userId: UserId,
        pusherId: PusherId,
        heldAlready: Boolean,
    ) {
        pushkeyOwnersRepository.update(pusherId) { existing ->
            if (!heldAlready && existing != null && existing.othersThan(userId).isNotEmpty()) {
                throw PusherOfOtherUserException()
            }
            existing?.add(userId) ?: PushkeyOwners.create(pusherId, userId)
        }
    }

    private fun removePusher(
        userId: UserId,
        pusherId: PusherId,
    ) {
        var pushKeyKept = false
        pushersRepository.update(userId) { existing ->
            val remaining = existing?.remove(pusherId)
            pushKeyKept = remaining?.channelConfigOf(pusherId.pushKey) != null
            remaining
        }
        if (!pushKeyKept) {
            deviceMessageHistoryRepository.delete(userId, pusherId.pushKey)
            pushkeyHoldersRepository.updateHolders(pusherId.pushKey) { it?.release(userId) }
        }
    }

    /**
     * Deregisters a pusher, tolerating one nobody holds and rejecting one another user holds (A_29978).
     *
     * Deregistration is idempotent: if no one holds the pusher, the desired end state already holds, and that is the
     * normal situation for a client that just removed its last device.
     */
    override fun remove(
        userId: UserId,
        pusherId: PusherId,
    ) {
        if (pushersRepository.findBy(userId)?.pusher(pusherId) == null &&
            pushkeyOwnersRepository
                .findBy(pusherId)
                ?.othersThan(userId)
                .orEmpty()
                .isNotEmpty()
        ) {
            throw PusherOfOtherUserException()
        }
        removePusher(userId, pusherId)
        pushkeyOwnersRepository.update(pusherId) { existing -> existing?.release(setOf(userId)) }
    }

    override fun removeMany(
        userId: UserId,
        pushKeys: Set<String>,
    ) {
        var removed: List<PusherId> = emptyList()
        pushersRepository.update(userId) { existing ->
            removed =
                existing
                    ?.pushers
                    ?.map { it.pusherId }
                    ?.filter { it.pushKey in pushKeys }
                    .orEmpty()
            existing?.removeAll(pushKeys)
        }
        removed.forEach { pusherId ->
            pushkeyOwnersRepository.update(pusherId) { existing -> existing?.release(setOf(userId)) }
        }
        pushKeys.forEach { pushKey ->
            deviceMessageHistoryRepository.delete(userId, pushKey)
            pushkeyHoldersRepository.updateHolders(pushKey) { it?.release(userId) }
        }
    }

    override fun currentKeyMaterial(
        userId: UserId,
        pusherId: PusherId,
        month: YearMonth,
    ): PusherKeyMaterial? {
        var current: PusherKeyMaterial? = null
        pushersRepository.update(userId) { existing ->
            val pusher = existing?.pusher(pusherId) ?: return@update existing
            val material = pusher.keyMaterial ?: return@update existing
            val ratcheted = material.ratchetTo(month)
            current = ratcheted
            if (ratcheted === material) existing else existing.register(pusher.withKeyMaterial(ratcheted))
        }
        return current
    }

    private fun withinReach(material: PusherKeyMaterial): PusherKeyMaterial {
        if (material.month > PusherKeyMaterial.monthOf(clock.now()).plusMonths(1)) {
            throw InvalidPusherException("encryption.time_iss_created must not lie more than one month in the future")
        }
        return material
    }
}
