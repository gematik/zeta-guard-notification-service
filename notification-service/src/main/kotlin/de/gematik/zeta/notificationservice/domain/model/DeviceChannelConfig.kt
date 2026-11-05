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

import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException

// Keeps the generated copy() as private as the constructor: instances come from `of`/`EMPTY`, which
// defensively copy. Without this, copy() would be public — a warning today, a compile error in Kotlin 2.5.
@ConsistentCopyVisibility
data class DeviceChannelConfig private constructor(
    val statuses: Map<ChannelId, ChannelStatus>,
) {
    fun isEmpty(): Boolean = statuses.isEmpty()

    fun statusOf(channelId: ChannelId): ChannelStatus = statuses[channelId] ?: ChannelStatus.NOT_SET

    /**
     * Returns `this` when the requested statuses are already in effect, so callers can recognise a
     * no-op by identity and skip re-writing the aggregate: every store re-seals the record under a
     * fresh record key, which is far more expensive than the comparison.
     */
    fun merge(changes: Map<ChannelId, ChannelStatus>): DeviceChannelConfig {
        if (changes.isEmpty()) {
            return this
        }
        val merged = of(statuses + changes)
        return if (merged.statuses == statuses) this else merged
    }

    companion object {
        val EMPTY = DeviceChannelConfig(emptyMap())

        fun of(statuses: Map<ChannelId, ChannelStatus>): DeviceChannelConfig =
            DeviceChannelConfig(statuses.filterValues { it != ChannelStatus.NOT_SET })

        fun changesOf(requested: List<ChannelConfiguration>): Map<ChannelId, ChannelStatus> {
            val changes = LinkedHashMap<ChannelId, ChannelStatus>(requested.size)
            requested.forEach {
                if (changes.put(it.channelId, it.status) != null) {
                    throw InvalidChannelException("Channel '${it.channelId.value}' is listed more than once")
                }
            }
            return changes
        }
    }
}
