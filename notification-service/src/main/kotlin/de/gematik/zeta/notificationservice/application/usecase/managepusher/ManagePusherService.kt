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
import de.gematik.zeta.notificationservice.application.repository.PushkeyOwnersRepository
import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
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
        val pushKey = registered.pusherId.pushKey
        if (pushersRepository.findBy(userId)?.channelConfigOf(pushKey) == null) {
            deviceMessageHistoryRepository.delete(userId, pushKey)
        }
        pushersRepository.update(userId) { existing ->
            existing?.register(registered) ?: PushersCollection.create(userId, registered)
        }
        claim(userId, registered.pusherId, registered.append)
    }

    /**
     * Records [userId] as an owner of the pusher and, unless [append] is set, removes it from every other owner.
     */
    private fun claim(
        userId: UserId,
        pusherId: PusherId,
        append: Boolean,
    ) {
        var others: Set<UserId> = emptySet()
        pushkeyOwnersRepository.update(pusherId) { existing ->
            val owners = existing?.add(userId) ?: PushkeyOwners.create(pusherId, userId)
            others = owners.othersThan(userId)
            owners
        }
        if (append || others.isEmpty()) {
            return
        }
        others.forEach { other -> removePusher(other, pusherId) }
        pushkeyOwnersRepository.update(pusherId) { existing -> existing?.release(others) }
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
        }
    }

    /**
     * Deregisters a pusher, tolerating a user or pushkey we hold nothing for.
     *
     * Deregistration is idempotent: if there is no record, the desired end state already holds, and that is the
     * normal situation for anyone who never registered or who just removed their last device. Failing instead
     * would also answer differently depending on whether a KVNR has any pushers at all, which is the kind of
     * differential response A_29978 rules out. The contract declares no such error on `POST /pushers/set`
     * either — only 200, 400 and 429.
     */
    override fun remove(
        userId: UserId,
        pusherId: PusherId,
    ) {
        removePusher(userId, pusherId)
        pushkeyOwnersRepository.update(pusherId) { existing -> existing?.release(setOf(userId)) }
    }

    override fun removeMany(
        userId: UserId,
        pushKeys: Set<String>,
    ) {
        pushersRepository.update(userId) { existing -> existing?.removeAll(pushKeys) }
        pushKeys.forEach { deviceMessageHistoryRepository.delete(userId, it) }
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
