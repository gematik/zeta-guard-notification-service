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

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyPort
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Optional
import javax.crypto.SecretKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The keys of the sealed persistence, as the operator supplies them HSM-wrapped: per entry one DEK, which
 * seals the payloads, and one pseudonymization key, which derives the storage ids. Both are wrapped
 * together under the entry's KEK, with the entry id (plus `/emergency` for an emergency entry) as
 * associated data, and unwrapped once, on first use.
 */
@ApplicationScoped
class Keychain(
    private val hsmProxy: HsmProxyPort,
    private val clock: Clock,
    keychain: Optional<String>,
) {
    // The keychain comes either as the value itself or, for an init container that writes it at pod
    // start, as a file; never both, so it is always clear which one counts.
    @Inject
    constructor(
        hsmProxy: HsmProxyPort,
        clock: Clock,
        @ConfigProperty(name = KEYCHAIN_PROPERTY) keychain: Optional<String>,
        @ConfigProperty(name = KEYCHAIN_FILE_PROPERTY) keychainFile: Optional<String>,
    ) : this(hsmProxy, clock, configured(keychain, keychainFile))

    private val entries = parse(keychain.orElse(null) ?: "[]").sortedBy { keyVersion(it.id) }

    init {
        check(entries.isNotEmpty() && entries.all { keyVersion(it.id) != null && followsKeyIdScheme(it.kekId) }) {
            "$KEYCHAIN_PROPERTY must list entries whose ids end in -v<N>, wrapped under KEKs that follow the " +
                "A_28831 scheme, was ${entries.map { "${it.id} under ${it.kekId}" }}"
        }
        check(
            entries.zipWithNext().all { (older, newer) ->
                isSupersededVersion(older.id, newer.id) && older.activation < newer.activation
            },
        ) {
            "$KEYCHAIN_PROPERTY must list distinct versions of one entry id whose activeFrom rises with the version, " +
                "was ${entries.map { "${it.id} from ${it.activeFrom}" }}"
        }
    }

    private val lock = Any()

    @Volatile
    private var cachedKeys: Map<String, EntryKeys>? = null

    /** The entry ids, oldest first. */
    val ids: List<String> = entries.map { it.id }

    val writeId: String
        get() {
            val now = clock.now()
            return checkNotNull(entries.lastOrNull { it.activation <= now }) {
                "No entry in $KEYCHAIN_PROPERTY is active at $now"
            }.id
        }

    /** The DEK of entry [id]. */
    fun key(id: String): SecretKey = keysOf(id).dek

    fun pseudonymKey(id: String): SecretKey = keysOf(id).pseudonymKey

    fun holds(id: String): Boolean = entries.any { it.id == id }

    fun isEmergency(id: String): Boolean = checkNotNull(entries.firstOrNull { it.id == id }) { notHeld(id) }.emergency

    private fun keysOf(id: String): EntryKeys = checkNotNull(keys()[id]) { notHeld(id) }

    private fun notHeld(id: String) = "Entry $id is not in the keychain"

    @PreDestroy
    fun close() {
        synchronized(lock) {
            cachedKeys?.values?.forEach(EntryKeys::destroy)
            cachedKeys = null
        }
    }

    private fun keys(): Map<String, EntryKeys> =
        cachedKeys ?: synchronized(lock) {
            cachedKeys ?: unwrapAll().also { cachedKeys = it }
        }

    private fun unwrapAll(): Map<String, EntryKeys> {
        val keys = LinkedHashMap<String, EntryKeys>()
        var complete = false
        try {
            entries.forEach { keys[it.id] = unwrap(it) }
            complete = true
        } finally {
            if (!complete) {
                keys.values.forEach(EntryKeys::destroy)
            }
        }
        return keys
    }

    private fun unwrap(entry: Entry): EntryKeys {
        val wrapped =
            try {
                Base64.getDecoder().decode(entry.keys)
            } catch (_: IllegalArgumentException) {
                error("The wrapped keys of ${entry.id} in $KEYCHAIN_PROPERTY are not base64")
            }
        val aad = if (entry.emergency) "${entry.id}$EMERGENCY_AAD_SUFFIX" else entry.id
        val keys = hsmProxy.unwrapKeys(entry.kekId, wrapped, aad.encodeToByteArray())
        try {
            check(keys.size == 2 * KEY_LENGTH) {
                "The keys of ${entry.id} are ${keys.size} bytes long, expected ${2 * KEY_LENGTH}"
            }
            return EntryKeys(
                dek = ZeroizableSecretKey(keys.copyOfRange(0, KEY_LENGTH), ALGORITHM),
                pseudonymKey = ZeroizableSecretKey(keys.copyOfRange(KEY_LENGTH, 2 * KEY_LENGTH), ALGORITHM),
            )
        } finally {
            keys.fill(0)
        }
    }

    private class EntryKeys(
        val dek: ZeroizableSecretKey,
        val pseudonymKey: ZeroizableSecretKey,
    ) {
        fun destroy() {
            dek.destroy()
            pseudonymKey.destroy()
        }
    }

    @Serializable
    private class Entry(
        val id: String,
        val kekId: String,
        val activeFrom: String,
        val emergency: Boolean = false,
        val keys: String,
    ) {
        @Transient
        val activation: Instant = Instant.parse(activeFrom)
    }

    private companion object {
        const val KEYCHAIN_PROPERTY = "notification.persistence.sealing.keychain"
        const val KEYCHAIN_FILE_PROPERTY = "notification.persistence.sealing.keychain-file"
        const val ALGORITHM = "HmacSHA256"
        const val KEY_LENGTH = 32
        const val EMERGENCY_AAD_SUFFIX = "/emergency"

        private fun configured(
            keychain: Optional<String>,
            keychainFile: Optional<String>,
        ): Optional<String> {
            check(keychain.isEmpty || keychainFile.isEmpty) {
                "Set either $KEYCHAIN_PROPERTY or $KEYCHAIN_FILE_PROPERTY, not both"
            }
            return keychainFile.map(::read).or { keychain }
        }

        private fun read(path: String): String =
            try {
                Files.readString(Path.of(path))
            } catch (e: IOException) {
                error("$KEYCHAIN_FILE_PROPERTY $path cannot be read: $e")
            }

        fun parse(keychain: String): List<Entry> =
            try {
                Json.decodeFromString<List<Entry>>(keychain)
            } catch (_: IllegalArgumentException) {
                error(
                    "$KEYCHAIN_PROPERTY must be a JSON array of entries with id, kekId, activeFrom as an " +
                        "ISO-8601 instant, keys and optionally emergency",
                )
            }
    }
}
