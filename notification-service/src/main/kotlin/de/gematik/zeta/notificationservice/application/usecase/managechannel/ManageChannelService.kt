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
package de.gematik.zeta.notificationservice.application.usecase.managechannel

import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.model.ChannelConfiguration
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.DeviceChannelConfig
import de.gematik.zeta.notificationservice.domain.model.UserId
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ManageChannelService(
    private val pushersRepository: PushersRepository,
    private val channelRegistry: ChannelRegistry,
) : ManageChannelUseCase {
    override fun availableChannels(): List<ChannelId> = channelRegistry.all()

    override fun channelsOfDevice(
        userId: UserId,
        pushKey: String,
    ): Map<ChannelId, ChannelStatus> {
        val config = deviceConfigOf(userId, pushKey)
        return channelRegistry.all().associateWith { config.statusOf(it) }
    }

    override fun configure(
        userId: UserId,
        pushKey: String,
        requested: List<ChannelConfiguration>,
    ) {
        val changes = DeviceChannelConfig.changesOf(requested)
        channelRegistry.requireAll(changes.keys)

        pushersRepository.update(userId) { collection ->
            (collection ?: throw PusherNotFoundException()).configureChannels(pushKey, changes)
        }
    }

    private fun deviceConfigOf(
        userId: UserId,
        pushKey: String,
    ): DeviceChannelConfig =
        pushersRepository.findBy(userId)?.channelConfigOf(pushKey) ?: throw PusherNotFoundException()
}
