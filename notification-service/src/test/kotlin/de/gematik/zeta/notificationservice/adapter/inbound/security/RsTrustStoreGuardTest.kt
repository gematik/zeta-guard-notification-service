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

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.quarkus.tls.BaseTlsConfiguration
import io.quarkus.tls.TlsConfiguration
import io.quarkus.tls.TlsConfigurationRegistry
import java.security.KeyStore
import java.util.Optional

class RsTrustStoreGuardTest :
    DescribeSpec({
        describe("verifyTrustStore") {
            it("refuses to start when HTTPS is on but no trust store is configured") {
                shouldThrow<IllegalStateException> {
                    guard(tls(keyStore = emptyStore(), trustStore = null)).verifyTrustStore()
                }
            }

            it("starts when HTTPS is on and a trust store is configured") {
                shouldNotThrowAny {
                    guard(tls(keyStore = emptyStore(), trustStore = emptyStore())).verifyTrustStore()
                }
            }

            it("starts without a trust store while there is no HTTPS listener") {
                shouldNotThrowAny {
                    guard(tls(keyStore = null, trustStore = null)).verifyTrustStore()
                }
            }

            it("starts without a trust store when the client-certificate check is switched off") {
                shouldNotThrowAny {
                    guard(tls(keyStore = emptyStore(), trustStore = null), clientCertificateRequired = false)
                        .verifyTrustStore()
                }
            }

            it("checks the TLS configuration named by quarkus.http.tls-configuration-name") {
                val registry =
                    registry(
                        default = tls(keyStore = emptyStore(), trustStore = emptyStore()),
                        named = mapOf("rs" to tls(keyStore = emptyStore(), trustStore = null)),
                    )

                shouldThrow<IllegalStateException> {
                    RsTrustStoreGuard(registry, Optional.of("rs"), clientCertificateRequired = true).verifyTrustStore()
                }
            }
        }
    }) {
    private companion object {
        fun guard(
            default: TlsConfiguration,
            clientCertificateRequired: Boolean = true,
        ) = RsTrustStoreGuard(registry(default), Optional.empty(), clientCertificateRequired)

        fun registry(
            default: TlsConfiguration,
            named: Map<String, TlsConfiguration> = emptyMap(),
        ) = object : TlsConfigurationRegistry {
            override fun get(name: String): Optional<TlsConfiguration> = Optional.ofNullable(named[name])

            override fun getDefault(): Optional<TlsConfiguration> = Optional.of(default)

            override fun register(
                name: String,
                configuration: TlsConfiguration,
            ) = Unit
        }

        fun tls(
            keyStore: KeyStore?,
            trustStore: KeyStore?,
        ): TlsConfiguration =
            object : BaseTlsConfiguration() {
                override fun getKeyStore(): KeyStore? = keyStore

                override fun getTrustStore(): KeyStore? = trustStore
            }

        fun emptyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    }
}
