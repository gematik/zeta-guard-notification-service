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
package de.gematik.zeta.notificationservice.application.repository.encryption

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PayloadSealerWithDek(
    private val keychain: Keychain,
) : PayloadSealer {
    private val random = newCryptoRandom()

    override fun open(record: StorageRecord): String {
        val sealed =
            try {
                Base64.getDecoder().decode(record.payload)
            } catch (_: IllegalArgumentException) {
                error("Sealed payload is not base64")
            }
        check(sealed.size > SALT_LENGTH + IV_LENGTH + TAG_LENGTH) { "Sealed payload too short: ${sealed.size} bytes" }
        return withRecordKey(record.keychainId, sealed.copyOfRange(0, SALT_LENGTH)) { recordKey ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                recordKey,
                GCMParameterSpec(TAG_LENGTH_BITS, sealed, SALT_LENGTH, IV_LENGTH),
            )
            cipher.updateAAD(record.id.encodeToByteArray())
            cipher.doFinal(sealed, SALT_LENGTH + IV_LENGTH, sealed.size - SALT_LENGTH - IV_LENGTH).decodeToString()
        }
    }

    override fun seal(
        storageId: String,
        keychainId: String,
        payload: String,
    ): StorageRecord {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val ciphertext =
            withRecordKey(keychainId, salt) { recordKey ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, recordKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
                cipher.updateAAD(storageId.encodeToByteArray())
                cipher.doFinal(payload.encodeToByteArray())
            }
        val sealed = Base64.getEncoder().encodeToString(salt + iv + ciphertext)
        return StorageRecord(id = storageId, payload = sealed, keychainId = keychainId)
    }

    private inline fun <T> withRecordKey(
        keychainId: String,
        salt: ByteArray,
        use: (SecretKey) -> T,
    ): T {
        val mac = Mac.getInstance(KDF_ALGORITHM)
        mac.init(keychain.key(keychainId))
        mac.update(RECORD_KEY_LABEL.encodeToByteArray())
        mac.update(salt)
        mac.update(FIRST_BLOCK)
        val recordKey = ZeroizableSecretKey(mac.doFinal(), KEY_ALGORITHM)
        return try {
            use(recordKey)
        } finally {
            recordKey.destroy()
        }
    }

    private companion object {
        const val RECORD_KEY_LABEL = "notification-service record key"
        const val SALT_LENGTH = 32
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16
        const val TAG_LENGTH_BITS = 128
        const val FIRST_BLOCK: Byte = 1
        const val KDF_ALGORITHM = "HmacSHA256"
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
