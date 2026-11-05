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
package de.gematik.zeta.notificationservice.integration.quarkusResources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType

/**
 * Points the service's Push Gateway client at [TlsProbeServer] over HTTPS instead of at [PushGatewayMock].
 *
 * Both the allow-list and the trust anchors have to be exported here: `push-gateway.allowed-base-urls` is
 * read once at startup by `RestPushGatewayClient`, and the probe's certificates are generated per run, so
 * the service can only trust them via `push-gateway.trusted-ca-paths`. That also gives
 * `RestPushGatewayClient.buildAdditiveTrustStore` its only test coverage.
 */
class TlsProbeTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        val started = TlsProbeServer.create().start()
        probe = started
        return mapOf(
            "push-gateway.allowed-base-urls" to started.baseUrl(),
            "push-gateway.trusted-ca-paths" to started.caPemPath(),
        )
    }

    override fun inject(testInjector: QuarkusTestResourceLifecycleManager.TestInjector?) {
        testInjector?.injectIntoFields(
            probe,
            AnnotatedAndMatchesType(InjectTlsProbe::class.java, TlsProbeServer::class.java),
        )
    }

    override fun stop() {
        probe?.stop()
        probe = null
    }

    companion object {
        var probe: TlsProbeServer? = null
            private set
    }
}
