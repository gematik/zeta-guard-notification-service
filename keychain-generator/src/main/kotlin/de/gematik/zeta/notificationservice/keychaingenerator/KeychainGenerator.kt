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
package de.gematik.zeta.notificationservice.keychaingenerator

import de.gematik.zetaguard.hsmproxy.grpc.HsmProxyGrpcClient
import io.grpc.StatusRuntimeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DrbgParameters
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Generates the two keys of a keychain entry, the DEK and the pseudonymization key, lets the HSM proxy wrap
 * them together and appends the entry to a keychain in the format of
 * `notification.persistence.sealing.keychain`. Configured by environment:
 *
 * - `HSM_PROXY_ENDPOINT`: host:port of the HSM proxy (default `localhost:50051`)
 * - `HSM_PROXY_KEK_ID`: the KEK to wrap under, e.g. `notification-service-keychain-kek-aesgcm-v1`. Required
 *   for a new keychain; when extending one, it defaults to the KEK of the newest entry
 * - `KEYCHAIN_INPUT_FILE`: optional, the current keychain; the new entry is appended to it
 * - `KEYCHAIN_ENTRY_ID`: optional, id of the new entry. Unset, it is
 *   `notification-service-keychain-v1` for a new keychain and the next version of the newest entry
 *   when extending one
 * - `KEYCHAIN_ACTIVE_FROM`: UTC instant from which the entry takes over writes, e.g. `2026-11-01T00:00:00Z`.
 *   Optional for a new keychain, where it defaults to now; required when extending one, since an entry
 *   must not take over before every instance holds it
 * - `KEYCHAIN_EMERGENCY`: optional, `true` marks the entry as an emergency rotation, after which the
 *   service also moves the records with a retention period instead of waiting for them to expire
 * - `KEYCHAIN_OUTPUT_FILE`: where to write the resulting keychain; if it exists already, the generator
 *   leaves it alone and ends successfully
 *
 * The associated data of the wrap is the entry id, followed by `/emergency` for an emergency entry, so the
 * flag cannot be changed in the keychain without the unwrap failing. The unwrapped keys never leave this
 * process: they are created here, sent to the proxy's `Encrypt` and overwritten afterward. Only the
 * wrapped form is written out.
 */
fun main() {
    try {
        val output = Path.of(System.getenv().required("KEYCHAIN_OUTPUT_FILE"))
        // Like the integrity provider's generator: an existing keychain means there is nothing to do,
        // so the generator can run as an init container on every pod start.
        if (Files.isDirectory(output)) {
            System.err.println("keychain-generator: $output is a directory")
            exitProcess(2)
        }
        if (Files.exists(output)) {
            System.err.println("keychain-generator: $output already exists, leaving it as it is")
            return
        }
        writeNew(output, generate(System.getenv()))
    } catch (e: GeneratorException) {
        System.err.println("keychain-generator: ${e.message}")
        exitProcess(1)
    }
}

private fun generate(env: Map<String, String>): String {
    val endpoint = env["HSM_PROXY_ENDPOINT"]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
    val existing = env.optional("KEYCHAIN_INPUT_FILE")?.let(::readKeychain) ?: JsonArray(emptyList())
    val kekIdSetting = env.optional("HSM_PROXY_KEK_ID") ?: newestKekId(existing)
    val activeFromSetting = env.optional("KEYCHAIN_ACTIVE_FROM")
    val missing =
        listOfNotNull(
            "HSM_PROXY_KEK_ID (required for a new keychain)".takeIf { kekIdSetting == null },
            "KEYCHAIN_ACTIVE_FROM (required when extending a keychain)".takeIf {
                activeFromSetting == null && existing.isNotEmpty()
            },
        )
    fail(missing.isNotEmpty()) { "set ${missing.joinToString(" and ")}" }
    val kekId = checkNotNull(kekIdSetting).also(::requireKeyId)
    val entryId = (env.optional("KEYCHAIN_ENTRY_ID") ?: nextEntryId(existing)).also(::requireKeyId)
    val activeFrom = (activeFromSetting ?: currentInstant()).also(::requireInstant)
    val emergency = env.flag("KEYCHAIN_EMERGENCY")
    fail(
        existing.any { it.jsonObject["id"] == JsonPrimitive(entryId) },
    ) { "the keychain already holds an entry $entryId" }
    val kind = if (emergency) "emergency entry" else "entry"
    System.err.println("keychain-generator: adding $kind $entryId under $kekId, active from $activeFrom")

    val aad = if (emergency) "$entryId$EMERGENCY_AAD_SUFFIX" else entryId
    val entry =
        buildJsonObject {
            put("id", entryId)
            put("kekId", kekId)
            put("activeFrom", activeFrom)
            if (emergency) put("emergency", true)
            put("keys", wrapNewKeys(endpoint, kekId, aad))
        }
    return Json.encodeToString(JsonArray(existing + entry))
}

// DEK and pseudonymization key, 32 bytes each, wrapped together in this order.
private fun wrapNewKeys(
    endpoint: String,
    kekId: String,
    aad: String,
): String {
    val keys = ByteArray(KEYS_LENGTH)
    try {
        newCryptoRandom().nextBytes(keys)
        val response =
            try {
                HsmProxyGrpcClient(endpoint).use { it.encrypt(kekId, keys, aad.encodeToByteArray()) }
            } catch (e: StatusRuntimeException) {
                throw GeneratorException(
                    "Encrypt at the HSM proxy $endpoint failed: ${e.status.code} ${e.status.description.orEmpty()}",
                    e,
                )
            }
        val iv = response.iv.toByteArray()
        val ciphertext = response.ciphertext.toByteArray()
        val tag = response.tag.toByteArray()
        fail(iv.size != IV_LENGTH) { "the HSM proxy returned an IV of ${iv.size} bytes" }
        fail(ciphertext.size != KEYS_LENGTH) { "the HSM proxy returned ${ciphertext.size} ciphertext bytes" }
        fail(tag.size != TAG_LENGTH) { "the HSM proxy returned a tag of ${tag.size} bytes" }
        return Base64.getEncoder().encodeToString(iv + ciphertext + tag)
    } finally {
        keys.fill(0)
    }
}

private fun writeNew(
    output: Path,
    keychain: String,
) {
    try {
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, keychain + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    } catch (e: IOException) {
        throw GeneratorException("cannot write $output: $e", e)
    }
}

private fun requireKeyId(keyId: String) {
    fail(!KEY_ID_SCHEME.matches(keyId)) { "key id must follow the A_28831 scheme and end in -v<N>, was '$keyId'" }
}

private fun requireInstant(value: String) {
    try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        throw GeneratorException(
            "KEYCHAIN_ACTIVE_FROM must be a UTC instant like 2026-11-01T00:00:00Z, was '$value'",
            e,
        )
    }
    fail(!value.endsWith("Z")) { "KEYCHAIN_ACTIVE_FROM must end in Z, was '$value'" }
}

// A new keychain's first entry may take over at once: no instance holds an older one.
private fun currentInstant(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

internal inline fun fail(
    condition: Boolean,
    message: () -> String,
) {
    if (condition) throw GeneratorException(message())
}

// Same generator as the service's newCryptoRandom(): SP 800-90A DRBG at 256 bits (GS-A_4367).
private fun newCryptoRandom(): SecureRandom =
    SecureRandom.getInstance(
        "DRBG",
        DrbgParameters.instantiation(SECURITY_STRENGTH_BITS, DrbgParameters.Capability.RESEED_ONLY, null),
    )

internal class GeneratorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val DEFAULT_ENDPOINT = "localhost:50051"
private const val KEYS_LENGTH = 64
private const val EMERGENCY_AAD_SUFFIX = "/emergency"
private const val IV_LENGTH = 12
private const val TAG_LENGTH = 16
private const val SECURITY_STRENGTH_BITS = 256
private val KEY_ID_SCHEME = Regex("([a-z0-9]+-){3,}v\\d+")
