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
package de.gematik.zeta.notificationservice.adapter.outbound.persistence

import io.quarkus.runtime.Startup
import io.smallrye.config.SmallRyeConfig
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@Startup
@ApplicationScoped
class HsmProxyTransportGuard(
    private val config: SmallRyeConfig,
    @param:ConfigProperty(name = "notification.persistence.sealing.enabled")
    private val sealingEnabled: Boolean,
    @param:ConfigProperty(name = "hsm-proxy.plain-text-accepted", defaultValue = "false")
    private val plainTextAccepted: Boolean,
) {
    private val logger = Logger.getLogger(HsmProxyTransportGuard::class.java)

    @PostConstruct
    fun verifyTransport() {
        if (!sealingEnabled) {
            return
        }
        val profiles = config.profiles
        if (profiles.none { it in DEVELOPMENT_PROFILES } && !plainTextAccepted) {
            throw IllegalStateException(
                "Payload sealing is enabled, but the HSM proxy client from java-hsm-proxy-provider " +
                    "speaks plain-text gRPC only (its mTLS support for A_28830 is deferred), so the " +
                    "Encrypt/Decrypt calls required by A_29958 would leave the VAU trust boundary " +
                    "unauthenticated (A_29962). Set hsm-proxy.plain-text-accepted=true to accept an " +
                    "unprotected channel, and only where the network itself protects it. " +
                    "Active profiles: $profiles",
            )
        }
        logger.warnf(
            "HSM proxy channel is plain text with sealing enabled (profiles: %s). " +
                "Acceptable for dev and test against the simulator, never for a real HSM.",
            profiles,
        )
    }

    private companion object {
        val DEVELOPMENT_PROFILES = setOf("dev", "test")
    }
}
