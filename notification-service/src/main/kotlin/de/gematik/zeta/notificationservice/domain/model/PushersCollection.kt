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

import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException

class PushersCollection private constructor(
    val userId: UserId,
    val pushers: List<Pusher>,
    val channelConfig: Map<String, DeviceChannelConfig>,
) {
    /** Re-registering a device updates its metadata and keeps the user's channel choices. */
    fun register(pusher: Pusher): PushersCollection {
        val updatedPushers = pushers.filterNot { it.pusherId == pusher.pusherId } + pusher
        return PushersCollection(userId, updatedPushers, channelConfig)
    }

    fun remove(pusherId: PusherId): PushersCollection = removeWhere { it.pusherId == pusherId }

    fun removeAll(pushKeys: Set<String>): PushersCollection = removeWhere { it.pusherId.pushKey in pushKeys }

    private fun removeWhere(matches: (Pusher) -> Boolean): PushersCollection {
        val updatedPushers = pushers.filterNot(matches)
        if (updatedPushers.size == pushers.size) {
            return this
        }
        val remainingPushKeys = updatedPushers.map { it.pusherId.pushKey }.toSet()
        return PushersCollection(userId, updatedPushers, channelConfig.filterKeys { it in remainingPushKeys })
    }

    fun pusher(pusherId: PusherId): Pusher? = pushers.firstOrNull { it.pusherId == pusherId }

    fun channelConfigOf(pushKey: String): DeviceChannelConfig? =
        if (pushers.none { it.pusherId.pushKey == pushKey }) {
            null
        } else {
            channelConfig[pushKey] ?: DeviceChannelConfig.EMPTY
        }

    fun configureChannels(
        pushKey: String,
        changes: Map<ChannelId, ChannelStatus>,
    ): PushersCollection {
        val current = channelConfigOf(pushKey) ?: throw PusherNotFoundException()
        val updated = current.merge(changes)
        if (updated === current) {
            return this
        }
        val configs = if (updated.isEmpty()) channelConfig - pushKey else channelConfig + (pushKey to updated)
        return PushersCollection(userId, pushers, configs)
    }

    fun channelStatus(channelId: ChannelId): ChannelStatus =
        ChannelStatusAggregation.statusOf(deviceConfigs(), channelId)

    fun pushersWithChannelEnabled(channelId: ChannelId): List<Pusher> =
        pushers
            .distinctBy { it.pusherId.pushKey }
            .filter { channelConfig[it.pusherId.pushKey]?.statusOf(channelId)?.isActive() == true }

    fun channelStatuses(channelIds: Collection<ChannelId>): Map<ChannelId, ChannelStatus> =
        ChannelStatusAggregation.statusesOf(deviceConfigs(), channelIds)

    private fun deviceConfigs(): List<DeviceChannelConfig> =
        pushers
            .mapTo(LinkedHashSet()) { it.pusherId.pushKey }
            .mapNotNull { channelConfig[it] }

    fun hasRegisteredDevices(): Boolean = pushers.isNotEmpty()

    fun markedForRemoval(): Boolean = !hasRegisteredDevices()

    companion object {
        fun create(
            userId: UserId,
            pusher: Pusher,
        ): PushersCollection = PushersCollection(userId, listOf(pusher), emptyMap())

        fun reconstitute(
            userId: UserId,
            pushers: List<Pusher>,
            channelConfig: Map<String, DeviceChannelConfig> = emptyMap(),
        ): PushersCollection = PushersCollection(userId, pushers, channelConfig.toMap())
    }
}
