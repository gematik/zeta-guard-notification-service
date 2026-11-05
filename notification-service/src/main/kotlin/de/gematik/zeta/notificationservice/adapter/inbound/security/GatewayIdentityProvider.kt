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

import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.IdentityProvider
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@ApplicationScoped
class GatewayIdentityProvider : IdentityProvider<TokenAuthenticationRequest> {
    override fun getRequestType(): Class<TokenAuthenticationRequest> = TokenAuthenticationRequest::class.java

    override fun authenticate(
        request: TokenAuthenticationRequest,
        context: AuthenticationRequestContext,
    ): Uni<SecurityIdentity> =
        context.runBlocking {
            identifierFrom(request.token.token)?.let { identifier ->
                QuarkusSecurityIdentity
                    .builder()
                    .setPrincipal(QuarkusPrincipal(identifier))
                    .addCredential(request.token)
                    .build()
            } ?: ANONYMOUS
        }

    private fun identifierFrom(userInfo: String): String? =
        try {
            val decoded = base64Url.decode(userInfo).decodeToString()
            json.decodeFromString<ZetaUserInfo>(decoded).identifier.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class ZetaUserInfo(
        val identifier: String,
        @SerialName("professionOID") val professionOid: String? = null,
        val commonName: String? = null,
        val organizationName: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val base64Url: Base64.Decoder = Base64.getUrlDecoder()

        val ANONYMOUS: SecurityIdentity =
            QuarkusSecurityIdentity
                .builder()
                .setAnonymous(true)
                .build()
    }
}
