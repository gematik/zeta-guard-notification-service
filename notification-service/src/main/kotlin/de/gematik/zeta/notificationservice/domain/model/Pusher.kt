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

data class Pusher private constructor(
    val pusherId: PusherId,
    val kind: String,
    val appDisplayName: String,
    val deviceDisplayName: String,
    val lang: String,
    val data: PusherData,
    val profileTag: String? = null,
    val keyMaterial: PusherKeyMaterial? = null,
    val append: Boolean,
) {
    fun withKeyMaterial(material: PusherKeyMaterial?): Pusher =
        Pusher(pusherId, kind, appDisplayName, deviceDisplayName, lang, data, profileTag, material, append)

    companion object {
        fun create(
            pusherId: PusherId,
            kind: String,
            appDisplayName: String,
            deviceDisplayName: String,
            lang: String,
            data: PusherData,
            profileTag: String? = null,
            encryption: PusherEncryption? = null,
            append: Boolean = false,
        ): Pusher {
            if (kind == "http" && data.url == null) {
                throw InvalidPusherException("data.url is required when kind is http")
            }
            return Pusher(
                pusherId = pusherId,
                kind = kind,
                appDisplayName = appDisplayName,
                deviceDisplayName = deviceDisplayName,
                lang = lang,
                data = data,
                profileTag = profileTag,
                keyMaterial = encryption?.let(PusherKeyMaterial::initial),
                append = append,
            )
        }

        fun reconstitute(
            pusherId: PusherId,
            kind: String,
            appDisplayName: String,
            deviceDisplayName: String,
            lang: String,
            data: PusherData,
            profileTag: String? = null,
            keyMaterial: PusherKeyMaterial? = null,
            append: Boolean,
        ): Pusher =
            Pusher(
                pusherId = pusherId,
                kind = kind,
                appDisplayName = appDisplayName,
                deviceDisplayName = deviceDisplayName,
                lang = lang,
                data = data,
                profileTag = profileTag,
                keyMaterial = keyMaterial,
                append = append,
            )
    }
}
