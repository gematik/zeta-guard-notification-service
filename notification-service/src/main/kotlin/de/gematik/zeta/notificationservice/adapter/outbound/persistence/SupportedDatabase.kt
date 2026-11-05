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

import io.quarkus.datasource.common.runtime.DatabaseKind

/**
 * The database engines this image ships, each with the JDBC url prefix its driver answers to.
 *
 * [dbKind] is one string doing three jobs: it is the Quarkus `db-kind`, the name of the engine's
 * datasource in `application.yaml`, and the Flyway location `db/migration/<dbKind>`. Keeping them
 * spelled the same is what lets a deployment name its engine once.
 *
 * Note that `jdbc:mysql:` infers [MYSQL], not [MARIADB] — MariaDB's own driver answers to
 * `jdbc:mariadb:`. A MariaDB server addressed through a `jdbc:mysql:` url has to say so with
 * `notification.datasource.db-kind`, or it would be handed the MySQL driver and the MySQL
 * migrations.
 */
enum class SupportedDatabase(
    val dbKind: String,
    val jdbcUrlPrefix: String,
) {
    POSTGRESQL(DatabaseKind.POSTGRESQL, "jdbc:postgresql:"),
    MARIADB(DatabaseKind.MARIADB, "jdbc:mariadb:"),
    MYSQL(DatabaseKind.MYSQL, "jdbc:mysql:"),
    MSSQL(DatabaseKind.MSSQL, "jdbc:sqlserver:"),
    ORACLE(DatabaseKind.ORACLE, "jdbc:oracle:"),
    ;

    companion object {
        /** The accepted `db-kind` values, for operator-facing error messages. */
        val dbKinds: String
            get() = entries.joinToString(", ") { it.dbKind }

        /**
         * The engine named by a `db-kind`, or `null` if it names none. Quarkus' own aliases and
         * casing are accepted (`PostgreSQL`, `pg`, `pgsql`), but the two kinds Quarkus knows and
         * this image does not carry a driver for (`db2`, `h2`) resolve to `null` like any typo.
         */
        fun byDbKind(dbKind: String?): SupportedDatabase? {
            val normalized = dbKind?.takeUnless(String::isBlank)?.let(DatabaseKind::normalize) ?: return null
            return entries.firstOrNull { it.dbKind == normalized }
        }

        /** The engine a JDBC url belongs to, or `null` if its prefix names none of them. */
        fun inferredFrom(jdbcUrl: String?): SupportedDatabase? {
            val url = jdbcUrl?.takeUnless(String::isBlank) ?: return null
            return entries.firstOrNull { url.startsWith(it.jdbcUrlPrefix, ignoreCase = true) }
        }
    }
}
