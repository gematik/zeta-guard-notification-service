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

import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.time.Instant

class PusherKeyMaterial(
    val keyIdentifier: String,
    val month: YearMonth,
    val sharedSecret: ByteArray,
    val aesKey: ByteArray,
) {
    init {
        require(sharedSecret.size == PushKeyDerivation.KEY_LENGTH) {
            "sharedSecret must be of length ${PushKeyDerivation.KEY_LENGTH}"
        }
        require(aesKey.size == PushKeyDerivation.KEY_LENGTH) {
            "aesKey must be of length ${PushKeyDerivation.KEY_LENGTH}"
        }
    }

    fun ratchetTo(target: YearMonth): PusherKeyMaterial {
        var current = this
        while (current.month < target) {
            val next = current.month.plusMonths(1)
            val derived = PushKeyDerivation.derive(current.sharedSecret, next)
            current = PusherKeyMaterial(keyIdentifier, next, derived.sharedSecret, derived.aesKey)
        }
        return current
    }

    override fun equals(other: Any?): Boolean =
        other is PusherKeyMaterial &&
            keyIdentifier == other.keyIdentifier &&
            month == other.month &&
            sharedSecret.contentEquals(other.sharedSecret) &&
            aesKey.contentEquals(other.aesKey)

    override fun hashCode(): Int = 31 * keyIdentifier.hashCode() + month.hashCode()

    override fun toString(): String = "PusherKeyMaterial(keyIdentifier=$keyIdentifier, month=$month)"

    companion object {
        fun initial(encryption: PusherEncryption): PusherKeyMaterial {
            val iss = encryption.issBytes()
            try {
                val derived = PushKeyDerivation.derive(iss, encryption.month())
                return PusherKeyMaterial(
                    encryption.keyIdentifier,
                    encryption.month(),
                    derived.sharedSecret,
                    derived.aesKey,
                )
            } finally {
                iss.fill(0)
            }
        }

        fun monthOf(instant: Instant): YearMonth =
            YearMonth.from(
                java.time.Instant
                    .ofEpochMilli(instant.toEpochMilliseconds())
                    .atOffset(ZoneOffset.UTC),
            )
    }
}
