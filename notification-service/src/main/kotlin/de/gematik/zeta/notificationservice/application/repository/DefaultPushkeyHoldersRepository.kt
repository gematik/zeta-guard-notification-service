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
import de.gematik.zeta.notificationservice.domain.model.PushkeyHolders
import de.gematik.zeta.notificationservice.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Stores [PushkeyHolders] under a SHA-256 of the pushkey, so the id stays fixed-width even with sealing off;
 * pseudonymized and sealed like every other aggregate.
 */
class DefaultPushkeyHoldersRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
) : AggregateRepository<PushkeyHolders>(storageRecords, pseudonymizer, payloadSealer),
    PushkeyHoldersRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun findBy(pushKey: String): PushkeyHolders? = findByLogicalId(logicalId(pushKey))

    override fun updateHolders(
        pushKey: String,
        transform: (PushkeyHolders?) -> PushkeyHolders?,
    ) {
        update(logicalId(pushKey), transform = transform)
    }

    override fun markedForRemoval(aggregate: PushkeyHolders): Boolean = aggregate.markedForRemoval()

    override fun serialize(aggregate: PushkeyHolders): String =
        json.encodeToString(
            PushkeyHoldersDto(pushKey = aggregate.pushKey, holders = aggregate.holders.map { it.value }),
        )

    override fun deserialize(payload: String): PushkeyHolders {
        val dto = json.decodeFromString<PushkeyHoldersDto>(payload)
        return PushkeyHolders.reconstitute(pushKey = dto.pushKey, holders = dto.holders.map { UserId.parse(it) })
    }

    private fun logicalId(pushKey: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(pushKey.encodeToByteArray())
            .toHexString() + LOGICAL_ID_SUFFIX

    @Serializable
    private data class PushkeyHoldersDto(
        val pushKey: String,
        val holders: List<String> = emptyList(),
    )

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_holders"
    }
}
