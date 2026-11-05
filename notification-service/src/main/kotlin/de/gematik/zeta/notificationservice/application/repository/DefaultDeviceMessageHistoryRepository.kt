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

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.Pseudonymizer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.storageLabel
import de.gematik.zeta.notificationservice.domain.model.DeviceMessageHistory
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Instant

class DefaultDeviceMessageHistoryRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
    ttl: Duration,
) : AggregateRepository<DeviceMessageHistory>(storageRecords, pseudonymizer, payloadSealer, ttl),
    DeviceMessageHistoryRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun findBy(
        userId: UserId,
        pushKey: String,
    ): DeviceMessageHistory? = findByLogicalId(logicalId(userId, pushKey))

    override fun append(
        userId: UserId,
        pushKey: String,
        message: HistoryMessage,
    ) {
        update(logicalId(userId, pushKey)) { history ->
            (history ?: DeviceMessageHistory.empty(userId, pushKey)).append(message)
        }
    }

    override fun delete(
        userId: UserId,
        pushKey: String,
    ) {
        delete(logicalId(userId, pushKey))
    }

    override fun serialize(aggregate: DeviceMessageHistory): String =
        json.encodeToString(
            DeviceMessageHistoryDto(
                userId = aggregate.userId.value,
                pushKey = aggregate.pushKey,
                messages =
                    aggregate.messages.map {
                        HistoryMessageDto(
                            notificationId = it.notificationId.toString(),
                            sentAt = it.sentAt.toString(),
                            payload = it.payload,
                        )
                    },
            ),
        )

    override fun deserialize(payload: String): DeviceMessageHistory {
        val dto = json.decodeFromString<DeviceMessageHistoryDto>(payload)
        return DeviceMessageHistory.reconstitute(
            userId = UserId.parse(dto.userId),
            pushKey = dto.pushKey,
            messages = dto.messages.mapNotNull { it.toDomain() },
        )
    }

    private fun HistoryMessageDto.toDomain(): HistoryMessage? =
        NotificationId.parseOrNull(notificationId)?.let {
            HistoryMessage(
                notificationId = it,
                payload = payload,
                sentAt = Instant.parseOrNull(sentAt) ?: Instant.DISTANT_PAST,
            )
        }

    private fun logicalId(
        userId: UserId,
        pushKey: String,
    ): String = "${userId.storageLabel()}/$pushKey$LOGICAL_ID_SUFFIX"

    @Serializable
    private data class DeviceMessageHistoryDto(
        val userId: String,
        val pushKey: String,
        val messages: List<HistoryMessageDto> = emptyList(),
    )

    @Serializable
    private data class HistoryMessageDto(
        val notificationId: String,
        val sentAt: String,
        val payload: JsonObject = JsonObject(emptyMap()),
    )

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_history"
    }
}
