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

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyPort
import de.gematik.zetaguard.hsmproxy.grpc.HsmProxyGrpcClient
import io.grpc.StatusRuntimeException
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class HsmProxyClient(
    private val grpcClient: HsmProxyGrpcClient,
) : HsmProxyPort {
    @Inject
    constructor(
        @ConfigProperty(name = "hsm-proxy.endpoint") endpoint: String,
    ) : this(HsmProxyGrpcClient(endpoint))

    override fun unwrapKeys(
        kekId: String,
        wrapped: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        check(wrapped.size > IV_LENGTH + TAG_LENGTH) { "Wrapped keys too short: ${wrapped.size} bytes" }
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(IV_LENGTH, wrapped.size - TAG_LENGTH)
        val tag = wrapped.copyOfRange(wrapped.size - TAG_LENGTH, wrapped.size)
        return call("Decrypt") { grpcClient.decrypt(kekId, ciphertext, iv, tag, aad).plaintext.toByteArray() }
    }

    @PreDestroy
    fun close() {
        grpcClient.close()
    }

    private fun <T> call(
        operation: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: StatusRuntimeException) {
            throw HsmProxyException("HSM proxy $operation failed: ${e.status.code}", e)
        }

    private companion object {
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16
    }
}
