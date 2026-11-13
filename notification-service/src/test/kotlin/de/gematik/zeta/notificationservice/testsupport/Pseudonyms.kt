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

import de.gematik.zeta.notificationservice.application.repository.pseudonymization.Pseudonymizer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.storageLabel
import de.gematik.zeta.notificationservice.domain.model.UserId
import java.security.MessageDigest

/** The storage id a new record for [id] gets. */
fun Pseudonymizer.storageIdOf(id: String): String = pseudonymize(id, writeKeychainId())

/**
 * The logical id of a device history: a SHA-256 over the length-prefixed user and pushkey. Spelled out rather than
 * borrowed from the repository, so a change to the derivation, which strands every stored history, fails a test.
 */
fun historyLogicalId(
    userId: UserId,
    pushKey: String,
): String {
    val canonical = listOf(userId.storageLabel(), pushKey).joinToString(":") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).toHexString() + "_history"
}

/** The id versions up to 2.0.1 stored a device history under. */
fun legacyHistoryLogicalId(
    userId: UserId,
    pushKey: String,
): String = "${userId.storageLabel()}/${pushKey}_history"
