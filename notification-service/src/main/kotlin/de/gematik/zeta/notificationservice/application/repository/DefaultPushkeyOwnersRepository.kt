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
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushkeyOwners
import de.gematik.zeta.notificationservice.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Stores [PushkeyOwners] under a SHA-256 of app ID and pushkey, so the id stays fixed-width even with sealing off;
 * pseudonymized and sealed like every other aggregate.
 */
class DefaultPushkeyOwnersRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
) : AggregateRepository<PushkeyOwners>(storageRecords, pseudonymizer, payloadSealer),
    PushkeyOwnersRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun findBy(pusherId: PusherId): PushkeyOwners? = findByLogicalId(logicalId(pusherId))

    override fun update(
        pusherId: PusherId,
        transform: (PushkeyOwners?) -> PushkeyOwners?,
    ) {
        update(logicalId(pusherId), transform = transform)
    }

    override fun markedForRemoval(aggregate: PushkeyOwners): Boolean = aggregate.markedForRemoval()

    override fun serialize(aggregate: PushkeyOwners): String =
        json.encodeToString(
            PushkeyOwnersDto(
                appId = aggregate.pusherId.appId,
                pushKey = aggregate.pusherId.pushKey,
                owners = aggregate.owners.map { it.value },
            ),
        )

    override fun deserialize(payload: String): PushkeyOwners {
        val dto = json.decodeFromString<PushkeyOwnersDto>(payload)
        return PushkeyOwners.reconstitute(
            pusherId = PusherId(appId = dto.appId, pushKey = dto.pushKey),
            owners = dto.owners.map { UserId.parse(it) },
        )
    }

    private fun logicalId(pusherId: PusherId): String = sha256Hex(canonicalForm(pusherId)) + LOGICAL_ID_SUFFIX

    /** Length-prefixed so neither part can fake a boundary with a colon of its own. */
    private fun canonicalForm(pusherId: PusherId): String =
        listOf(pusherId.appId, pusherId.pushKey).joinToString(":") { "${it.length}:$it" }

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .toHexString()

    @Serializable
    private data class PushkeyOwnersDto(
        val appId: String,
        val pushKey: String,
        val owners: List<String> = emptyList(),
    )

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_owners"
    }
}
