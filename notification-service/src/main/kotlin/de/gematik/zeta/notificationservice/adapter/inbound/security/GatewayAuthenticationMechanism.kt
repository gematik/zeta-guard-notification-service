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
package de.gematik.zeta.notificationservice.adapter.inbound.security

import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class GatewayAuthenticationMechanism(
    @param:ConfigProperty(name = "app.auth.user-info-header", defaultValue = "zeta-user-info")
    private val userInfoHeader: String,
) : HttpAuthenticationMechanism {
    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager,
    ): Uni<SecurityIdentity> =
        extractUserInfo(context)?.let {
            identityProviderManager.authenticate(
                TokenAuthenticationRequest(TokenCredential(it, USER_INFO_CREDENTIAL_TYPE)),
            )
        } ?: Uni.createFrom().nullItem()

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> =
        Uni.createFrom().item(ChallengeData(UNAUTHORIZED))

    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> =
        setOf(TokenAuthenticationRequest::class.java)

    private fun extractUserInfo(context: RoutingContext): String? =
        context
            .request()
            .getHeader(userInfoHeader)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val USER_INFO_CREDENTIAL_TYPE = "user-info"
        const val UNAUTHORIZED = 401
    }
}
