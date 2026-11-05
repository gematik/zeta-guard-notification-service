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

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

@Startup
@ApplicationScoped
@IfBuildProperty(name = "notification.api.rs.enabled", stringValue = "true", enableIfMissing = true)
class RsTrustStoreGuard(
    private val tlsConfigurationRegistry: TlsConfigurationRegistry,
    @param:ConfigProperty(name = "quarkus.http.tls-configuration-name")
    private val tlsConfigurationName: Optional<String>,
    @param:ConfigProperty(name = "quarkus.http.auth.permission.rs.enabled", defaultValue = "true")
    private val clientCertificateRequired: Boolean,
) {
    @PostConstruct
    fun verifyTrustStore() {
        if (!clientCertificateRequired) return
        val tls =
            TlsConfiguration
                .from(tlsConfigurationRegistry, tlsConfigurationName)
                .or { tlsConfigurationRegistry.default }
                .orElse(null)
        check(tls?.keyStore == null || tls.trustStore != null) {
            "The resource-server paths require a client certificate (A_29980) and HTTPS is configured, but the " +
                "TLS configuration has no trust store. Set quarkus.tls.trust-store.pem.certs to the resource " +
                "server's certificate or CA, or quarkus.http.auth.permission.rs.enabled=false where mTLS is " +
                "terminated upstream."
        }
    }
}
