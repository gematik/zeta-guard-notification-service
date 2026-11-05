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

import java.security.DrbgParameters
import java.security.SecureRandom

/**
 * The random generator for key material and IVs (GS-A_4367 / GS-A_4368 → BSI TR-03116-1 §3.8/§3.9).
 *
 * Pins the NIST SP 800-90A `DRBG` at 256-bit strength, seeded from the OS entropy source, instead of the
 * platform-dependent default that `SecureRandom()` picks. The mechanism is not a parameter: it is the JDK
 * default from `securerandom.drbg.config`, Hash_DRBG over SHA-256, which `CryptoRandomTest` guards. No
 * prediction resistance — that reseeds from the entropy source (a blocking read on older kernels) under the
 * generator's lock on every draw; the Hash_DRBG state update already gives the backward secrecy of DRG.3.
 */
fun newCryptoRandom(): SecureRandom =
    SecureRandom.getInstance(
        "DRBG",
        DrbgParameters.instantiation(SECURITY_STRENGTH_BITS, DrbgParameters.Capability.RESEED_ONLY, null),
    )

private const val SECURITY_STRENGTH_BITS = 256
