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

import de.gematik.zeta.notificationservice.domain.error.InvalidUserIdException

sealed interface UserId {
    val value: String

    companion object {
        private val REGEX_KVNR = Regex("""^[A-Z][0-9]{9}$""")
        private val REGEX_TELEMATIK_ID = Regex("""^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$""")

        fun parse(raw: String): UserId =
            when {
                REGEX_KVNR.matches(raw) -> Kvnr(raw)
                REGEX_TELEMATIK_ID.matches(raw) -> TelematikId(raw)
                else -> throw InvalidUserIdException()
            }
    }
}

@JvmInline
value class Kvnr(
    override val value: String,
) : UserId

@JvmInline
value class TelematikId(
    override val value: String,
) : UserId
