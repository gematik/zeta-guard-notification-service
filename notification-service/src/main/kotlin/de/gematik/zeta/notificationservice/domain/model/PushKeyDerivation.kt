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
package de.gematik.zeta.notificationservice.domain.model

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.time.YearMonth

class DerivedPushkeys(
    val sharedSecret: ByteArray,
    val aesKey: ByteArray,
)

object PushKeyDerivation {
    const val KEY_LENGTH = 32

    fun derive(
        ikm: ByteArray,
        month: YearMonth,
    ): DerivedPushkeys {
        val okm = ByteArray(2 * KEY_LENGTH)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, ByteArray(KEY_LENGTH), month.toString().toByteArray()))
            generateBytes(okm, 0, okm.size)
        }
        val keys = DerivedPushkeys(okm.copyOfRange(0, KEY_LENGTH), okm.copyOfRange(KEY_LENGTH, 2 * KEY_LENGTH))
        okm.fill(0)
        return keys
    }
}
