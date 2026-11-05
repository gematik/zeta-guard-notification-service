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

import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException

/**
 * Identifies one pusher. Both parts are mandatory in the contract's schema, so their absence is already
 * rejected while deserializing the body; what remains for us is a value that is present but empty.
 *
 * That check lives here rather than in [PusherFactory] because it is an invariant of the identifier, not of
 * registration: `POST /pushers/set` addresses a pusher by this pair on the deregistration path too
 * (`kind: null`), and a blank identifier is no more addressable there than it is here.
 *
 * A present-but-blank value is a format violation, not a missing one, so this maps to `M_INVALID_PARAM`
 * rather than the `M_MISSING_PARAM` used for absent keys (`A_29986`).
 */
data class PusherId(
    val appId: String,
    val pushKey: String,
) {
    init {
        val blank =
            buildList {
                if (pushKey.isBlank()) add("pushkey")
                if (appId.isBlank()) add("app_id")
            }
        if (blank.isNotEmpty()) {
            throw InvalidPusherException("Blank parameters: ${blank.joinToString(", ")}")
        }
    }
}
