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
package de.gematik.zeta.notificationservice.adapter.outbound.pushgateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PushGatewayEncryptedBatchRequest(
    val notifications: List<PushGatewayBatchItem>,
)

@Serializable
data class PushGatewayBatchItem(
    val id: String,
    val notification: PushGatewayEncryptedNotification,
)

@Serializable
data class PushGatewayEncryptedNotification(
    val ciphertext: String,
    @SerialName("time_message_encrypted") val timeMessageEncrypted: String,
    @SerialName("key_identifier") val keyIdentifier: String,
    val prio: String,
    val device: PushGatewayDevice,
    val identifier: String? = null,
)

@Serializable
data class PushGatewayPlainBatchRequest(
    val notifications: List<PushGatewayPlainBatchItem>,
)

@Serializable
data class PushGatewayPlainBatchItem(
    val id: String,
    val notification: PushGatewayPlainNotification,
)

@Serializable
data class PushGatewayPlainNotification(
    val prio: String,
    val devices: List<PushGatewayDevice>,
    val content: JsonObject? = null,
)

@Serializable
data class PushGatewayDevice(
    @SerialName("app_id") val appId: String,
    val pushkey: String,
    val data: PushGatewayPusherData,
)

@Serializable
data class PushGatewayPusherData(
    val format: String? = null,
    val platform: String? = null,
)

@Serializable
data class PushGatewayBatchResponse(
    val results: List<PushGatewayItemResult>,
    val summary: PushGatewaySummary,
)

@Serializable
data class PushGatewayItemResult(
    val id: String,
    val status: String,
    val rejected: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class PushGatewaySummary(
    val total: Int,
    val successful: Int,
    val failed: Int,
    val partial: Int,
)
