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
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Keeps accepted notifications for the short retention period of A_29988. */
class DefaultNotificationsRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
    ttl: Duration,
) : AggregateRepository<Notification>(storageRecords, pseudonymizer, payloadSealer, ttl),
    NotificationsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun findBy(notificationId: NotificationId): Notification? = findByLogicalId(logicalId(notificationId))

    override fun save(notification: Notification) {
        create(logicalId(notification.id), notification)
    }

    override fun serialize(aggregate: Notification): String =
        json.encodeToString(
            NotificationDto(
                id = aggregate.id.toString(),
                userId = aggregate.userId.value,
                channel = aggregate.channel.value,
                acceptedAt = aggregate.acceptedAt.toString(),
                prio = aggregate.prio.value,
                status = aggregate.status.value,
                payload = aggregate.payload,
                reference = aggregate.reference,
            ),
        )

    override fun deserialize(payload: String): Notification {
        val dto = json.decodeFromString<NotificationDto>(payload)
        return Notification(
            id = NotificationId.parseOrNull(dto.id) ?: error("Stored notification id is not a uuid"),
            userId = UserId.parse(dto.userId),
            channel = ChannelId.reconstitute(dto.channel),
            prio = Priority.from(dto.prio) ?: Priority.NORMAL,
            payload = dto.payload,
            reference = dto.reference,
            status = NotificationStatus.from(dto.status) ?: NotificationStatus.ACCEPTED,
            acceptedAt = Instant.parseOrNull(dto.acceptedAt) ?: Instant.DISTANT_PAST,
        )
    }

    private fun logicalId(notificationId: NotificationId): String = notificationId.toString() + LOGICAL_ID_SUFFIX

    @Serializable
    private data class NotificationDto(
        val id: String,
        val userId: String,
        val channel: String,
        val acceptedAt: String,
        val prio: String = Priority.NORMAL.value,
        val status: String = NotificationStatus.ACCEPTED.value,
        val payload: JsonObject = JsonObject(emptyMap()),
        val reference: String? = null,
    )

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_notification"
    }
}
