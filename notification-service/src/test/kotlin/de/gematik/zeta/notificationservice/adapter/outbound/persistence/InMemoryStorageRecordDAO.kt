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
package de.gematik.zeta.notificationservice.adapter.outbound.persistence

import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class InMemoryStorageRecordDAO(
    private val clock: Clock = Clock.System,
) : StorageRecordDAO {
    private data class Entry(
        val record: StorageRecord,
        val expiresAt: Instant?,
    )

    private val records: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()
    private val random = SecureRandom()

    @Synchronized
    override fun findById(recordId: String): StorageRecord? = records[recordId]?.takeIf { it.isLive() }?.record

    @Synchronized
    override fun save(
        recordId: String,
        payload: StorageRecord,
        expectedTransVerifier: Int?,
        ttl: Duration?,
    ) {
        records.compute(recordId) { _, existing ->
            val live = existing?.takeIf { it.isLive() }
            if (live?.record?.transVerifier != expectedTransVerifier) {
                throw StaleRecordException()
            }
            Entry(
                record = payload.copy(transVerifier = freshVerifier(expectedTransVerifier)),
                expiresAt = live?.expiresAt ?: ttl?.let { clock.now() + it },
            )
        }
    }

    @Synchronized
    override fun delete(recordId: String) {
        records.remove(recordId)
    }

    override fun countLiveRecordsPerKeychainId(): Map<String, Long> =
        records.values
            .filter { it.isLive() && it.record.keychainId.isNotEmpty() }
            .groupingBy { it.record.keychainId }
            .eachCount()
            .mapValues { it.value.toLong() }

    @Synchronized
    override fun move(
        fromId: String,
        toId: String,
        record: StorageRecord,
        expectedTransVerifier: Int,
    ) {
        val source =
            records[fromId]
                ?.takeIf { it.isLive() && it.record.transVerifier == expectedTransVerifier }
                ?: throw StaleRecordException()
        records.remove(fromId)
        records[toId] = Entry(record.copy(transVerifier = freshVerifier(null)), expiresAt = source.expiresAt)
    }

    @Synchronized
    override fun findUnderOtherKeychainId(
        keychainId: String,
        fromId: String,
        untilId: String,
        withRetention: Boolean,
    ): Map<String, String> =
        records.entries
            .filter { (id, entry) ->
                (entry.expiresAt == null || (withRetention && entry.isLive())) &&
                    entry.record.keychainId != keychainId &&
                    id >= fromId &&
                    id < untilId
            }.associate { (id, entry) -> id to entry.record.keychainId }

    private fun Entry.isLive(): Boolean = expiresAt == null || expiresAt > clock.now()

    private fun freshVerifier(previous: Int?): Int {
        while (true) {
            val next = random.nextInt()
            if (next != previous) {
                return next
            }
        }
    }
}
