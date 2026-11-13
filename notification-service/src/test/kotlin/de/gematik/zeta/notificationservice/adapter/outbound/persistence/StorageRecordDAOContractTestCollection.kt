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
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.inject.Inject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours

/**
 * The contract every supported engine must satisfy.
 *
 * The scenarios are deliberately phrased in terms of the [StorageRecordDAO] port rather than SQL, so
 * the same expectations hold on all five engines. Each engine gets one thin runner that names it in
 * `@WithDatabase` — put scenarios here, never in a runner, or they end up copied five times.
 */
abstract class StorageRecordDAOContractTestCollection {
    @Inject
    lateinit var dao: JdbcStorageRecordDAO

    @Inject
    lateinit var dataSource: DataSource

    private val recordId = "record-1"

    /** Mirrors the DAO's own reserved id, which lives in its private companion. */
    private val purgeLeaseId = "__purge_lease"

    private fun record(payload: String = "payload"): StorageRecord =
        StorageRecord(id = recordId, payload = payload, keychainId = "")

    private fun saveSealed(vararg records: Pair<String, String>) {
        records.forEach { (id, entryId) ->
            dao.save(
                id,
                StorageRecord(id, "payload", entryId),
                expectedTransVerifier = null,
                ttl = 24.hours,
            )
        }
    }

    private fun saveUnexpiring(
        id: String,
        payload: String = "payload",
        keychainId: String = "keychain-v1",
    ): Int {
        dao.save(id, StorageRecord(id, payload, keychainId), expectedTransVerifier = null)
        return checkNotNull(dao.findById(id)?.transVerifier)
    }

    private fun moved(
        id: String,
        payload: String = "moved",
    ): StorageRecord = StorageRecord(id, payload, "keychain-v2")

    @BeforeEach
    fun truncate() {
        execute("TRUNCATE TABLE storage_record")
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    /** A deadline in the past reads exactly like an elapsed retention period. */
    private fun expire(id: String) {
        val elapsed = System.currentTimeMillis() - 60_000L
        execute("UPDATE storage_record SET expires_at = $elapsed WHERE id = '$id'")
    }

    /** Excludes the purge lease, which is bookkeeping rather than a stored record. */
    private fun rowCount(): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT count(*) FROM storage_record WHERE id <> '$purgeLeaseId'")
                    .use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
            }
        }

    /**
     * Whether a row is present at all, expired or not.
     *
     * [JdbcStorageRecordDAO.findById] cannot answer this: it filters on the retention deadline, so
     * it reports an expired-but-present lease as absent.
     */
    private fun exists(id: String): Boolean =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM storage_record WHERE id = '$id'").use { rows ->
                    rows.next()
                    rows.getInt(1) > 0
                }
            }
        }

    private fun expiresAtOf(id: String): Long? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT expires_at FROM storage_record WHERE id = '$id'").use { rows ->
                    if (rows.next()) rows.getLong("expires_at").takeUnless { rows.wasNull() } else null
                }
            }
        }

    @Test
    fun `creates a record when none is expected and none exists`() {
        dao.save(recordId, record(), expectedTransVerifier = null)

        val stored = dao.findById(recordId).shouldNotBeNull()
        stored.payload shouldBe "payload"
        stored.transVerifier.shouldNotBeNull()
    }

    @Test
    fun `rejects a create when the record already exists`() {
        dao.save(recordId, record(), expectedTransVerifier = null)

        shouldThrow<StaleRecordException> {
            dao.save(recordId, record(), expectedTransVerifier = null)
        }
    }

    @Test
    fun `keeps the winner's record untouched when a competing create loses`() {
        dao.save(recordId, record(payload = "winner"), expectedTransVerifier = null)
        val stamped = dao.findById(recordId).shouldNotBeNull().transVerifier

        shouldThrow<StaleRecordException> {
            dao.save(recordId, record(payload = "loser"), expectedTransVerifier = null)
        }

        val stored = dao.findById(recordId).shouldNotBeNull()
        stored.payload shouldBe "winner"
        stored.transVerifier shouldBe stamped
    }

    @Test
    fun `accepts a write carrying the verifier that was read`() {
        dao.save(recordId, record(), expectedTransVerifier = null)
        val read = dao.findById(recordId).shouldNotBeNull()

        dao.save(recordId, record(payload = "changed"), expectedTransVerifier = read.transVerifier)

        dao.findById(recordId).shouldNotBeNull().payload shouldBe "changed"
    }

    @Test
    fun `rejects a write carrying a stale verifier`() {
        dao.save(recordId, record(), expectedTransVerifier = null)
        val stale = dao.findById(recordId).shouldNotBeNull().transVerifier
        dao.save(recordId, record(payload = "concurrent"), expectedTransVerifier = stale)

        shouldThrow<StaleRecordException> {
            dao.save(recordId, record(payload = "lost update"), expectedTransVerifier = stale)
        }
        dao.findById(recordId).shouldNotBeNull().payload shouldBe "concurrent"
    }

    @Test
    fun `rejects a write expecting a record that does not exist`() {
        shouldThrow<StaleRecordException> {
            dao.save(recordId, record(), expectedTransVerifier = 42)
        }
    }

    @Test
    fun `stamps a fresh verifier on every write`() {
        dao.save(recordId, record(), expectedTransVerifier = null)
        val first = dao.findById(recordId).shouldNotBeNull().transVerifier

        dao.save(recordId, record(), expectedTransVerifier = first)

        dao.findById(recordId).shouldNotBeNull().transVerifier shouldNotBe first
    }

    @Test
    fun `ignores the verifier the caller put into the payload record`() {
        dao.save(recordId, record().copy(transVerifier = 7), expectedTransVerifier = null)

        dao.findById(recordId).shouldNotBeNull().transVerifier shouldNotBe 7
    }

    @Test
    fun `allows recreating a deleted record`() {
        dao.save(recordId, record(), expectedTransVerifier = null)
        dao.delete(recordId)

        dao.save(recordId, record(payload = "again"), expectedTransVerifier = null)

        dao.findById(recordId).shouldNotBeNull().payload shouldBe "again"
    }

    @Test
    fun `round-trips a payload holding bytes a text column would reject`() {
        val withNulByte = "head" + Char(0) + "tail"

        dao.save(recordId, record(payload = withNulByte), expectedTransVerifier = null)

        dao.findById(recordId).shouldNotBeNull().payload shouldBe withNulByte
    }

    @Test
    fun `stamps a retention deadline only when a ttl is given`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        dao.save("unlimited", record(), expectedTransVerifier = null)

        expiresAtOf(recordId).shouldNotBeNull()
        expiresAtOf("unlimited").shouldBeNull()
    }

    @Test
    fun `hides a record once its retention period has passed`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)

        dao.findById(recordId).shouldBeNull()
    }

    @Test
    fun `lets a create take over an expired record`() {
        dao.save(recordId, record(payload = "stale"), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)

        dao.save(recordId, record(payload = "fresh"), expectedTransVerifier = null, ttl = 24.hours)

        dao.findById(recordId).shouldNotBeNull().payload shouldBe "fresh"
    }

    @Test
    fun `rejects a write against an expired record`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        val verifier = dao.findById(recordId).shouldNotBeNull().transVerifier
        expire(recordId)

        shouldThrow<StaleRecordException> {
            dao.save(recordId, record(payload = "revived"), expectedTransVerifier = verifier)
        }
    }

    @Test
    fun `does not extend the retention period on a write`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        val deadline = expiresAtOf(recordId)
        val verifier = dao.findById(recordId).shouldNotBeNull().transVerifier

        dao.save(recordId, record(payload = "changed"), expectedTransVerifier = verifier, ttl = 24.hours)

        expiresAtOf(recordId) shouldBe deadline
    }

    @Test
    fun `purges expired records and keeps the rest`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        dao.save("live", record(), expectedTransVerifier = null, ttl = 24.hours)
        dao.save("unlimited", record(), expectedTransVerifier = null)
        expire(recordId)

        dao.purgeExpired()

        rowCount() shouldBe 2
        expiresAtOf(recordId).shouldBeNull()
    }

    @Test
    fun `tells two ids apart that differ only in case`() {
        // MySQL, MariaDB and SQL Server all default to case-INSENSITIVE collations. Storage ids
        // derive from user identifiers, so a default collation would merge two users into one.
        dao.save("Record-1", record(payload = "upper"), expectedTransVerifier = null)
        dao.save("record-1", record(payload = "lower"), expectedTransVerifier = null)

        dao.findById("Record-1").shouldNotBeNull().payload shouldBe "upper"
        dao.findById("record-1").shouldNotBeNull().payload shouldBe "lower"
    }

    @Test
    fun `round-trips the empty keychain id the no-op sealer writes`() {
        // Oracle folds an empty string to NULL on the way in, so the column is nullable and reads back
        // as the empty value rather than as null.
        dao.save(recordId, record(), expectedTransVerifier = null)

        val stored = dao.findById(recordId).shouldNotBeNull()
        stored.keychainId shouldBe ""
    }

    @Test
    fun `rejects an id the column cannot hold`() {
        shouldThrow<IllegalArgumentException> {
            dao.save("x".repeat(451), record(), expectedTransVerifier = null)
        }
    }

    // The device history still looks up and deletes its legacy id, which with sealing off is longer than the
    // column for a long pushkey. That must find nothing rather than fail.
    @Test
    fun `finds and deletes nothing under an id the column cannot hold`() {
        val tooLong = "x".repeat(600)

        dao.findById(tooLong).shouldBeNull()
        shouldNotThrow<SQLException> { dao.delete(tooLong) }
    }

    @Test
    fun `purges again on the next cycle because the lease is released`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)
        dao.purgeExpired()
        rowCount() shouldBe 0

        dao.save("later", record(), expectedTransVerifier = null, ttl = 24.hours)
        expire("later")
        dao.purgeExpired()

        // A lease held for the whole interval would fall due together with the next tick, and
        // milliseconds of jitter would decide whether this cycle ran at all.
        rowCount() shouldBe 0
    }

    @Test
    fun `releases the purge lease when the purge succeeds`() {
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)

        dao.purgeExpired()

        exists(purgeLeaseId).shouldBeFalse()
    }

    @Test
    fun `skips the purge while another instance holds a live lease`() {
        dao.save(purgeLeaseId, record(), expectedTransVerifier = null, ttl = 24.hours)
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)

        dao.purgeExpired()

        rowCount() shouldBe 1
        // Someone else's lease, so this instance must neither purge nor release it.
        exists(purgeLeaseId).shouldBeTrue()
    }

    @Test
    fun `takes over the lease of a crashed purge`() {
        dao.save(purgeLeaseId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(purgeLeaseId)
        dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
        expire(recordId)

        dao.purgeExpired()

        rowCount() shouldBe 0
    }

    // Schema migrations. The DDL is the one part of the persistence layer that is not shared
    // between engines — binary and text types, collations and index syntax all differ — so each
    // engine needs its own proof that its migration ran and produced what the DAO writes to.

    @Test
    fun `migrations produce the columns the DAO writes`() {
        val query = "SELECT id, payload, keychain_id, trans_verifier, expires_at FROM storage_record"
        dataSource.connection.use { connection ->
            shouldNotThrow<SQLException> {
                connection.prepareStatement(query).executeQuery()
            }
        }
    }

    @Test
    fun `expires_at holds epoch milliseconds, not a timestamp`() {
        // Guards the portability decision itself: an engine whose DDL drifted back to a timestamp
        // type would still satisfy the query above, but would break every retention comparison.
        val deadline = 4_102_444_800_000L
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO storage_record (id, payload, keychain_id, trans_verifier, expires_at) " +
                        "VALUES ('epoch-millis-probe', ?, 'entry', 1, ?)",
                ).use { statement ->
                    statement.setBytes(1, "payload".toByteArray())
                    statement.setLong(2, deadline)
                    statement.executeUpdate()
                }

            connection
                .prepareStatement("SELECT expires_at FROM storage_record WHERE id = 'epoch-millis-probe'")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong("expires_at") shouldBe deadline
                    }
                }
        }
    }

    @Test
    fun `counts the live sealed records per kek`() {
        saveSealed(
            "record-a" to "keychain-v1",
            "record-b" to "keychain-v2",
            "record-c" to "keychain-v1",
            "record-d" to "keychain-v1",
        )
        expire("record-d")
        dao.save(recordId, record(), expectedTransVerifier = null)

        dao.countLiveRecordsPerKeychainId() shouldBe mapOf("keychain-v1" to 2L, "keychain-v2" to 1L)
    }

    @Test
    fun `has no sealed_key table any more`() {
        val tables =
            dataSource.connection.use { connection ->
                val rows = connection.metaData.getTables(connection.catalog, connection.schema, "%", arrayOf("TABLE"))
                rows.use {
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("TABLE_NAME").lowercase())
                        }
                    }
                }
            }

        tables shouldContain "storage_record"
        tables shouldNotContain "sealed_key"
    }

    @Test
    fun `moves a record to another id in one step and drops the source`() {
        val verifier = saveUnexpiring("0a11")

        dao.move("0a11", "0b22", moved("0b22"), verifier)

        dao.findById("0a11").shouldBeNull()
        val stored = dao.findById("0b22").shouldNotBeNull()
        stored.payload shouldBe "moved"
        stored.keychainId shouldBe "keychain-v2"
        expiresAtOf("0b22").shouldBeNull()
    }

    @Test
    fun `move replaces a live and an expired record under the target id`() {
        val first = saveUnexpiring("0a11")
        saveUnexpiring("0b22", payload = "live copy")
        val second = saveUnexpiring("0c33")
        dao.save("0d44", StorageRecord("0d44", "expired", "keychain-v1"), null, ttl = 24.hours)
        expire("0d44")

        dao.move("0a11", "0b22", moved("0b22"), first)
        dao.move("0c33", "0d44", moved("0d44", payload = "moved too"), second)

        dao.findById("0b22").shouldNotBeNull().payload shouldBe "moved"
        dao.findById("0d44").shouldNotBeNull().payload shouldBe "moved too"
        expiresAtOf("0d44").shouldBeNull()
    }

    @Test
    fun `move changes nothing when the source changed in the meantime`() {
        val verifier = saveUnexpiring("0a11")
        dao.save("0a11", StorageRecord("0a11", "updated", "keychain-v1"), verifier)
        saveUnexpiring("0b22", payload = "target")

        shouldThrow<StaleRecordException> { dao.move("0a11", "0b22", moved("0b22"), verifier) }

        dao.findById("0a11").shouldNotBeNull().payload shouldBe "updated"
        dao.findById("0b22").shouldNotBeNull().payload shouldBe "target"
    }

    @Test
    fun `move keeps the retention period of the source`() {
        dao.save("0c33", StorageRecord("0c33", "payload", "keychain-v1"), null, ttl = 24.hours)
        val verifier = checkNotNull(dao.findById("0c33")?.transVerifier)
        val expiresAt = expiresAtOf("0c33").shouldNotBeNull()

        dao.move("0c33", "0d44", moved("0d44"), verifier)

        exists("0c33").shouldBeFalse()
        dao.findById("0d44").shouldNotBeNull().payload shouldBe "moved"
        expiresAtOf("0d44") shouldBe expiresAt
    }

    @Test
    fun `move refuses a missing source and an expired one`() {
        dao.save("0c33", StorageRecord("0c33", "payload", "keychain-v1"), null, ttl = 24.hours)
        val verifier = checkNotNull(dao.findById("0c33")?.transVerifier)
        expire("0c33")

        shouldThrow<StaleRecordException> { dao.move("0a11", "0b22", moved("0b22"), verifier) }
        shouldThrow<StaleRecordException> { dao.move("0c33", "0d44", moved("0d44"), verifier) }

        exists("0b22").shouldBeFalse()
        exists("0d44").shouldBeFalse()
        exists("0c33").shouldBeTrue()
    }

    @Test
    fun `ordinary writes commit on their own again after a failed move`() {
        shouldThrow<StaleRecordException> { dao.move("0a11", "0b22", moved("0b22"), 1) }

        repeat(5) { saveUnexpiring("0e5$it") }

        rowCount() shouldBe 5
    }

    @Test
    fun `finds the unexpiring records under another keychain id within an id range`() {
        saveUnexpiring("0a11")
        saveUnexpiring("0a22", keychainId = "keychain-v2")
        dao.save("0a33", StorageRecord("0a33", "payload", "keychain-v1"), null, ttl = 24.hours)
        saveUnexpiring("0b44")

        dao.findUnderOtherKeychainId("keychain-v2", fromId = "0a", untilId = "0ag") shouldBe
            mapOf("0a11" to "keychain-v1")
    }

    @Test
    fun `finds the live records with a retention period too when asked to`() {
        saveUnexpiring("0a11")
        saveUnexpiring("0a22", keychainId = "keychain-v2")
        dao.save("0a33", StorageRecord("0a33", "payload", "keychain-v1"), null, ttl = 24.hours)
        dao.save("0a44", StorageRecord("0a44", "payload", "keychain-v1"), null, ttl = 24.hours)
        expire("0a44")
        saveUnexpiring("0b55")

        dao.findUnderOtherKeychainId("keychain-v2", fromId = "0a", untilId = "0ag", withRetention = true) shouldBe
            mapOf("0a11" to "keychain-v1", "0a33" to "keychain-v1")
    }
}
