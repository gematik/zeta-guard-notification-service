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
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Stores [StorageRecord]s in the single `storage_record` table.
 *
 * Every statement below is deliberately vendor-neutral ANSI SQL and runs unchanged on all engines
 * the service supports (PostgreSQL, MariaDB, MySQL, SQL Server, Oracle). Only the DDL differs per
 * engine, under `db/migration/<engine>/`. Please keep it that way — a dialect-specific construct
 * here compiles and passes on PostgreSQL while silently breaking one of the other four.
 *
 * Three consequences of that constraint are worth knowing:
 *
 * - `expires_at` holds **epoch milliseconds**, not a timestamp, and the deadline is computed here
 *   rather than by the database. Interval arithmetic has no portable form, and the per-engine
 *   timestamp semantics differ too much to rely on. The application is therefore the single clock
 *   authority; the cost is that instances must agree on the time to within their clock skew, which
 *   is milliseconds against retention periods measured in hours.
 * - There is no conditional upsert. `INSERT ... ON CONFLICT ... WHERE` is PostgreSQL-only, so a
 *   create is an insert, and only when that hits the primary key does it fall back to taking over
 *   an expired row — using the same compare-and-set that ordinary writes use, so two instances
 *   racing to resurrect the same id still cannot both win.
 * - The purge takes a lease through that same create-unless-live primitive instead of a PostgreSQL
 *   advisory lock, which needs no additional SQL and works identically everywhere.
 */
@ApplicationScoped
class JdbcStorageRecordDAO(
    private val dataSource: DataSource,
    /**
     * How long a claimed purge lease stays valid. Since a lease is released as soon as its purge
     * succeeds, this bounds crash recovery only: matched to the purge interval, a holder that dies
     * mid-purge delays the next one by at most a cycle. MicroProfile Config has no converter for
     * kotlin.time.Duration, so the JDK type is injected.
     */
    @param:ConfigProperty(name = "notification.persistence.purge.interval", defaultValue = "5m")
    private val purgeInterval: java.time.Duration,
    val clock: Clock,
) : StorageRecordDAO {
    private val random = SecureRandom()

    private val purgeLease =
        StorageRecord(id = PURGE_LEASE_ID, payload = PURGE_LEASE_PAYLOAD, keychainId = "")

    override fun findById(recordId: String): StorageRecord? =
        dataSource.query(SELECT, {
            string(recordId)
            long(now())
        }) { rows ->
            if (rows.next()) rows.toStorageRecord() else null
        }

    override fun save(
        recordId: String,
        payload: StorageRecord,
        expectedTransVerifier: Int?,
        ttl: Duration?,
    ) {
        require(recordId.length <= MAX_ID_LENGTH) {
            "Storage id is ${recordId.length} characters, the column holds at most $MAX_ID_LENGTH"
        }
        if (expectedTransVerifier == null) {
            create(recordId, payload, ttl)
        } else if (!updateIfVerifierMatches(recordId, payload, expectedTransVerifier)) {
            throw StaleRecordException()
        }
    }

    override fun delete(recordId: String) {
        dataSource.changedRow(DELETE) { string(recordId) }
    }

    override fun countLiveRecordsPerKeychainId(): Map<String, Long> =
        dataSource.query(COUNT_PER_KEYCHAIN_ID, { long(now()) }) { rows ->
            buildMap {
                while (rows.next()) {
                    val entryId = rows.getString(1)
                    if (!entryId.isNullOrEmpty()) {
                        put(entryId, rows.getLong(2))
                    }
                }
            }
        }

    override fun move(
        fromId: String,
        toId: String,
        record: StorageRecord,
        expectedTransVerifier: Int,
    ) {
        require(toId.length <= MAX_ID_LENGTH) {
            "Storage id is ${toId.length} characters, the column holds at most $MAX_ID_LENGTH"
        }
        try {
            dataSource.inTransaction { connection -> connection.move(fromId, toId, record, expectedTransVerifier) }
        } catch (cause: SQLException) {
            throw if (cause.isTransientConflict()) StaleRecordException() else cause
        }
    }

    /**
     * The retention period moves with the record. A source that changed or expired in the meantime is
     * not moved, or a move could bring back what the purge is about to delete; the verifier check of
     * the final delete keeps expires_at as read here. This must run in a transaction.
     */
    private fun Connection.move(
        fromId: String,
        toId: String,
        record: StorageRecord,
        expectedTransVerifier: Int,
    ) {
        val source =
            query(SELECT_STATE, { string(fromId) }) { rows ->
                if (rows.next()) ExistingRow(rows.getInt(1), rows.nullableLong(2)) else null
            }
        val movable =
            source != null &&
                source.transVerifier == expectedTransVerifier &&
                (source.expiresAt == null || source.expiresAt > now())
        if (!movable) {
            throw StaleRecordException()
        }
        changedRow(DELETE) { string(toId) }
        changedRow(INSERT) {
            string(toId)
            payload(record)
            int(freshVerifier(previous = null))
            nullableLong(source.expiresAt)
        }
        val sourceRemoved =
            changedRow(DELETE_MOVED) {
                string(fromId)
                int(expectedTransVerifier)
            }
        if (!sourceRemoved) {
            throw StaleRecordException()
        }
    }

    override fun findUnderOtherKeychainId(
        keychainId: String,
        fromId: String,
        untilId: String,
        withRetention: Boolean,
    ): Map<String, String> =
        dataSource.query(if (withRetention) SELECT_OTHER_KEYCHAIN_ID_LIVE else SELECT_OTHER_KEYCHAIN_ID, {
            string(fromId)
            string(untilId)
            string(keychainId)
            if (withRetention) long(now())
        }) { rows ->
            buildMap {
                while (rows.next()) {
                    put(rows.getString(1), rows.getString(2))
                }
            }
        }

    /**
     * Deletes rows whose retention period has passed.
     *
     * Guarded by a lease so that several instances do not run the delete *concurrently*: claiming
     * it is an ordinary create-unless-live write, which succeeds for exactly one instance and takes
     * over the lease of a crashed one once it expires. The lease is released
     * as soon as the delete succeeds, so with several instances the delete may run once per
     * instance per interval.
     */
    @Scheduled(
        every = "{notification.persistence.purge.interval:5m}",
        concurrentExecution = ConcurrentExecution.SKIP,
    )
    fun purgeExpired() {
        val lease = claimPurgeLease() ?: return
        dataSource.changedRow(PURGE) { long(now()) }
        // Compare-and-set on the verifier this instance stamped, not a plain delete by id: a purge
        // that overran its own lease has already been superseded, and must not release the lease
        // its successor now holds.
        dataSource.changedRow(RELEASE) {
            string(PURGE_LEASE_ID)
            int(lease)
        }
    }

    /** The verifier stamped on the claimed lease, or null when another instance holds it. */
    private fun claimPurgeLease(): Int? =
        try {
            create(PURGE_LEASE_ID, purgeLease, purgeInterval.toKotlinDuration())
        } catch (_: StaleRecordException) {
            null
        }

    /**
     * Creates a record, provided no *live* one holds the id. An expired leftover may be taken over;
     * a live row means someone else got there first.
     *
     * Returns the verifier stamped on the row, which the purge lease needs to release only its own
     * claim.
     */
    private fun create(
        recordId: String,
        record: StorageRecord,
        ttl: Duration?,
    ): Int {
        val expiresAt = ttl?.let { now() + it.inWholeMilliseconds }
        return try {
            insert(recordId, record, expiresAt)
        } catch (cause: SQLException) {
            if (!cause.isIntegrityViolation()) throw cause
            // Some constraint fired, and SQLSTATE cannot say which one; the re-read settles it.
            takeOverExpired(recordId, record, expiresAt) ?: throw StaleRecordException()
        }
    }

    /** Returns the verifier stamped on the taken-over row, or null when a live row holds the id. */
    private fun takeOverExpired(
        recordId: String,
        record: StorageRecord,
        expiresAt: Long?,
    ): Int? {
        // Re-read rather than trusting the insert's failure: the row in the way may be live (in
        // which case this create loses) or an expired leftover (which it may claim). This read is
        // also what tells a primary key collision from any other constraint, since SQLSTATE cannot.
        val existing =
            dataSource.query(SELECT_STATE, { string(recordId) }) { rows ->
                if (rows.next()) ExistingRow(rows.getInt(1), rows.nullableLong(2)) else null
            }

        return when {
            // Purged between the insert and this read. One more attempt, deliberately not a loop:
            // a caller that keeps losing is a stale caller.
            existing == null -> {
                insert(recordId, record, expiresAt)
            }

            existing.expiresAt == null || existing.expiresAt > now() -> {
                null
            }

            else -> {
                val verifier = freshVerifier(previous = existing.transVerifier)
                val taken =
                    dataSource.changedRow(RESURRECT) {
                        payload(record)
                        int(verifier)
                        nullableLong(expiresAt)
                        string(recordId)
                        int(existing.transVerifier)
                        long(now())
                    }
                verifier.takeIf { taken }
            }
        }
    }

    /** Writes the row and returns the verifier it stamped. */
    private fun insert(
        recordId: String,
        record: StorageRecord,
        expiresAt: Long?,
    ): Int {
        val verifier = freshVerifier(previous = null)
        dataSource.changedRow(INSERT) {
            string(recordId)
            payload(record)
            int(verifier)
            nullableLong(expiresAt)
        }
        return verifier
    }

    private fun updateIfVerifierMatches(
        recordId: String,
        record: StorageRecord,
        expected: Int,
    ): Boolean =
        dataSource.changedRow(UPDATE) {
            payload(record)
            int(freshVerifier(previous = expected))
            string(recordId)
            int(expected)
            long(now())
        }

    /**
     * A verifier that always differs from the one it replaces, so a successful write always changes
     * the row. That is what lets `executeUpdate() > 0` stand in for "the WHERE clause matched" on
     * every engine — MySQL's driver can be configured to count changed rather than matched rows.
     */
    private fun freshVerifier(previous: Int?): Int {
        while (true) {
            val next = random.nextInt()
            if (next != previous) {
                return next
            }
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private data class ExistingRow(
        val transVerifier: Int,
        val expiresAt: Long?,
    )

    private companion object {
        /**
         * SQL Server caps a clustered index key — which a PRIMARY KEY is by default — at 900 bytes,
         * so NVARCHAR(450) is the widest id column every engine can hold. Comfortable for real ids,
         * and moot once pseudonymisation makes them fixed-width hashes.
         */
        const val MAX_ID_LENGTH = 450

        /** Cannot collide: every real id ends in _pushers, _owners, _notification, _history or _idempotency. */
        const val PURGE_LEASE_ID = "__purge_lease"
        const val PURGE_LEASE_PAYLOAD = "purge-lease"

        const val LIVE = "(expires_at IS NULL OR expires_at > ?)"

        const val SELECT = """
            SELECT id, payload, keychain_id, trans_verifier
            FROM storage_record
            WHERE id = ? AND $LIVE
        """

        const val SELECT_STATE = "SELECT trans_verifier, expires_at FROM storage_record WHERE id = ?"

        const val INSERT = """
            INSERT INTO storage_record (id, payload, keychain_id, trans_verifier, expires_at)
            VALUES (?, ?, ?, ?, ?)
        """

        const val UPDATE = """
            UPDATE storage_record
            SET payload = ?, keychain_id = ?, trans_verifier = ?
            WHERE id = ? AND trans_verifier = ? AND $LIVE
        """

        const val RESURRECT = """
            UPDATE storage_record
            SET payload = ?, keychain_id = ?, trans_verifier = ?, expires_at = ?
            WHERE id = ? AND trans_verifier = ? AND expires_at IS NOT NULL AND expires_at <= ?
        """

        const val DELETE = "DELETE FROM storage_record WHERE id = ?"

        const val DELETE_MOVED = "DELETE FROM storage_record WHERE id = ? AND trans_verifier = ?"

        const val SELECT_OTHER_KEYCHAIN_ID = """
            SELECT id, keychain_id FROM storage_record
            WHERE id >= ? AND id < ? AND keychain_id <> ? AND expires_at IS NULL
        """

        const val SELECT_OTHER_KEYCHAIN_ID_LIVE = """
            SELECT id, keychain_id FROM storage_record
            WHERE id >= ? AND id < ? AND keychain_id <> ? AND $LIVE
        """

        const val COUNT_PER_KEYCHAIN_ID = """
            SELECT keychain_id, COUNT(*) FROM storage_record WHERE $LIVE GROUP BY keychain_id
        """

        /** Releases a lease only if this instance still holds it; see [purgeExpired]. */
        const val RELEASE = "DELETE FROM storage_record WHERE id = ? AND trans_verifier = ?"

        const val PURGE = "DELETE FROM storage_record WHERE expires_at IS NOT NULL AND expires_at <= ?"
    }
}
