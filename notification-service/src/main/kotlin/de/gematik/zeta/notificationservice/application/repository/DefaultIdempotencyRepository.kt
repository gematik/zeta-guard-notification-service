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

import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.Pseudonymizer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.storageLabel
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.IdempotencyKey
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.SubmissionReceipt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Remembers submission receipts for the retention period of A_29988, so a retransmission replays. */
class DefaultIdempotencyRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
    ttl: Duration,
) : AggregateRepository<SubmissionReceipt>(storageRecords, pseudonymizer, payloadSealer, ttl),
    IdempotencyRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(key: IdempotencyKey): SubmissionReceipt? = findByLogicalId(logicalId(key))

    override fun remember(
        key: IdempotencyKey,
        receipt: SubmissionReceipt,
    ): SubmissionReceipt =
        try {
            create(logicalId(key), receipt)
            receipt
        } catch (_: StaleRecordException) {
            find(key) ?: receipt
        }

    override fun serialize(aggregate: SubmissionReceipt): String =
        json.encodeToString(
            SubmissionReceiptDto(
                notificationId = aggregate.notificationId.toString(),
                status = aggregate.status.value,
                channel = aggregate.channel.value,
                acceptedAt = aggregate.acceptedAt.toString(),
            ),
        )

    override fun deserialize(payload: String): SubmissionReceipt {
        val dto = json.decodeFromString<SubmissionReceiptDto>(payload)
        return SubmissionReceipt(
            notificationId = NotificationId.parseOrNull(dto.notificationId) ?: error("Stored receipt id is not a uuid"),
            status = NotificationStatus.from(dto.status) ?: NotificationStatus.ACCEPTED,
            channel = ChannelId.reconstitute(dto.channel),
            // An unreadable timestamp counts as expired, so a damaged record replays nothing.
            acceptedAt = Instant.parseOrNull(dto.acceptedAt) ?: Instant.DISTANT_PAST,
        )
    }

    private fun logicalId(key: IdempotencyKey): String = sha256Hex(canonicalForm(key)) + LOGICAL_ID_SUFFIX

    /** Length-prefixed so no field can fake a boundary with a colon of its own. */
    private fun canonicalForm(key: IdempotencyKey): String =
        listOf(key.userId.storageLabel(), key.channel.value, key.key)
            .joinToString(":") { "${it.length}:$it" }

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .toHexString()

    @Serializable
    private data class SubmissionReceiptDto(
        val notificationId: String,
        val status: String,
        val channel: String,
        val acceptedAt: String,
    )

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_idempotency"
    }
}
