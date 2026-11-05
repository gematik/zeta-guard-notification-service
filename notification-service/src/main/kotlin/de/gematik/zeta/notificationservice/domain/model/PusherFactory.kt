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

import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PusherFactory {
    /**
     * Builds a pusher from request data, rejecting the fields the contract requires once `kind` is set
     * (`A_29986`). The identifier validates itself — see [PusherId].
     *
     * A required field that is present but blank is reported as missing: an empty string carries no more
     * information than an absent key, and the contract's `required` only constrains presence.
     */
    fun create(
        pusherId: PusherId,
        kind: String,
        appDisplayName: String?,
        deviceDisplayName: String?,
        lang: String?,
        data: PusherData?,
        profileTag: String? = null,
        encryption: PusherEncryption? = null,
        append: Boolean = false,
    ): Pusher {
        val missing =
            buildList {
                if (appDisplayName.isNullOrBlank()) add("app_display_name")
                if (deviceDisplayName.isNullOrBlank()) add("device_display_name")
                if (lang.isNullOrBlank()) add("lang")
                if (data == null) add("data")
            }
        if (missing.isNotEmpty()) {
            throw MissingPusherFieldsException(missing)
        }
        // checkNotNull, not requireNotNull: reaching this with a null means the list above and the arguments
        // below have drifted apart, which is our bug and not the client's. IllegalStateException falls through
        // to the mapper's catch-all as a logged 500, where an IllegalArgumentException would be dressed up as a
        // 400 telling the client only "Required value was null."
        return Pusher.create(
            pusherId = pusherId,
            kind = kind,
            appDisplayName = checkNotNull(appDisplayName) { NOT_NULL_AFTER_CHECK },
            deviceDisplayName = checkNotNull(deviceDisplayName) { NOT_NULL_AFTER_CHECK },
            lang = checkNotNull(lang) { NOT_NULL_AFTER_CHECK },
            data = checkNotNull(data) { NOT_NULL_AFTER_CHECK },
            profileTag = profileTag,
            encryption = encryption,
            append = append,
        )
    }

    private companion object {
        const val NOT_NULL_AFTER_CHECK = "Field passed the missing-field check but is null"
    }
}
