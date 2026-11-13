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
package de.gematik.zeta.notificationservice.application.config

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.DefaultDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultIdempotencyRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultNotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyOwnersRepository
import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.IdempotencyRepository
import de.gematik.zeta.notificationservice.application.repository.NotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.application.repository.PushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.PushkeyOwnersRepository
import de.gematik.zeta.notificationservice.application.repository.ToggleableDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealer
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealerWithDek
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.HmacPseudonymizer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.Pseudonymizer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import kotlin.time.toKotlinDuration

@ApplicationScoped
class PersistenceStrategyProducer(
    @param:ConfigProperty(name = "notification.persistence.sealing.enabled")
    private val sealingEnabled: Boolean,
    /**
     * Retention period of the notification processing data (A_29988): notifications and idempotency
     * receipts. MicroProfile Config has no converter for kotlin.time.Duration, so the JDK type is injected.
     */
    @param:ConfigProperty(name = "notification.persistence.retention")
    private val retention: java.time.Duration,
    @param:ConfigProperty(name = "notification.history.retention")
    private val historyRetention: java.time.Duration,
) {
    @Produces
    @ApplicationScoped
    fun pseudonymizer(keychain: Keychain): Pseudonymizer =
        if (sealingEnabled) HmacPseudonymizer(keychain) else NoOpPseudonymizer()

    @Produces
    @ApplicationScoped
    fun payloadEncryption(keychain: Keychain): PayloadSealer =
        if (sealingEnabled) PayloadSealerWithDek(keychain) else NoOpPayloadSealer()

    @Produces
    @ApplicationScoped
    fun pushersRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
    ): PushersRepository = DefaultPushersRepository(storageRecords, pseudonymizer, payloadSealer)

    @Produces
    @ApplicationScoped
    fun pushkeyOwnersRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
    ): PushkeyOwnersRepository = DefaultPushkeyOwnersRepository(storageRecords, pseudonymizer, payloadSealer)

    @Produces
    @ApplicationScoped
    fun pushkeyHoldersRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
    ): PushkeyHoldersRepository = DefaultPushkeyHoldersRepository(storageRecords, pseudonymizer, payloadSealer)

    @Produces
    @ApplicationScoped
    fun notificationsRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
    ): NotificationsRepository =
        DefaultNotificationsRepository(
            storageRecords = storageRecords,
            pseudonymizer = pseudonymizer,
            payloadSealer = payloadSealer,
            ttl = retention.toKotlinDuration(),
        )

    @Produces
    @ApplicationScoped
    fun deviceMessageHistoryRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
        historyFeature: HistoryFeature,
    ): DeviceMessageHistoryRepository =
        ToggleableDeviceMessageHistoryRepository(
            feature = historyFeature,
            delegate =
                DefaultDeviceMessageHistoryRepository(
                    storageRecords = storageRecords,
                    pseudonymizer = pseudonymizer,
                    payloadSealer = payloadSealer,
                    ttl = historyRetention.toKotlinDuration(),
                ),
        )

    @Produces
    @ApplicationScoped
    fun idempotencyRepository(
        storageRecords: StorageRecordDAO,
        pseudonymizer: Pseudonymizer,
        payloadSealer: PayloadSealer,
    ): IdempotencyRepository =
        DefaultIdempotencyRepository(
            storageRecords = storageRecords,
            pseudonymizer = pseudonymizer,
            payloadSealer = payloadSealer,
            ttl = retention.toKotlinDuration(),
        )
}
