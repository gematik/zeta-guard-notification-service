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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Clock

/**
 * How a create classifies a failed insert.
 *
 * [StorageRecordDAOContractTestCollection] covers the storage contract against all five engines,
 * but it cannot express these cases: they turn on the SQLSTATE a driver reports, and the whole point
 * is that the classification must not depend on which engine is underneath. A stubbed data source
 * is the only way to pin a chosen SQLSTATE, so they live here rather than in the contract.
 */
class JdbcStorageRecordDAOTest :
    DescribeSpec({
        val recordId = "record-1"

        fun record(): StorageRecord = StorageRecord(id = recordId, payload = "payload", keychainId = "")

        /** The state re-read's result: no arguments means the row is gone. */
        fun stateOf(
            transVerifier: Int? = null,
            expiresAt: Long? = null,
        ): ResultSet =
            mockk<ResultSet>(relaxed = true).apply {
                every { next() } returns (transVerifier != null)
                every { getInt(1) } returns (transVerifier ?: 0)
                every { getLong(2) } returns (expiresAt ?: 0L)
                every { wasNull() } returns (expiresAt == null)
            }

        fun connectionWith(
            insertFailure: SQLException,
            state: ResultSet,
            onSelect: PreparedStatement.() -> Unit = {},
        ): Pair<DataSource, PreparedStatement> {
            val insert = mockk<PreparedStatement>(relaxed = true)
            every { insert.executeUpdate() } throws insertFailure

            val select = mockk<PreparedStatement>(relaxed = true)
            every { select.executeQuery() } returns state
            select.onSelect()

            val connection = mockk<Connection>(relaxed = true)
            every { connection.prepareStatement(any()) } answers {
                if (firstArg<String>().trimStart().startsWith("INSERT")) insert else select
            }

            val dataSource = mockk<DataSource>(relaxed = true)
            every { dataSource.connection } returns connection
            return dataSource to select
        }

        fun daoOn(dataSource: DataSource): JdbcStorageRecordDAO =
            JdbcStorageRecordDAO(dataSource, java.time.Duration.ofMinutes(5), Clock.System)

        describe("a create whose insert hits a constraint") {
            it("surfaces a violation that is not a primary key collision") {
                // 23502 is Postgres' not_null_violation. On Oracle, MySQL, MariaDB and SQL Server
                // the same failure arrives as a bare 23000, indistinguishable from a collision --
                // so the create tells them apart by re-reading, and finds no row in the way.
                val (dataSource, _) =
                    connectionWith(SQLException("payload must not be null", "23502"), stateOf())

                val thrown =
                    shouldThrow<SQLException> {
                        daoOn(dataSource).save(recordId, record(), expectedTransVerifier = null)
                    }

                // Not a StaleRecordException: that maps to a 503 asking the caller to retry
                // something that can never succeed, and two of its callers swallow it entirely.
                thrown.sqlState shouldBe "23502"
                thrown.message shouldBe "payload must not be null"
            }

            it("reports a collision with a live row as a lost race") {
                val (dataSource, _) =
                    connectionWith(
                        SQLException("duplicate key value violates unique constraint", "23505"),
                        // A row with no deadline is live, so this create genuinely lost.
                        stateOf(transVerifier = 7, expiresAt = null),
                    )

                shouldThrow<StaleRecordException> {
                    daoOn(dataSource).save(recordId, record(), expectedTransVerifier = null)
                }
            }

            it("propagates a failure that is no integrity violation at all without re-reading") {
                val (dataSource, select) =
                    connectionWith(SQLException("connection failure", "08006"), stateOf())

                shouldThrow<SQLException> {
                    daoOn(dataSource).save(recordId, record(), expectedTransVerifier = null)
                }.sqlState shouldBe "08006"

                verify(exactly = 0) { select.executeQuery() }
            }
        }
    })
