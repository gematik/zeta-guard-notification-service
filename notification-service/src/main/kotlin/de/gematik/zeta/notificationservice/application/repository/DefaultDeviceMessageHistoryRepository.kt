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
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Stores a [DeviceMessageHistory] under a SHA-256 of user and pushkey, so the id stays fixed-width with sealing off
 * as well: the pushkey may be up to 512 bytes, the id column holds 450 characters.
 *
 * Versions up to 2.0.1 stored it under the plain `<user>/<pushkey>` id. Such a record is still read, moved to the
 * hashed id by the next append and deleted with the history. Records written by 1.3.x have no retention deadline,
 * so the legacy id stays until no deployment can hold one of those any more.
 */
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
    ): DeviceMessageHistory? = findByLogicalId(logicalId(userId, pushKey), legacyLogicalId(userId, pushKey))

    override fun append(
        userId: UserId,
        pushKey: String,
        message: HistoryMessage,
    ) {
        update(logicalId(userId, pushKey), legacyLogicalId(userId, pushKey)) { history ->
            (history ?: DeviceMessageHistory.empty(userId, pushKey)).append(message)
        }
    }

    override fun delete(
        userId: UserId,
        pushKey: String,
    ) {
        delete(logicalId(userId, pushKey), legacyLogicalId(userId, pushKey))
    }

    // Both exist only after a rolling update in which an old instance recreated the legacy record. Each keeps
    // its own retention deadline, so the merge happens on read and is never written back.
    override fun merge(
        legacy: DeviceMessageHistory,
        current: DeviceMessageHistory,
    ): DeviceMessageHistory =
        DeviceMessageHistory.reconstitute(
            userId = current.userId,
            pushKey = current.pushKey,
            messages =
                (legacy.messages + current.messages)
                    .distinctBy { it.notificationId }
                    .sortedBy { it.sentAt }
                    .takeLast(DeviceMessageHistory.MAX_MESSAGES),
        )

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
    ): String = sha256Hex(canonicalForm(userId, pushKey)) + LOGICAL_ID_SUFFIX

    /** Length-prefixed so neither part can fake a boundary with a colon of its own. */
    private fun canonicalForm(
        userId: UserId,
        pushKey: String,
    ): String = listOf(userId.storageLabel(), pushKey).joinToString(":") { "${it.length}:$it" }

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .toHexString()

    private fun legacyLogicalId(
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
