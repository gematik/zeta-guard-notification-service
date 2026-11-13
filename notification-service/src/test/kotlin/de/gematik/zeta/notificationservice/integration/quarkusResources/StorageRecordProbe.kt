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
package de.gematik.zeta.notificationservice.integration.quarkusResources

import de.gematik.zeta.notificationservice.application.repository.pseudonymization.storageLabel
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import org.testcontainers.containers.JdbcDatabaseContainer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Direct JDBC view on the `storage_record` table of the test container, so acceptance scenarios can
 * observe retention without waiting out the configured period.
 *
 * Deliberately not a CDI bean: the acceptance scenarios also run as `@QuarkusIntegrationTest`
 * against the packaged app, where injection is unavailable. The probe talks to the container from
 * the test JVM instead, using the host-mapped JDBC url.
 *
 * Scenarios address records by the repositories' *logical* ids, built with the same [storageLabel]
 * the repositories use. With `notification.persistence.sealing.enabled` those ids are pseudonymized
 * before they reach the table, so the probe reproduces the mapping with the pseudonymization keys of
 * [TestKeychain]. Every test keychain carries those keys, whether the in-JVM fake or the simulator behind
 * [HsmSimulatorTestResource] wrapped it, so the probe needs no HSM.
 */
class StorageRecordProbe(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
) {
    /** Backdates the retention deadline of one record, which reads exactly like an elapsed period. */
    fun expireRecord(logicalId: String) {
        connection().use { connection ->
            connection.prepareStatement(EXPIRE_ONE).use { statement ->
                statement.setLong(1, alreadyElapsed())
                statement.setString(2, storageId(logicalId))
                statement.executeUpdate()
            }
        }
    }

    /**
     * Runs the same delete the service's scheduled purge runs, and returns the row count.
     *
     * Deliberately not routed through the service's own purge, which first claims a lease: the
     * probe exists to make retention observable on demand, not to contend for that lease.
     */
    fun purgeExpired(): Long =
        connection().use { connection ->
            connection.prepareStatement(PURGE).use { statement ->
                statement.setLong(1, System.currentTimeMillis())
                statement.executeUpdate().toLong()
            }
        }

    /** Whether the row is physically present, ignoring the retention deadline. */
    fun exists(logicalId: String): Boolean = expiresAt(logicalId) != null

    fun notificationExists(notificationId: String): Boolean = exists(notificationLogicalId(notificationId))

    fun pusherRecordExists(identifier: String): Boolean = exists("${label(identifier)}$PUSHERS_SUFFIX")

    fun expireNotification(notificationId: String) = expireRecord(notificationLogicalId(notificationId))

    fun expireIdempotencyReceipt(
        identifier: String,
        channel: String,
        idempotencyKey: String,
    ) = expireRecord(idempotencyLogicalId(identifier, channel, idempotencyKey))

    fun expireHistory(
        identifier: String,
        pushkey: String,
    ) = expireRecord(historyLogicalId(identifier, pushkey))

    fun historyRecordExists(
        identifier: String,
        pushkey: String,
    ): Boolean = exists(historyLogicalId(identifier, pushkey))

    fun historyRecordExpiresAt(
        identifier: String,
        pushkey: String,
    ): Long? = expiresAt(historyLogicalId(identifier, pushkey))?.value

    /** The row as the storage backend actually holds it, for the at-rest assertions of A_29959. */
    fun rawPusherRecord(identifier: String): RawRecord? = rawRecord("${label(identifier)}$PUSHERS_SUFFIX")

    fun rawNotificationRecord(notificationId: String): RawRecord? = rawRecord(notificationLogicalId(notificationId))

    fun rawHistoryRecord(
        identifier: String,
        pushkey: String,
    ): RawRecord? = rawRecord(historyLogicalId(identifier, pushkey))

    private fun rawRecord(logicalId: String): RawRecord? =
        connection().use { connection ->
            connection.prepareStatement(SELECT_RECORD).use { statement ->
                statement.setString(1, storageId(logicalId))
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        RawRecord(
                            storageId = rows.getString("id"),
                            payload = rows.getBytes("payload"),
                            keychainId = rows.getString("keychain_id") ?: "",
                        )
                    } else {
                        null
                    }
                }
            }
        }

    fun deleteAll() {
        connection().use { connection ->
            connection.prepareStatement(DELETE_ALL).use { statement ->
                statement.execute()
            }
        }
    }

    private fun label(identifier: String): String = UserId.parse(identifier).storageLabel()

    private fun notificationLogicalId(notificationId: String): String = "$notificationId$NOTIFICATION_SUFFIX"

    /** Mirrors `DefaultDeviceMessageHistoryRepository`: a SHA-256 over the length-prefixed user and pushkey. */
    private fun historyLogicalId(
        identifier: String,
        pushkey: String,
    ): String {
        val canonical = listOf(label(identifier), pushkey).joinToString(":") { "${it.length}:$it" }
        return sha256Hex(canonical) + HISTORY_SUFFIX
    }

    /**
     * Mirrors `DefaultIdempotencyRepository`: receipt ids are a SHA-256 over the length-prefixed
     * scoped key and cannot be derived from an API response.
     */
    private fun idempotencyLogicalId(
        identifier: String,
        channel: String,
        idempotencyKey: String,
    ): String {
        val canonical =
            listOf(label(identifier), channel, idempotencyKey)
                .joinToString(":") { "${it.length}:$it" }
        return sha256Hex(canonical) + IDEMPOTENCY_SUFFIX
    }

    private fun expiresAt(logicalId: String): Deadline? =
        connection().use { connection ->
            connection.prepareStatement(SELECT_EXPIRES_AT).use { statement ->
                statement.setString(1, storageId(logicalId))
                statement.executeQuery().use { rows ->
                    if (rows.next()) Deadline(rows.getLong("expires_at").takeUnless { rows.wasNull() }) else null
                }
            }
        }

    // Whichever candidate holds the row: the logical id itself without sealing, otherwise its storage id
    // under each entry of the test keychain, since a rotation scenario may leave it under an older one.
    // Without a row, the id under the newest entry, where a new record would go.
    private fun storageId(logicalId: String): String {
        val candidates = listOf(logicalId) + TestKeychain.DEFAULT_ENTRIES.map { pseudonymize(it, logicalId) }
        return connection().use { connection ->
            connection.prepareStatement(SELECT_EXPIRES_AT).use { statement ->
                candidates.firstOrNull { candidate ->
                    statement.setString(1, candidate)
                    statement.executeQuery().use { it.next() }
                }
            }
        } ?: candidates.last()
    }

    private fun pseudonymize(
        entry: TestKeychain.Entry,
        logicalId: String,
    ): String =
        Mac
            .getInstance(HMAC_ALGORITHM)
            .apply { init(SecretKeySpec(entry.pseudonymKey, HMAC_ALGORITHM)) }
            .doFinal(logicalId.encodeToByteArray())
            .toHexString()

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.encodeToByteArray())
            .toHexString()

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl, username, password)

    class RawRecord(
        val storageId: String,
        val payload: ByteArray,
        val keychainId: String,
    )

    /** Distinguishes "no such row" from "row without a deadline", which a bare `Long?` cannot. */
    private class Deadline(
        val value: Long?,
    )

    companion object {
        // Deliberately the only factory: a probe must be built from the container, never from
        // the app's configured jdbc url. DatabaseTestResource rewrites that one to the
        // container-network host and port whenever the app runs inside that network.
        fun of(container: JdbcDatabaseContainer<*>): StorageRecordProbe =
            // The host-mapped url, not the container-network one handed to the app under test.
            StorageRecordProbe(container.jdbcUrl, container.username, container.password)

        private const val NOTIFICATION_SUFFIX = "_notification"
        private const val PUSHERS_SUFFIX = "_pushers"
        private const val HISTORY_SUFFIX = "_history"
        private const val IDEMPOTENCY_SUFFIX = "_idempotency"

        private const val HMAC_ALGORITHM = "HmacSHA256"

        /** A deadline comfortably in the past, whatever the engine's clock is doing. */
        private fun alreadyElapsed(): Long = System.currentTimeMillis() - ONE_MINUTE_MILLIS

        private const val ONE_MINUTE_MILLIS = 60_000L

        private const val EXPIRE_ONE = "UPDATE storage_record SET expires_at = ? WHERE id = ?"

        private const val PURGE =
            "DELETE FROM storage_record WHERE expires_at IS NOT NULL AND expires_at <= ?"

        private const val SELECT_EXPIRES_AT = "SELECT expires_at FROM storage_record WHERE id = ?"
        private const val SELECT_RECORD = "SELECT id, payload, keychain_id FROM storage_record WHERE id = ?"
        private const val DELETE_ALL = "DELETE FROM storage_record"
    }
}
