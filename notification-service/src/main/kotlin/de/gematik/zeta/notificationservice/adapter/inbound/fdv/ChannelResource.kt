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

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.api.ChannelApi
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsGetSchema
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsPostSchema
import de.gematik.zeta.notificationservice.adapter.inbound.security.userId
import de.gematik.zeta.notificationservice.application.usecase.managechannel.ManageChannelUseCase
import de.gematik.zeta.notificationservice.domain.model.ChannelConfiguration
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jboss.resteasy.reactive.RestResponse

@IfBuildProperty(name = "notification.api.fdv.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
class ChannelResource(
    private val manageChannelUseCase: ManageChannelUseCase,
    private val securityIdentity: SecurityIdentity,
) : ChannelApi {
    override fun getChannels(): RestResponse<ChannelsGetSchema> {
        val channels =
            manageChannelUseCase.availableChannels().map {
                ChannelsInner(id = it.value, status = ChannelsInner.Status.not_set)
            }
        return RestResponse.ok(ChannelsGetSchema(channels = channels))
    }

    override fun getChannelsOfDevice(pushkey: String): RestResponse<ChannelsGetSchema> {
        val configured = manageChannelUseCase.channelsOfDevice(securityIdentity.userId(), pushkey)
        return RestResponse.ok(ChannelsGetSchema(channels = configured.map { it.key.toApi(it.value) }))
    }

    override fun postChannelsOfDevice(
        pushkey: String,
        channelsPostSchema: ChannelsPostSchema,
    ): RestResponse<JsonElement> {
        manageChannelUseCase.configure(
            userId = securityIdentity.userId(),
            pushKey = pushkey,
            requested = channelsPostSchema.channels.orEmpty().map { it.toDomain() },
        )
        return RestResponse.ok(JsonObject(emptyMap()))
    }

    private fun ChannelId.toApi(status: ChannelStatus): ChannelsInner =
        ChannelsInner(id = value, status = status.toApi())

    private fun ChannelStatus.toApi(): ChannelsInner.Status =
        when (this) {
            ChannelStatus.ENABLED -> ChannelsInner.Status.enabled
            ChannelStatus.DISABLED -> ChannelsInner.Status.disabled
            ChannelStatus.NOT_SET -> ChannelsInner.Status.not_set
        }

    private fun ChannelsInner.toDomain(): ChannelConfiguration =
        ChannelConfiguration(channelId = ChannelId.parse(id), status = status.toDomain())

    private fun ChannelsInner.Status.toDomain(): ChannelStatus =
        when (this) {
            ChannelsInner.Status.enabled -> ChannelStatus.ENABLED
            ChannelsInner.Status.disabled -> ChannelStatus.DISABLED
            ChannelsInner.Status.not_set -> ChannelStatus.NOT_SET
        }
}
