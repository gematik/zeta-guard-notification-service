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
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.DeviceChannelConfig
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.YearMonth
import java.util.Base64

class DefaultPushersRepository(
    storageRecords: StorageRecordDAO,
    pseudonymizer: Pseudonymizer,
    payloadSealer: PayloadSealer,
) : AggregateRepository<PushersCollection>(storageRecords, pseudonymizer, payloadSealer),
    PushersRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun findBy(userId: UserId): PushersCollection? =
        findByLogicalId(logicalId(userId))?.takeIf { it.hasRegisteredDevices() }

    override fun update(
        userId: UserId,
        transform: (PushersCollection?) -> PushersCollection?,
    ) {
        update(logicalId(userId), transform)
    }

    override fun markedForRemoval(aggregate: PushersCollection): Boolean = aggregate.markedForRemoval()

    override fun serialize(aggregate: PushersCollection): String =
        json.encodeToString(
            PushersCollectionDto(
                userId = aggregate.userId.value,
                pushers = aggregate.pushers.map { it.toDto() },
                channelConfig =
                    aggregate.channelConfig.mapValues { (_, config) ->
                        config.statuses.entries.associate { (channelId, status) -> channelId.value to status.value }
                    },
            ),
        )

    override fun deserialize(payload: String): PushersCollection {
        val dto = json.decodeFromString<PushersCollectionDto>(payload)
        return PushersCollection.reconstitute(
            userId = UserId.parse(dto.userId),
            pushers = dto.pushers.map { it.toDomain() },
            channelConfig = dto.channelConfig.mapValues { (_, statuses) -> statuses.toDeviceChannelConfig() },
        )
    }

    private fun logicalId(userId: UserId): String = userId.storageLabel() + LOGICAL_ID_SUFFIX

    private fun Map<String, String>.toDeviceChannelConfig(): DeviceChannelConfig =
        DeviceChannelConfig.of(
            entries.associate { (channelId, token) ->
                ChannelId.reconstitute(channelId) to (ChannelStatus.from(token) ?: ChannelStatus.NOT_SET)
            },
        )

    private fun Pusher.toDto(): RegisteredPusherDto =
        RegisteredPusherDto(
            pushKey = pusherId.pushKey,
            appId = pusherId.appId,
            kind = kind,
            appDisplayName = appDisplayName,
            deviceDisplayName = deviceDisplayName,
            lang = lang,
            data = PusherDataDto(url = data.url, format = data.format),
            profileTag = profileTag,
            append = append,
            keyMaterial =
                keyMaterial?.let {
                    PusherKeyMaterialDto(
                        keyIdentifier = it.keyIdentifier,
                        month = it.month.toString(),
                        sharedSecret = Base64.getEncoder().encodeToString(it.sharedSecret),
                        aesKey = Base64.getEncoder().encodeToString(it.aesKey),
                    )
                },
        )

    private fun RegisteredPusherDto.toDomain(): Pusher =
        Pusher.reconstitute(
            pusherId = PusherId(appId = appId, pushKey = pushKey),
            kind = kind,
            appDisplayName = appDisplayName,
            deviceDisplayName = deviceDisplayName,
            lang = lang,
            data = PusherData(url = data.url, format = data.format),
            profileTag = profileTag,
            append = append,
            keyMaterial =
                keyMaterial?.let {
                    PusherKeyMaterial(
                        keyIdentifier = it.keyIdentifier,
                        month = YearMonth.parse(it.month),
                        sharedSecret = Base64.getDecoder().decode(it.sharedSecret),
                        aesKey = Base64.getDecoder().decode(it.aesKey),
                    )
                },
        )

    @Serializable
    private data class PushersCollectionDto(
        val userId: String,
        val pushers: List<RegisteredPusherDto> = emptyList(),
        val channelConfig: Map<String, Map<String, String>> = emptyMap(),
    )

    @Serializable
    private data class RegisteredPusherDto(
        val pushKey: String,
        val appId: String,
        val kind: String,
        val appDisplayName: String,
        val deviceDisplayName: String,
        val lang: String,
        val data: PusherDataDto,
        val profileTag: String? = null,
        val keyMaterial: PusherKeyMaterialDto? = null,
        // Records written before the flag was stored have no such key; they read as the contract's default.
        val append: Boolean = false,
    )

    @Serializable
    private data class PusherDataDto(
        val url: String? = null,
        val format: String? = null,
    )

    @Serializable
    private data class PusherKeyMaterialDto(
        val keyIdentifier: String,
        val month: String,
        val sharedSecret: String,
        val aesKey: String,
    ) {
        override fun toString(): String = "PusherKeyMaterialDto(keyIdentifier=$keyIdentifier, month=$month)"
    }

    private companion object {
        const val LOGICAL_ID_SUFFIX = "_pushers"
    }
}
