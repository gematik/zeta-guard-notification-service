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

object ChannelStatusAggregation {
    fun statusOf(
        deviceConfigs: Collection<DeviceChannelConfig>,
        channelId: ChannelId,
    ): ChannelStatus {
        val statuses = deviceConfigs.map { it.statusOf(channelId) }
        return when {
            statuses.any { it == ChannelStatus.ENABLED } -> ChannelStatus.ENABLED
            statuses.any { it == ChannelStatus.DISABLED } -> ChannelStatus.DISABLED
            else -> ChannelStatus.NOT_SET
        }
    }

    fun statusesOf(
        deviceConfigs: Collection<DeviceChannelConfig>,
        channelIds: Collection<ChannelId>,
    ): Map<ChannelId, ChannelStatus> = channelIds.associateWith { statusOf(deviceConfigs, it) }
}
