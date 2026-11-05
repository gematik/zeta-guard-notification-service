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

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/** Runs a write and reports whether it matched a row, which is the DAO's compare-and-set signal. */
internal fun DataSource.changedRow(
    sql: String,
    bind: Parameters.() -> Unit,
): Boolean = connection.use { it.changedRow(sql, bind) }

internal fun Connection.changedRow(
    sql: String,
    bind: Parameters.() -> Unit,
): Boolean =
    prepareStatement(sql).use { statement ->
        Parameters(statement).bind()
        statement.executeUpdate() > 0
    }

internal fun <T> DataSource.query(
    sql: String,
    bind: Parameters.() -> Unit,
    read: (ResultSet) -> T,
): T = connection.use { it.query(sql, bind, read) }

internal fun <T> Connection.query(
    sql: String,
    bind: Parameters.() -> Unit,
    read: (ResultSet) -> T,
): T =
    prepareStatement(sql).use { statement ->
        Parameters(statement).bind()
        statement.executeQuery().use(read)
    }

internal fun <T> DataSource.inTransaction(work: (Connection) -> T): T =
    connection.use { connection ->
        connection.autoCommit = false
        var committed = false
        try {
            work(connection).also {
                connection.commit()
                committed = true
            }
        } finally {
            if (!committed) {
                runCatching { connection.rollback() }
            }
            runCatching { connection.autoCommit = true }
        }
    }

internal fun ResultSet.nullableLong(index: Int): Long? = getLong(index).takeUnless { wasNull() }

internal fun ResultSet.toStorageRecord(): StorageRecord =
    StorageRecord(
        id = getString("id"),
        payload = getBytes("payload").toString(Charsets.UTF_8),
        // Oracle folds an empty string to NULL on the way in, so the column is nullable and reads back
        // as the empty value the no-op sealer wrote.
        keychainId = getString("keychain_id") ?: "",
        transVerifier = getInt("trans_verifier"),
    )

/**
 * Whether this failure is an integrity constraint violation — SQLSTATE class 23.
 *
 * That class covers NOT NULL, check and foreign key violations as well as a primary key collision,
 * and SQLSTATE cannot narrow it any further: only PostgreSQL reports a distinct code (23505 against
 * 23502), while Oracle, MySQL, MariaDB and SQL Server all report a bare 23000. So this answers
 * "some constraint fired", never "which one" — the caller settles that by re-reading the row, which
 * is the same state-based reasoning the rest of the DAO runs on. The JDBC 4 exception subclasses
 * are no help either, because pgjdbc never throws them.
 */
internal fun SQLException.isIntegrityViolation(): Boolean =
    // Capped because some drivers build a self-referential nextException chain, which would
    // otherwise never terminate when no element matches.
    generateSequence(this) { it.nextException ?: it.cause as? SQLException }
        .take(CHAIN_LIMIT)
        .any { it.sqlState?.startsWith(INTEGRITY_CONSTRAINT_VIOLATION) == true }

internal fun SQLException.isTransientConflict(): Boolean =
    isIntegrityViolation() ||
        generateSequence(this) { it.nextException ?: it.cause as? SQLException }
            .take(CHAIN_LIMIT)
            .any {
                it.sqlState?.startsWith(TRANSACTION_ROLLBACK) == true ||
                    it.sqlState == ORACLE_DEADLOCK ||
                    (it.sqlState == GENERAL_ERROR && it.errorCode == MYSQL_LOCK_WAIT_TIMEOUT)
            }

private const val INTEGRITY_CONSTRAINT_VIOLATION = "23"

private const val TRANSACTION_ROLLBACK = "40"

private const val ORACLE_DEADLOCK = "61000"

private const val GENERAL_ERROR = "HY000"

private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205

private const val CHAIN_LIMIT = 100
