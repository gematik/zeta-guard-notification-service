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
package de.gematik.zeta.notificationservice.adapter.inbound.rs

import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.api.NotificationsApi
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.NotificationAccepted
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.NotificationRequest
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.NotificationStatusValue
import de.gematik.zeta.notificationservice.application.usecase.submitnotification.SubmitNotificationCommand
import de.gematik.zeta.notificationservice.application.usecase.submitnotification.SubmitNotificationUseCase
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.SubmissionReceipt
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.faulttolerance.api.RateLimit
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.JsonObject
import org.jboss.resteasy.reactive.RestResponse
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.Priority as PriorityDto

@IfBuildProperty(name = "notification.api.rs.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
class NotificationResource(
    private val submitNotificationUseCase: SubmitNotificationUseCase,
) : NotificationsApi {
    @RateLimit
    override fun submitNotification(
        notificationRequest: NotificationRequest,
        idempotencyKey: String?,
    ): RestResponse<NotificationAccepted> =
        RestResponse.accepted(submitNotificationUseCase.submit(notificationRequest.toCommand(idempotencyKey)).toApi())

    private fun NotificationRequest.toCommand(idempotencyKey: String?): SubmitNotificationCommand =
        SubmitNotificationCommand(
            idempotencyKey = idempotencyKey,
            userId = parseUserId(user.value, user.idType),
            channel = ChannelId.parse(channel),
            prio = prio?.toDomain() ?: Priority.NORMAL,
            payload = JsonObject(payload),
            reference = reference,
        )

    private fun SubmissionReceipt.toApi(): NotificationAccepted =
        NotificationAccepted(
            notificationId = notificationId.value,
            status = status.toApi(),
            channel = channel.value,
            acceptedAt = acceptedAt,
        )

    private fun NotificationStatus.toApi(): NotificationStatusValue =
        when (this) {
            NotificationStatus.ACCEPTED -> NotificationStatusValue.accepted
            NotificationStatus.QUEUED -> NotificationStatusValue.queued
            NotificationStatus.NO_ACTIVE_CHANNEL -> NotificationStatusValue.no_active_channel
        }

    private fun PriorityDto.toDomain(): Priority =
        when (this) {
            PriorityDto.high -> Priority.HIGH
            PriorityDto.normal -> Priority.NORMAL
        }
}
