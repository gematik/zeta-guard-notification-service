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

class PushGatewayTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        val started = PushGatewayMock.create().start()
        mock = started
        return mapOf("push-gateway.allowed-base-urls" to allowedBaseUrl())
    }

    override fun inject(testInjector: QuarkusTestResourceLifecycleManager.TestInjector?) {
        testInjector?.injectIntoFields(
            mock,
            AnnotatedAndMatchesType(InjectPushGatewayMock::class.java, PushGatewayMock::class.java),
        )
    }

    override fun stop() {
        mock?.stop()
        mock = null
    }

    companion object {
        var mock: PushGatewayMock? = null
            private set

        fun allowedBaseUrl(): String = checkNotNull(mock).baseUrl() + "/push/v1/"
    }
}
