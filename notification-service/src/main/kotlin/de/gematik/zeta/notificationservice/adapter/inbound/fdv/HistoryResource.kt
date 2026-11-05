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
package de.gematik.zeta.notificationservice.adapter.inbound.fdv

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.api.HistoryApi
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.Message
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.MessageGetSchema
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.MessagesGetSchema
import de.gematik.zeta.notificationservice.adapter.inbound.security.userId
import de.gematik.zeta.notificationservice.application.usecase.querynotificationhistory.QueryNotificationHistoryUseCase
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.resteasy.reactive.RestResponse

@IfBuildProperty(name = "notification.api.fdv.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
class HistoryResource(
    private val queryNotificationHistoryUseCase: QueryNotificationHistoryUseCase,
    private val securityIdentity: SecurityIdentity,
) : HistoryApi {
    override fun getNotification(notificationId: String): RestResponse<MessageGetSchema> {
        val message = queryNotificationHistoryUseCase.messageOf(securityIdentity.userId(), notificationId)
        return RestResponse.ok(MessageGetSchema(identifier = message.identifier(), payload = message.payload))
    }

    override fun getNotifications(pushkey: String): RestResponse<MessagesGetSchema> {
        val messages = queryNotificationHistoryUseCase.messagesOf(securityIdentity.userId(), pushkey)
        return RestResponse.ok(MessagesGetSchema(messages = messages.map { it.toApi() }))
    }

    private fun HistoryMessage.toApi(): Message = Message(identifier = identifier(), payload = payload)

    private fun HistoryMessage.identifier(): String = notificationId.toString()
}
