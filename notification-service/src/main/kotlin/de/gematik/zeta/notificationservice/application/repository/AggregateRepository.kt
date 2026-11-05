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
package de.gematik.zeta.notificationservice.application.repository

import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.Pseudonymizer
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration

abstract class AggregateRepository<A>(
    private val storageRecords: StorageRecordDAO,
    private val pseudonymizer: Pseudonymizer,
    private val payloadSealer: PayloadSealer,
    private val ttl: Duration? = null,
) {
    init {
        require(ttl == null || ttl.isPositive()) { "The retention period must be positive, was $ttl" }
    }

    protected abstract fun serialize(aggregate: A): String

    protected abstract fun deserialize(payload: String): A

    protected open fun markedForRemoval(aggregate: A): Boolean = false

    protected fun findByLogicalId(logicalId: String): A? {
        val record = pseudonymizer.lookupIds(logicalId).firstNotNullOfOrNull(storageRecords::findById) ?: return null
        return deserialize(payloadSealer.open(record))
    }

    protected fun create(
        logicalId: String,
        aggregate: A,
    ) {
        val keychainId = pseudonymizer.writeKeychainId()
        val storageId = pseudonymizer.pseudonymize(logicalId, keychainId)
        val record = payloadSealer.seal(storageId, keychainId, withLogicalId(serialize(aggregate), logicalId))
        storageRecords.save(storageId, record, expectedTransVerifier = null, ttl = ttl)
    }

    protected fun update(
        logicalId: String,
        transform: (A?) -> A?,
    ) {
        repeat(MAX_UPDATE_ATTEMPTS) { attempt ->
            if (attemptUpdate(logicalId, transform)) {
                return
            }
            awaitRetry(attempt)
        }
        throw StaleRecordException()
    }

    private fun awaitRetry(attempt: Int) {
        Thread.sleep(ThreadLocalRandom.current().nextLong(1, attempt + 2L))
    }

    private fun attemptUpdate(
        logicalId: String,
        transform: (A?) -> A?,
    ): Boolean {
        val lookupIds = pseudonymizer.lookupIds(logicalId)
        val found = lookupIds.firstNotNullOfOrNull { id -> storageRecords.findById(id)?.let { id to it } }
        val existingRecord = found?.second
        val keychainId = existingRecord?.keychainId ?: pseudonymizer.writeKeychainId()
        val storageId = found?.first ?: pseudonymizer.pseudonymize(logicalId, keychainId)
        val current = existingRecord?.let { deserialize(payloadSealer.open(it)) }
        val updated = transform(current)
        return when {
            updated == null || updated === current -> {
                true
            }

            markedForRemoval(updated) -> {
                delete(logicalId)
                true
            }

            else -> {
                try {
                    val payload = withLogicalId(serialize(updated), logicalId)
                    val recordToSave = payloadSealer.seal(storageId, keychainId, payload)
                    storageRecords.save(storageId, recordToSave, existingRecord?.transVerifier, ttl)
                    true
                } catch (_: StaleRecordException) {
                    false
                }
            }
        }
    }

    protected fun delete(logicalId: String) {
        pseudonymizer.lookupIds(logicalId).forEach(storageRecords::delete)
    }

    private companion object {
        const val MAX_UPDATE_ATTEMPTS = 10
    }
}
