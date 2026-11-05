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
package de.gematik.zeta.notificationservice.application.usecase.querynotificationhistory

import de.gematik.zeta.notificationservice.application.config.HistoryFeature
import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.NotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.domain.error.HistoryDisabledException
import de.gematik.zeta.notificationservice.domain.error.NotificationNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.UserId
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class QueryNotificationHistoryService(
    private val notificationsRepository: NotificationsRepository,
    private val deviceMessageHistoryRepository: DeviceMessageHistoryRepository,
    private val pushersRepository: PushersRepository,
    private val historyFeature: HistoryFeature,
) : QueryNotificationHistoryUseCase {
    override fun messageOf(
        userId: UserId,
        notificationId: String,
    ): HistoryMessage {
        if (!historyFeature.isEnabled) {
            throw HistoryDisabledException()
        }
        val notification =
            NotificationId
                .parseOrNull(notificationId)
                ?.let { notificationsRepository.findBy(it) }
                ?.takeIf { it.isOwnedBy(userId) }
                ?: throw NotificationNotFoundException()
        return HistoryMessage(notification.id, notification.payload, notification.acceptedAt)
    }

    override fun messagesOf(
        userId: UserId,
        pushKey: String,
    ): List<HistoryMessage> {
        if (!historyFeature.isEnabled) {
            throw HistoryDisabledException()
        }
        pushersRepository.findBy(userId)?.channelConfigOf(pushKey) ?: throw PusherNotFoundException()
        return deviceMessageHistoryRepository.findBy(userId, pushKey)?.messages.orEmpty()
    }
}
