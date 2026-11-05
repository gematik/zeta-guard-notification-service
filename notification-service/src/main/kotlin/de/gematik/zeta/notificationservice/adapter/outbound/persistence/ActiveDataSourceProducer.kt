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

import io.agroal.api.AgroalDataSource
import io.quarkus.arc.InjectableInstance
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.AmbiguousResolutionException
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.inject.UnsatisfiedResolutionException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import javax.sql.DataSource

/**
 * Resolves the one datasource the deployment actually configured.
 *
 * The image carries a driver for every supported engine, because `quarkus.datasource.db-kind` is
 * fixed at build time. Each engine is declared as a *named* datasource in `application.yaml`, and
 * exactly one of them gets a `jdbc.url` — either straight from `quarkus.datasource.<db-kind>.*`, or
 * from the engine-agnostic keys that [EngineAgnosticDatasourceKeysFactory] maps onto the selected
 * engine. Quarkus deactivates the rest by itself. That leaves no default datasource, so a bare
 * `javax.sql.DataSource` injection point would be unsatisfied, and injecting one of the named
 * datasources statically would fail startup whenever that engine is not the active one. This
 * producer bridges the gap: it hands out the active datasource under the default qualifier, so
 * [JdbcStorageRecordDAO] can keep asking for a plain `DataSource`.
 *
 * It deliberately looks up `AgroalDataSource`, not `DataSource`: `@Any` would otherwise also match
 * the bean this method itself produces, and the lookup would resolve into its own result.
 *
 * It is also the single place where a misconfigured database is reported. The config layer that
 * maps the engine-agnostic keys stays silent by design — it runs while the configuration is still
 * being assembled, several times per launch — so the diagnostics live here, where the failure
 * happens once and the reason can be named.
 */
@ApplicationScoped
class ActiveDataSourceProducer(
    @param:ConfigProperty(name = "quarkus.datasource.jdbc.url") private val jdbcUrl: Optional<String>,
    @param:ConfigProperty(name = "notification.datasource.db-kind") private val dbKind: Optional<String>,
) {
    // @Startup so a misconfigured deployment fails while starting rather than on its first
    // request: both "no database configured" and "more than one" are deployment mistakes, and a
    // container that comes up healthy and then rejects traffic is far harder to diagnose.
    @Produces
    @Startup
    @ApplicationScoped
    fun activeDataSource(
        @Any dataSources: InjectableInstance<AgroalDataSource>,
    ): DataSource =
        try {
            dataSources.active
        } catch (cause: UnsatisfiedResolutionException) {
            throw IllegalStateException(noActiveDataSource(), cause)
        } catch (cause: AmbiguousResolutionException) {
            throw IllegalStateException(SEVERAL_ACTIVE_DATASOURCES, cause)
        }

    private fun noActiveDataSource(): String {
        val configuredKind = dbKind.orElse(null)
        val supportedKinds = SupportedDatabase.dbKinds

        return when {
            configuredKind != null && SupportedDatabase.byDbKind(configuredKind) == null -> {
                "NOTIFICATION_DATASOURCE_DB_KIND is set to '$configuredKind', which is not a " +
                    "supported database engine. Use one of $supportedKinds."
            }

            configuredKind == null && jdbcUrl.isPresent && SupportedDatabase.inferredFrom(jdbcUrl.get()) == null -> {
                "The JDBC url in QUARKUS_DATASOURCE_JDBC_URL does not identify a supported database " +
                    "engine. Name the engine with NOTIFICATION_DATASOURCE_DB_KIND ($supportedKinds)."
            }

            configuredKind != null && jdbcUrl.isEmpty -> {
                "NOTIFICATION_DATASOURCE_DB_KIND is set to '$configuredKind' but no JDBC url is " +
                    "configured. Set QUARKUS_DATASOURCE_JDBC_URL together with " +
                    "QUARKUS_DATASOURCE_USERNAME and QUARKUS_DATASOURCE_PASSWORD."
            }

            else -> {
                NO_DATASOURCE_AT_ALL
            }
        }
    }

    private companion object {
        const val SEVERAL_ACTIVE_DATASOURCES =
            "More than one database is configured. Exactly one engine may have a JDBC url — note " +
                "that QUARKUS_DATASOURCE_JDBC_URL counts as one of them, for whichever engine it " +
                "names; unset the others."

        const val NO_DATASOURCE_AT_ALL =
            "No database is configured. Set QUARKUS_DATASOURCE_JDBC_URL together with " +
                "QUARKUS_DATASOURCE_USERNAME and QUARKUS_DATASOURCE_PASSWORD. The engine follows " +
                "from the url; NOTIFICATION_DATASOURCE_DB_KIND overrides it."
    }
}
