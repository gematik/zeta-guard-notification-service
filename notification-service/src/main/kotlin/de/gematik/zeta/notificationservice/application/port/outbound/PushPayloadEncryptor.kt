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
package de.gematik.zeta.notificationservice.application.port.outbound

import de.gematik.zeta.notificationservice.application.repository.encryption.ZeroizableSecretKey
import de.gematik.zeta.notificationservice.application.repository.encryption.newCryptoRandom
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

@ApplicationScoped
class PushPayloadEncryptor {
    private val random = newCryptoRandom()

    fun encrypt(
        payload: JsonObject,
        material: PusherKeyMaterial,
    ): EncryptedPayload {
        val plainText = PushPayloadCodec.pad(PushPayloadCodec.serialize(payload))
        val iv = ByteArray(12).also(random::nextBytes)
        val key = ZeroizableSecretKey(material.aesKey.copyOf(), "AES")
        val cipherText =
            try {
                Cipher
                    .getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                    .doFinal(plainText)
            } finally {
                key.destroy()
                plainText.fill(0)
            }
        return EncryptedPayload(Base64.getEncoder().encodeToString(iv + cipherText), material.month.toString())
    }
}

data class EncryptedPayload(
    val cipherText: String,
    val timeMessageEncrypted: String,
)
