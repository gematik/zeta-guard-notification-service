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

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.api.SetupApi
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.PusherPostPutDeleteSchema
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.PushersInner
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.Schema
import de.gematik.zeta.notificationservice.adapter.inbound.security.userId
import de.gematik.zeta.notificationservice.application.usecase.managepusher.ManagePusherUseCase
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherEncryption
import de.gematik.zeta.notificationservice.domain.model.PusherFactory
import de.gematik.zeta.notificationservice.domain.model.PusherId
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jboss.resteasy.reactive.RestResponse
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.PusherData as ApiPusherData

@IfBuildProperty(name = "notification.api.fdv.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
class PusherResource(
    private val managePusherUseCase: ManagePusherUseCase,
    private val pusherFactory: PusherFactory,
    private val securityIdentity: SecurityIdentity,
) : SetupApi {
    override fun getPushers(): RestResponse<Schema> {
        val pushers = managePusherUseCase.get(securityIdentity.userId())
        return RestResponse.ok(Schema(pushers = pushers.map { it.toApi() }))
    }

    override fun postPusher(pusherPostPutDeleteSchema: PusherPostPutDeleteSchema): RestResponse<JsonElement> {
        val userId = securityIdentity.userId()
        val pusherId =
            PusherId(
                appId = pusherPostPutDeleteSchema.appId,
                pushKey = pusherPostPutDeleteSchema.pushkey,
            )
        val kind: String? = pusherPostPutDeleteSchema.kind
        if (kind == null) {
            managePusherUseCase.remove(userId, pusherId)
        } else {
            val pusher =
                pusherFactory.create(
                    pusherId = pusherId,
                    kind = kind,
                    appDisplayName = pusherPostPutDeleteSchema.appDisplayName,
                    deviceDisplayName = pusherPostPutDeleteSchema.deviceDisplayName,
                    lang = pusherPostPutDeleteSchema.lang,
                    data =
                        pusherPostPutDeleteSchema.data?.let {
                            PusherData(url = it.url, format = it.format)
                        },
                    profileTag = pusherPostPutDeleteSchema.profileTag,
                    encryption =
                        pusherPostPutDeleteSchema.encryption?.let {
                            PusherEncryption.create(
                                method = it.method,
                                timeIssCreated = it.timeIssCreated,
                                iss = it.iss,
                                keyIdentifier = it.keyIdentifier,
                            )
                        },
                    append = pusherPostPutDeleteSchema.append ?: false,
                )
            managePusherUseCase.register(userId, pusher)
        }
        return RestResponse.ok(JsonObject(emptyMap()))
    }

    private fun Pusher.toApi(): PushersInner =
        PushersInner(
            pushkey = pusherId.pushKey,
            kind = kind,
            appId = pusherId.appId,
            appDisplayName = appDisplayName,
            deviceDisplayName = deviceDisplayName,
            lang = lang,
            data = ApiPusherData(url = data.url, format = data.format),
            profileTag = profileTag,
            append = append,
        )
}
