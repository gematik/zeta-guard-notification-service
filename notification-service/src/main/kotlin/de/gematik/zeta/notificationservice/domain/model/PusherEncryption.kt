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

import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import java.time.YearMonth

data class PusherEncryption private constructor(
    val method: String,
    val timeIssCreated: String,
    val iss: String,
    val keyIdentifier: String,
) {
    fun month(): YearMonth = YearMonth.parse(timeIssCreated)

    fun issBytes(): ByteArray = iss.hexToByteArray()

    override fun toString(): String =
        "PusherEncryption(method=$method, timeIssCreated=$timeIssCreated, keyIdentifier=$keyIdentifier)"

    companion object {
        const val METHOD = "aes-hmac-sha256"
        const val ISS_HEX_LENGTH = 64
        const val MAX_KEY_IDENTIFIER_LENGTH = 128
        private val MONTH = Regex("""\d{4}-\d{2}""")
        private val HEX = Regex("[0-9a-fA-F]{$ISS_HEX_LENGTH}")

        fun create(
            method: String?,
            timeIssCreated: String?,
            iss: String?,
            keyIdentifier: String?,
        ): PusherEncryption {
            val presentMethod = method ?: missing("method")
            val presentTime = timeIssCreated ?: missing("time_iss_created")
            val presentIss = iss ?: missing("iss")
            val presentKeyIdentifier = keyIdentifier ?: missing("key_identifier")
            when {
                presentMethod != METHOD -> {
                    invalid("encryption.method must be $METHOD")
                }

                !MONTH.matches(presentTime) || runCatching { YearMonth.parse(presentTime) }.isFailure -> {
                    invalid("encryption.time_iss_created must have the format yyyy-MM")
                }

                !HEX.matches(presentIss) -> {
                    invalid("encryption.iss must be $ISS_HEX_LENGTH hexadecimal characters")
                }

                presentKeyIdentifier.isBlank() || presentKeyIdentifier.length > MAX_KEY_IDENTIFIER_LENGTH -> {
                    invalid("encryption.key_identifier must be 1 to $MAX_KEY_IDENTIFIER_LENGTH characters")
                }
            }
            return PusherEncryption(
                method = presentMethod,
                timeIssCreated = presentTime,
                iss = presentIss,
                keyIdentifier = presentKeyIdentifier,
            )
        }

        private fun missing(field: String): Nothing =
            invalid("encryption.$field is required when encryption is present")

        private fun invalid(problem: String): Nothing = throw InvalidPusherException(problem)
    }
}
