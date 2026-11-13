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
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
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

    /**
     * Combines the record still under a legacy logical id with the one under the current id, when both exist.
     * Only a repository that passes a `legacyLogicalId` needs it.
     */
    protected open fun merge(
        legacy: A,
        current: A,
    ): A = current

    /**
     * The aggregate under [logicalId], or under [legacyLogicalId], the id an earlier version stored it under;
     * merged when both exist.
     */
    protected fun findByLogicalId(
        logicalId: String,
        legacyLogicalId: String? = null,
    ): A? {
        // The legacy id first: a write moving its record over would otherwise slip between the two lookups.
        val legacy = legacyLogicalId?.let { storageRecords.findFirst(pseudonymizer.lookupIds(it)) }
        val current = storageRecords.findFirst(pseudonymizer.lookupIds(logicalId))
        val legacyAggregate = legacy?.let { aggregateOf(it.record) }
        val currentAggregate = current?.let { aggregateOf(it.record) }
        return if (legacyAggregate != null && currentAggregate != null) {
            merge(legacyAggregate, currentAggregate)
        } else {
            currentAggregate ?: legacyAggregate
        }
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

    /**
     * Read-modify-write under [logicalId]. A record found only under [legacyLogicalId] is moved to [logicalId] by
     * the write, keeping its retention deadline. One found under both ids is updated under [logicalId] alone: the
     * legacy one keeps its own deadline, and [findByLogicalId] merges the two until it has expired.
     */
    protected fun update(
        logicalId: String,
        legacyLogicalId: String? = null,
        transform: (A?) -> A?,
    ) {
        repeat(MAX_UPDATE_ATTEMPTS) { attempt ->
            if (attemptUpdate(logicalId, legacyLogicalId, transform)) {
                return
            }
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, attempt + 2L))
        }
        throw StaleRecordException()
    }

    private fun attemptUpdate(
        logicalId: String,
        legacyLogicalId: String?,
        transform: (A?) -> A?,
    ): Boolean {
        val legacy = legacyLogicalId?.let { storageRecords.findFirst(pseudonymizer.lookupIds(it)) }
        val found = storageRecords.findFirst(pseudonymizer.lookupIds(logicalId))
        if (legacy != null && found == null) {
            val keychainId = legacy.record.keychainId
            val storageId = pseudonymizer.pseudonymize(logicalId, keychainId)
            return write(aggregateOf(legacy.record), transform, logicalId, legacyLogicalId) { payload ->
                val moved = payloadSealer.seal(storageId, keychainId, payload)
                storageRecords.move(legacy.storageId, storageId, moved, checkNotNull(legacy.record.transVerifier))
            }
        }
        val existingRecord = found?.record
        val keychainId = existingRecord?.keychainId ?: pseudonymizer.writeKeychainId()
        val storageId = found?.storageId ?: pseudonymizer.pseudonymize(logicalId, keychainId)
        return write(existingRecord?.let(::aggregateOf), transform, logicalId, legacyLogicalId) { payload ->
            val recordToSave = payloadSealer.seal(storageId, keychainId, payload)
            storageRecords.save(storageId, recordToSave, existingRecord?.transVerifier, ttl)
        }
    }

    /** False when a concurrent write got there first, so the caller retries. */
    private fun write(
        current: A?,
        transform: (A?) -> A?,
        logicalId: String,
        legacyLogicalId: String?,
        persist: (payload: String) -> Unit,
    ): Boolean {
        val updated = transform(current)
        return when {
            updated == null || updated === current -> {
                true
            }

            markedForRemoval(updated) -> {
                delete(logicalId, legacyLogicalId)
                true
            }

            else -> {
                try {
                    persist(withLogicalId(serialize(updated), logicalId))
                    true
                } catch (_: StaleRecordException) {
                    false
                }
            }
        }
    }

    protected fun delete(
        logicalId: String,
        legacyLogicalId: String? = null,
    ) {
        // The legacy id first, for the same reason the entries go oldest first: deleting the current id first
        // would let a concurrent move put the legacy record back under it.
        legacyLogicalId?.let { pseudonymizer.lookupIds(it).forEach(storageRecords::delete) }
        pseudonymizer.lookupIds(logicalId).forEach(storageRecords::delete)
    }

    private fun aggregateOf(record: StorageRecord): A = deserialize(payloadSealer.open(record))

    private companion object {
        const val MAX_UPDATE_ATTEMPTS = 10
    }
}

private class Found(
    val storageId: String,
    val record: StorageRecord,
)

/** The first of [storageIds] that holds a live record, in the given order. */
private fun StorageRecordDAO.findFirst(storageIds: List<String>): Found? =
    storageIds.firstNotNullOfOrNull { id -> findById(id)?.let { Found(id, it) } }
