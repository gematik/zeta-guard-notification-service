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
package de.gematik.zeta.notificationservice.application.repository.encryption

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.DrbgParameters

/**
 * Checks defined SecureRandom instance for gemSpec_Krypt compliance
 * GS-A_4367 -> BSI-TR-03116
 * Not strongly defined in ZETA AFOs, but implemented along the way just to be safe.
 */
class CryptoRandomTest :
    DescribeSpec({
        it("is a 256-bit SP 800-90A Hash_DRBG over SHA-256") {
            val random = newCryptoRandom()

            random.algorithm shouldBe "DRBG"
            val parameters = random.parameters.shouldBeInstanceOf<DrbgParameters.Instantiation>()
            parameters.strength shouldBe 256
            parameters.capability shouldBe DrbgParameters.Capability.RESEED_ONLY
            // The mechanism comes from securerandom.drbg.config, not the parameters; toString() names it.
            random.toString() shouldStartWith "Hash_DRBG,SHA-256,256,"
        }
    })
