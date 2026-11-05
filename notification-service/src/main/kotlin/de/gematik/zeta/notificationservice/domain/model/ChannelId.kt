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

@JvmInline
value class ChannelId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val REGEX_CHANNEL_ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$""")

        fun parse(raw: String): ChannelId =
            if (REGEX_CHANNEL_ID.matches(raw)) {
                ChannelId(raw)
            } else {
                throw InvalidChannelException("The channel id '$raw' is not a valid channel identifier")
            }

        fun reconstitute(raw: String): ChannelId = ChannelId(raw)
    }
}
