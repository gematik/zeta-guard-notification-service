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

import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException

/**
 * The static catalogue of channels the one Fachdienst of this instance accepts (A_29985). Immutable
 * and fixed at boot from injected instance configuration: there is deliberately no operation that
 * adds or removes a channel at runtime, and no Fachdienst dimension.
 */
class ChannelRegistry private constructor(
    private val channels: Set<ChannelId>,
) {
    /**
     * Catalogue in configured order, so API listings are stable across requests. Materialised once:
     * the registry is immutable and application-scoped, while [all] is called on every channel request.
     */
    private val ordered: List<ChannelId> = channels.toList()

    fun contains(channelId: ChannelId): Boolean = channelId in channels

    fun all(): List<ChannelId> = ordered

    fun require(channelId: ChannelId): ChannelId =
        if (contains(channelId)) channelId else throw ChannelNotAllowedException(channelId.value)

    /** Rejects on the first channel outside the catalogue, so a partial request is never applied. */
    fun requireAll(channelIds: Collection<ChannelId>) {
        channelIds.forEach { require(it) }
    }

    companion object {
        /**
         * Parses through the validating [ChannelId.parse], so a malformed identifier in the instance
         * configuration fails the boot instead of producing a channel that `GET /channels` advertises
         * but `POST /channels/{pushkey}` rejects.
         */
        fun of(channelIds: Collection<String>): ChannelRegistry {
            val configured = channelIds.map { it.trim() }.filter { it.isNotEmpty() }
            require(configured.isNotEmpty()) { "The channel registry must contain at least one channel" }
            return ChannelRegistry(configured.mapTo(LinkedHashSet()) { ChannelId.parse(it) })
        }
    }
}
