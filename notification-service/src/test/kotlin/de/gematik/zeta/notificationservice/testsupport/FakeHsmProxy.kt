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
package de.gematik.zeta.notificationservice.testsupport

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** The keys of a keychain entry as the HSM proxy's `Encrypt` wraps them, `iv ‖ ct ‖ tag` in [wrapped]. */
data class WrappedKeys(
    val kekId: String,
    val wrapped: ByteArray,
)

@Alternative
@Priority(1)
@ApplicationScoped
class FakeHsmProxy : HsmProxyPort {
    val unwraps = AtomicInteger()

    var wrapKekId: String = KEK_ID

    fun wrapKeys(
        keys: ByteArray,
        aad: ByteArray,
    ): WrappedKeys = WrappedKeys(kekId = wrapKekId, wrapped = pack(wrapKekId, keys, aad))

    override fun unwrapKeys(
        kekId: String,
        wrapped: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        unwraps.incrementAndGet()
        val header = header(kekId, aad)
        if (wrapped.size < header.size || !wrapped.copyOfRange(0, header.size).contentEquals(header)) {
            throw HsmProxyException("HSM proxy Decrypt failed: INTERNAL")
        }
        return wrapped.copyOfRange(header.size, wrapped.size)
    }

    private fun pack(
        kekId: String,
        key: ByteArray,
        aad: ByteArray,
    ): ByteArray = header(kekId, aad) + key

    private fun header(
        kekId: String,
        aad: ByteArray,
    ): ByteArray {
        val kek = kekId.encodeToByteArray()
        return ByteBuffer
            .allocate(2 * Int.SIZE_BYTES + kek.size + aad.size)
            .putInt(kek.size)
            .put(kek)
            .putInt(aad.size)
            .put(aad)
            .array()
    }

    companion object {
        const val KEK_ID = "notification-service-keychain-kek-aesgcm-v1"
    }
}
