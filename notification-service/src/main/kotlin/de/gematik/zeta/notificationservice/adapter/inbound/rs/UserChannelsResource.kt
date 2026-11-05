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

import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.ActiveChannelsResponse
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.Channel
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.UserIdType
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.UserIdentifier
import de.gematik.zeta.notificationservice.application.usecase.queryuserchannels.QueryUserChannelsUseCase
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.UserChannels
import io.quarkus.arc.properties.IfBuildProperty
import io.smallrye.faulttolerance.api.RateLimit
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse
import de.gematik.zeta.notificationservice.adapter.inbound.rs.generated.model.ChannelStatus as ChannelStatusDto

@IfBuildProperty(name = "notification.api.rs.enabled", stringValue = "true", enableIfMissing = true)
@Path("/users/{userId}/channels")
@ApplicationScoped
class UserChannelsResource(
    private val queryUserChannelsUseCase: QueryUserChannelsUseCase,
) {
    @GET
    @Produces("application/json")
    @RateLimit
    fun getActiveChannels(
        @PathParam("userId") userId: String,
        @QueryParam("id_type") idType: String?,
        @QueryParam("include_inactive") @DefaultValue("false") includeInactive: Boolean,
    ): RestResponse<ActiveChannelsResponse> {
        val declaredIdType = idType.toIdType()
        val userChannels = queryUserChannelsUseCase.channelsOf(parseUserId(userId, declaredIdType), includeInactive)
        return RestResponse.ok(userChannels.toApi(declaredIdType))
    }

    private fun String?.toIdType(): UserIdType {
        requireNotNull(this) { "The parameter 'id_type' is required." }
        return requireNotNull(UserIdType.entries.firstOrNull { it.value == this }) {
            "The parameter 'id_type' must be one of: " + UserIdType.entries.joinToString(", ") { it.value }
        }
    }

    private fun UserChannels.toApi(idType: UserIdType): ActiveChannelsResponse =
        ActiveChannelsResponse(
            user = UserIdentifier(idType = idType, value = userId.value),
            hasRegisteredDevices = registeredDeviceCount > 0,
            registeredDeviceCount = registeredDeviceCount,
            channels = channels.map { (id, status) -> Channel(id = id.value, status = status.toApi()) },
        )

    private fun ChannelStatus.toApi(): ChannelStatusDto =
        when (this) {
            ChannelStatus.ENABLED -> ChannelStatusDto.enabled
            ChannelStatus.DISABLED -> ChannelStatusDto.disabled
            ChannelStatus.NOT_SET -> ChannelStatusDto.not_set
        }
}
