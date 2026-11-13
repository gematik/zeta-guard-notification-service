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
package de.gematik.zeta.notificationservice.application.usecase.submitnotification

import de.gematik.zeta.notificationservice.application.port.outbound.NotificationDispatchQueue
import de.gematik.zeta.notificationservice.application.port.outbound.PushPayloadCodec
import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.IdempotencyRepository
import de.gematik.zeta.notificationservice.application.repository.NotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import de.gematik.zeta.notificationservice.domain.error.UserNotFoundException
import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import de.gematik.zeta.notificationservice.domain.model.Device
import de.gematik.zeta.notificationservice.domain.model.DispatchTarget
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.IdempotencyKey
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.SubmissionReceipt
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import kotlin.time.Clock

@ApplicationScoped
class SubmitNotificationService(
    private val pushersRepository: PushersRepository,
    private val notificationsRepository: NotificationsRepository,
    private val deviceMessageHistoryRepository: DeviceMessageHistoryRepository,
    private val idempotencyRepository: IdempotencyRepository,
    private val dispatchQueue: NotificationDispatchQueue,
    private val channelRegistry: ChannelRegistry,
    private val clock: Clock,
    @param:ConfigProperty(name = "notification.encryption.enabled") private val encryptionEnabled: Boolean,
) : SubmitNotificationUseCase {
    private val logger = Logger.getLogger(SubmitNotificationService::class.java)

    override fun submit(command: SubmitNotificationCommand): SubmissionReceipt {
        channelRegistry.require(command.channel)
        if (encryptionEnabled) {
            PushPayloadCodec.requireEncryptable(command.payload)
        } else if (PushPayloadCodec.serialize(command.payload).size > MAX_PLAIN_PAYLOAD_BYTES) {
            throw PayloadTooLargeException(MAX_PLAIN_PAYLOAD_BYTES)
        }
        val idempotencyKey = command.idempotencyKey
        require(idempotencyKey == null || idempotencyKey.length in 1..IdempotencyKey.MAX_KEY_LENGTH) {
            "Idempotency-Key must be between 1 and ${IdempotencyKey.MAX_KEY_LENGTH} characters"
        }
        require(command.reference == null || command.reference.length <= MAX_REFERENCE_LENGTH) {
            "reference must not exceed $MAX_REFERENCE_LENGTH characters"
        }
        val replayed = idempotencyKey?.let { idempotencyRepository.find(command.scopedKey(it)) }
        return replayed ?: process(command, idempotencyKey)
    }

    private fun process(
        command: SubmitNotificationCommand,
        idempotencyKey: String?,
    ): SubmissionReceipt {
        val pushers =
            pushersRepository.findBy(command.userId) ?: throw UserNotFoundException()
        val enabledPushers = pushers.pushersWithChannelEnabled(command.channel)
        val targets = enabledPushers.mapNotNull { it.toDispatchTarget() }

        val acceptedAt = clock.now()
        val notificationId = NotificationId.random()

        if (targets.isEmpty()) {
            if (enabledPushers.isNotEmpty()) {
                logger.warn("Channel enabled on ${enabledPushers.size} pusher(s), but none has a push URL")
            }
            return SubmissionReceipt(notificationId, NotificationStatus.NO_ACTIVE_CHANNEL, command.channel, acceptedAt)
        }

        val notification =
            Notification(
                id = notificationId,
                userId = command.userId,
                channel = command.channel,
                prio = command.prio,
                payload = command.payload,
                reference = command.reference,
                status = NotificationStatus.ACCEPTED,
                acceptedAt = acceptedAt,
            )
        notificationsRepository.save(notification)
        targets.forEach { target ->
            deviceMessageHistoryRepository.append(
                userId = command.userId,
                pushKey = target.device.pushkey,
                message = HistoryMessage(notificationId, command.payload, acceptedAt),
            )
        }
        dispatchQueue.enqueue(PendingDispatch(notification, targets))

        val receipt = SubmissionReceipt(notificationId, NotificationStatus.ACCEPTED, command.channel, acceptedAt)
        return idempotencyKey?.let { key -> idempotencyRepository.remember(command.scopedKey(key), receipt) }
            ?: receipt
    }

    private fun Pusher.toDispatchTarget(): DispatchTarget? =
        data.url?.let { url ->
            DispatchTarget(
                targetUrl = url,
                device = Device(appId = pusherId.appId, pushkey = pusherId.pushKey, format = data.format),
            )
        }

    private fun SubmitNotificationCommand.scopedKey(key: String): IdempotencyKey =
        IdempotencyKey(userId = userId, channel = channel, key = key)

    private companion object {
        const val MAX_REFERENCE_LENGTH = 128
        const val MAX_PLAIN_PAYLOAD_BYTES = 3072
    }
}
