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

import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * The engines the service supports, each mapped to its `db-kind` — which is also the name of its
 * datasource in `application.yaml` and its Flyway location (see `SupportedDatabase` in main).
 *
 * Image tags track the versions Keycloak documents as supported, since the point of running on all
 * five is that the service can share a deployment's existing database.
 *
 * Named in [WithDatabase] by the test that wants it, e.g. `@WithDatabase(ORACLE)`.
 */
enum class DatabaseEngine(
    val datasourceName: String,
) {
    POSTGRES("postgresql") {
        override fun newContainer(): JdbcDatabaseContainer<*> = PostgreSQLContainer("postgres:17")
    },
    MARIADB("mariadb") {
        override fun newContainer(): JdbcDatabaseContainer<*> = MariaDBContainer("mariadb:11.8")
    },
    MYSQL("mysql") {
        override fun newContainer(): JdbcDatabaseContainer<*> = MySQLContainer("mysql:8.4")
    },
    MSSQL("mssql") {
        override fun newContainer(): JdbcDatabaseContainer<*> =
            MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()
    },
    ORACLE("oracle") {
        // slim-faststart, because a plain Oracle image spends minutes initialising a database the
        // suite then truncates anyway. Even so, opening it can take well over the 60 s OracleContainer
        // waits by default on a loaded CI runner, and a timeout there fails the whole test class.
        override fun newContainer(): JdbcDatabaseContainer<*> =
            OracleContainer("gvenzl/oracle-free:slim-faststart").withStartupTimeout(ORACLE_STARTUP_TIMEOUT)
    }, ;

    abstract fun newContainer(): JdbcDatabaseContainer<*>
}

private val ORACLE_STARTUP_TIMEOUT = Duration.ofMinutes(5)
