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

import kotlinx.serialization.json.JsonObject

data class Device(
    val appId: String,
    val pushkey: String,
    val format: String? = null,
    val platform: String? = null,
)

sealed interface PushDispatchItem {
    val id: String
    val prio: Priority
}

data class EncryptedPushItem(
    override val id: String,
    override val prio: Priority = Priority.HIGH,
    val device: Device,
    val ciphertext: String,
    val timeMessageEncrypted: String,
    val keyIdentifier: String,
    val reference: String? = null,
) : PushDispatchItem {
    init {
        require(ciphertext.length == CIPHERTEXT_LENGTH) {
            "ciphertext must be exactly $CIPHERTEXT_LENGTH chars, was ${ciphertext.length}"
        }
    }

    companion object {
        // [IV][Ciphertext][Auth-Tag] have a fixed length
        const val CIPHERTEXT_LENGTH = 1404
    }
}

data class PlainPushItem(
    override val id: String,
    override val prio: Priority = Priority.HIGH,
    val devices: List<Device>,
    val payload: JsonObject? = null,
) : PushDispatchItem
