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

import java.math.BigInteger

/** Whether [keyId] is an older `-v<N>` of the same key id as [currentKeyId]. */
fun isSupersededVersion(
    keyId: String,
    currentKeyId: String,
): Boolean {
    val stored = KEY_VERSION.matchEntire(keyId)?.destructured
    val current = KEY_VERSION.matchEntire(currentKeyId)?.destructured
    if (stored == null || current == null) {
        return false
    }
    val (storedFamily, storedVersion) = stored
    val (currentFamily, currentVersion) = current
    return storedFamily == currentFamily && storedVersion.toBigInteger() < currentVersion.toBigInteger()
}

fun keyVersion(keyId: String): BigInteger? =
    KEY_VERSION
        .matchEntire(keyId)
        ?.groupValues
        ?.get(2)
        ?.toBigInteger()

fun followsKeyIdScheme(keyId: String): Boolean = KEY_ID_SCHEME.matches(keyId)

private val KEY_VERSION = Regex("(.+)-v(0|[1-9]\\d*)")

private val KEY_ID_SCHEME = Regex("([a-z0-9]+-){3,}v\\d+")
