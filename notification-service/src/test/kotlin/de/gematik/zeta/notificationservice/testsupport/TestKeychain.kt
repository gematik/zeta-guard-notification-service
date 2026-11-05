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

import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import de.gematik.zeta.notificationservice.application.repository.encryption.keyVersion
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.Optional
import kotlin.time.Clock
import kotlin.time.Instant

object TestKeychain {
    const val V1 = "notification-service-keychain-v1"
    const val V2 = "notification-service-keychain-v2"
    val BEFORE_V2: Instant = Instant.parse("2025-06-01T00:00:00Z")

    class Entry(
        val id: String,
        val activeFrom: String,
        val dek: ByteArray = dekOf(id),
        val pseudonymKey: ByteArray = pseudonymKeyOf(id),
        val emergency: Boolean = false,
    )

    val DEFAULT_ENTRIES = arrayOf(Entry(V1, "2025-01-01T00:00:00Z"), Entry(V2, "2026-01-01T00:00:00Z"))

    fun dekOf(id: String): ByteArray = ByteArray(32) { (keyVersion(id)?.toInt() ?: 0).toByte() }

    fun pseudonymKeyOf(id: String): ByteArray =
        ByteArray(32) {
            (PSEUDONYM_KEY_OFFSET + (keyVersion(id)?.toInt() ?: 0)).toByte()
        }

    private const val PSEUDONYM_KEY_OFFSET = 100

    /** A keychain of [entries], wrapped by a [FakeHsmProxy] and unwrapped through one. */
    fun keychain(
        vararg entries: Entry = DEFAULT_ENTRIES,
        clock: Clock = FixedClock(),
    ): Keychain {
        val hsm = FakeHsmProxy()
        return Keychain(hsm, clock, Optional.of(json(hsm, *entries)))
    }

    fun json(
        hsm: FakeHsmProxy,
        vararg entries: Entry = DEFAULT_ENTRIES,
    ): String = json(hsm::wrapKeys, *entries)

    fun json(
        wrap: (ByteArray, ByteArray) -> WrappedKeys,
        vararg entries: Entry = DEFAULT_ENTRIES,
    ): String =
        buildJsonArray {
            entries.forEach { entry ->
                val aad = if (entry.emergency) "${entry.id}/emergency" else entry.id
                val wrapped = wrap(entry.dek + entry.pseudonymKey, aad.encodeToByteArray())
                addJsonObject {
                    put("id", entry.id)
                    put("kekId", wrapped.kekId)
                    put("activeFrom", entry.activeFrom)
                    if (entry.emergency) put("emergency", true)
                    put("keys", Base64.getEncoder().encodeToString(wrapped.wrapped))
                }
            }
        }.toString()
}
