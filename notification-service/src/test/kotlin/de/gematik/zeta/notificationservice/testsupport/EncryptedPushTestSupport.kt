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
package de.gematik.zeta.notificationservice.testsupport

import de.gematik.zeta.notificationservice.domain.model.PusherEncryption
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.time.YearMonth
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptedPushTestSupport {
    const val ISS = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
    const val OTHER_ISS = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd3"
    const val KEY_IDENTIFIER = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"
    const val CIPHERTEXT_LENGTH = 1404
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val PADDED_LENGTH = 1024
    private const val MAGIC_LENGTH = 4
    private const val HEADER_LENGTH = 6

    fun material(
        issHex: String = ISS,
        month: String,
        keyIdentifier: String = KEY_IDENTIFIER,
    ): PusherKeyMaterial =
        PusherKeyMaterial.initial(
            PusherEncryption.create(
                method = "aes-hmac-sha256",
                timeIssCreated = month,
                iss = issHex,
                keyIdentifier = keyIdentifier,
            ),
        )

    fun aesKeyFor(
        issHex: String,
        startMonth: String,
        targetMonth: String,
    ): ByteArray = material(issHex, startMonth).ratchetTo(YearMonth.parse(targetMonth)).aesKey

    fun decrypt(
        ciphertext: String,
        aesKey: ByteArray,
    ): JsonObject =
        Json.decodeFromString(JsonObject.serializer(), unpad(decryptToPadded(ciphertext, aesKey)).decodeToString())

    fun decryptToPadded(
        ciphertext: String,
        aesKey: ByteArray,
    ): ByteArray {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
    }

    fun unpad(padded: ByteArray): ByteArray {
        require(padded.size == PADDED_LENGTH) { "padded block must be $PADDED_LENGTH bytes, was ${padded.size}" }
        require(
            padded.copyOfRange(0, MAGIC_LENGTH).decodeToString() == "PNM1",
        ) { "padded block does not start with PNM1" }
        val fill = ByteBuffer.wrap(padded, MAGIC_LENGTH, HEADER_LENGTH - MAGIC_LENGTH).short.toInt() and 0xFFFF
        return padded.copyOfRange(HEADER_LENGTH + fill, PADDED_LENGTH)
    }
}
