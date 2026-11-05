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

import de.gematik.zeta.notificationservice.testsupport.FakeHsmProxy
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.WrappedKeys
import de.gematik.zetaguard.hsmproxy.grpc.HsmProxyGrpcClient
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class HsmSimulatorTestResource : QuarkusTestResourceLifecycleManager {
    private val container =
        GenericContainer(DockerImageName.parse(System.getenv(IMAGE_ENV) ?: DEFAULT_IMAGE))
            .apply { withExposedPorts(GRPC_PORT) }

    override fun start(): MutableMap<String, String> {
        container.start()
        val target = "${container.host}:${container.getMappedPort(GRPC_PORT)}"
        endpoint = target

        return mutableMapOf(
            "hsm-proxy.endpoint" to target,
            "notification.persistence.sealing.keychain" to keychainWrappedBy(target),
        )
    }

    override fun stop() {
        container.stop()
        endpoint = null
    }

    private fun keychainWrappedBy(target: String): String {
        val client = HsmProxyGrpcClient(target)

        fun wrap(
            keys: ByteArray,
            aad: ByteArray,
        ): WrappedKeys {
            val response = client.encrypt(FakeHsmProxy.KEK_ID, keys, aad)
            val blob = response.iv.toByteArray() + response.ciphertext.toByteArray() + response.tag.toByteArray()
            return WrappedKeys(FakeHsmProxy.KEK_ID, blob)
        }

        return try {
            TestKeychain.json(::wrap)
        } finally {
            client.close()
        }
    }

    companion object {
        var endpoint: String? = null
            private set

        private const val IMAGE_ENV = "HSM_SIM_IMAGE"
        private const val DEFAULT_IMAGE =
            "registry.tas-devtools-gitlab.spree.de/zeta/zeta-guard/ngx_pep/hsm_sim:main-c86ca5b5"
        private const val GRPC_PORT = 50051
    }
}
