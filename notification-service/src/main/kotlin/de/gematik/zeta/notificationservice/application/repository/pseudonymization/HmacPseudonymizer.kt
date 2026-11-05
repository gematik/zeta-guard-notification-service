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
package de.gematik.zeta.notificationservice.application.repository.pseudonymization

import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import javax.crypto.Mac

/**
 * Derives storage ids with the pseudonymization keys of the [Keychain]: new records under the entry that
 * takes the writes, lookups under every entry, oldest first.
 */
class HmacPseudonymizer(
    private val keychain: Keychain,
) : Pseudonymizer {
    override fun writeKeychainId(): String = keychain.writeId

    override fun pseudonymize(
        id: String,
        keychainId: String,
    ): String =
        Mac
            .getInstance(ALGORITHM)
            .apply { init(keychain.pseudonymKey(keychainId)) }
            .doFinal(id.encodeToByteArray())
            .toHexString()

    override fun lookupIds(id: String): List<String> = keychain.ids.map { pseudonymize(id, it) }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
