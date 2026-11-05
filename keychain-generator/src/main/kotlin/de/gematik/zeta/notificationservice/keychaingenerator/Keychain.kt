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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.math.BigInteger

internal fun readKeychain(path: String): JsonArray {
    val file = File(path)
    fail(!file.isFile || !file.canRead()) { "KEYCHAIN_INPUT_FILE $path is not a readable file" }
    val text = file.readText().trim()
    if (text.isEmpty()) {
        return JsonArray(emptyList())
    }
    return try {
        Json.parseToJsonElement(text).jsonArray.onEach { it.jsonObject }
    } catch (e: IllegalArgumentException) {
        throw GeneratorException("KEYCHAIN_INPUT_FILE $path is not a JSON array of entries: ${e.message}", e)
    }
}

// The first entry of a keychain, or the next version of the newest one.
internal fun nextEntryId(existing: JsonArray): String {
    if (existing.isEmpty()) {
        return DEFAULT_ENTRY_ID
    }
    val (family, newest) = versionOf(newestEntry(existing))
    return "$family-v${newest + BigInteger.ONE}"
}

// The KEK the newest entry is wrapped under; none for a new keychain.
internal fun newestKekId(existing: JsonArray): String? =
    existing.takeIf { it.isNotEmpty() }?.let { (newestEntry(it).jsonObject["kekId"] as? JsonPrimitive)?.content }

private fun newestEntry(existing: JsonArray) = existing.maxBy { versionOf(it).second }

private fun versionOf(entry: JsonElement): Pair<String, BigInteger> {
    val id = (entry.jsonObject["id"] as? JsonPrimitive)?.content.orEmpty()
    val (family, version) =
        VERSIONED_ID.matchEntire(id)?.destructured
            ?: throw GeneratorException("the keychain holds an entry '$id' without a -v<N> version")
    return family to version.toBigInteger()
}

private const val DEFAULT_ENTRY_ID = "notification-service-keychain-v1"
private val VERSIONED_ID = Regex("(.+)-v(\\d+)")
