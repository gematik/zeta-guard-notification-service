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
package de.gematik.zeta.notificationservice.application.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun withLogicalId(
    json: String,
    logicalId: String,
): String {
    val fields = Json.parseToJsonElement(json) as JsonObject
    return JsonObject(fields + (LOGICAL_ID_FIELD to JsonPrimitive(logicalId))).toString()
}

internal fun logicalIdOf(plaintext: String): String? =
    ((Json.parseToJsonElement(plaintext) as? JsonObject)?.get(LOGICAL_ID_FIELD) as? JsonPrimitive)?.contentOrNull

private const val LOGICAL_ID_FIELD = "_lid"
