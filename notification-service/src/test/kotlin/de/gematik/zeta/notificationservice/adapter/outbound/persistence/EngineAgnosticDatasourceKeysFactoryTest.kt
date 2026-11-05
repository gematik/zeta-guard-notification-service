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

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.smallrye.config.EnvConfigSource
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfig
import io.smallrye.config.SmallRyeConfigBuilder
import org.eclipse.microprofile.config.spi.ConfigSource
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * The engine-agnostic datasource keys, without booting Quarkus.
 *
 * `SmallRyeConfigBuilder` builds the same interceptor chain the application does, so everything
 * here is a real resolution — including the environment-variable spelling, which is the form every
 * deployment actually uses and the one a name-rewriting implementation would silently break.
 *
 * What this cannot show is Quarkus picking the datasource up from the derived *names*; that is what
 * the per-engine acceptance and storage-contract runners do against a real database. Hence the
 * assertions on [SmallRyeConfig.getPropertyNames] — losing a name there is exactly how the
 * datasource would go missing.
 */
class EngineAgnosticDatasourceKeysFactoryTest :
    DescribeSpec({
        fun config(
            vararg sources: ConfigSource,
            profiles: List<String> = emptyList(),
        ): SmallRyeConfig =
            SmallRyeConfigBuilder()
                .withInterceptorFactories(EngineAgnosticDatasourceKeysFactory())
                .apply { if (profiles.isNotEmpty()) withProfiles(profiles) }
                .withSources(*sources)
                .build()

        fun properties(
            vararg entries: Pair<String, String>,
            ordinal: Int = 250,
        ): ConfigSource = PropertiesConfigSource(mapOf(*entries), "test", ordinal)

        fun flat(url: String): ConfigSource =
            properties(
                "quarkus.datasource.jdbc.url" to url,
                "quarkus.datasource.username" to "notification",
                "quarkus.datasource.password" to "secret",
            )

        describe("engine selection") {
            SupportedDatabase.entries.forEach { engine ->
                it("infers ${engine.dbKind} from a ${engine.jdbcUrlPrefix} url") {
                    val url = "${engine.jdbcUrlPrefix}//db:1234/notification"

                    val config = config(flat(url))

                    config.getConfigValue("quarkus.datasource.${engine.dbKind}.jdbc.url").value shouldBe url
                    config.getConfigValue("quarkus.datasource.${engine.dbKind}.username").value shouldBe "notification"
                    config.getConfigValue("quarkus.datasource.${engine.dbKind}.password").value shouldBe "secret"
                    config.propertyNames shouldContain "quarkus.datasource.${engine.dbKind}.jdbc.url"

                    SupportedDatabase.entries.filter { it != engine }.forEach { other ->
                        config.getConfigValue("quarkus.datasource.${other.dbKind}.jdbc.url").value.shouldBeNull()
                        config.propertyNames shouldNotContain "quarkus.datasource.${other.dbKind}.jdbc.url"
                    }
                }
            }

            it("lets db-kind override what the url implies, which is how MariaDB uses a mysql url") {
                val url = "jdbc:mysql://db:3306/notification"

                val config = config(flat(url), properties("notification.datasource.db-kind" to "mariadb"))

                config.getConfigValue("quarkus.datasource.mariadb.jdbc.url").value shouldBe url
                config.getConfigValue("quarkus.datasource.mysql.jdbc.url").value.shouldBeNull()
            }

            it("accepts the db-kind aliases and casing Quarkus accepts") {
                val url = "jdbc:postgresql://db:5432/notification"

                listOf("PostgreSQL", "pg", "pgsql").forEach { alias ->
                    val config = config(flat(url), properties("notification.datasource.db-kind" to alias))

                    config.getConfigValue("quarkus.datasource.postgresql.jdbc.url").value shouldBe url
                }
            }

            it("maps nothing when a db-kind was set but names no supported engine") {
                // Not even the url is consulted: mistyping the engine must not hand a MariaDB
                // deployment the MySQL driver. ActiveDataSourceProducer reports it at startup.
                val config =
                    config(
                        flat("jdbc:postgresql://db:5432/notification"),
                        properties("notification.datasource.db-kind" to "postgres"),
                    )

                config.getConfigValue("quarkus.datasource.postgresql.jdbc.url").value.shouldBeNull()
            }

            it("maps nothing when the url belongs to no supported engine") {
                val config = config(flat("jdbc:h2:mem:test"))

                SupportedDatabase.entries.forEach {
                    config.getConfigValue("quarkus.datasource.${it.dbKind}.jdbc.url").value.shouldBeNull()
                }
            }

            it("is a no-op when nothing is configured") {
                val config = config(properties("unrelated" to "value"))

                config.getConfigValue("unrelated").value shouldBe "value"
                config.getConfigValue("quarkus.datasource.postgresql.jdbc.url").value.shouldBeNull()
            }

            it("treats a blank url as unset, the way an unused compose variable arrives") {
                val config = config(properties("quarkus.datasource.jdbc.url" to ""))

                config.getConfigValue("quarkus.datasource.postgresql.jdbc.url").value.shouldBeNull()
            }
        }

        describe("environment variables") {
            it("resolves the engine and the values from the env spelling") {
                val env =
                    EnvConfigSource(
                        mapOf(
                            "QUARKUS_DATASOURCE_JDBC_URL" to "jdbc:mariadb://db:3306/notification",
                            "QUARKUS_DATASOURCE_USERNAME" to "notification",
                            "NOTIFICATION_DATASOURCE_DB_KIND" to "mariadb",
                        ),
                        300,
                    )

                val config = config(env)

                config.getConfigValue("quarkus.datasource.mariadb.jdbc.url").value shouldBe
                    "jdbc:mariadb://db:3306/notification"
                config.getConfigValue("quarkus.datasource.mariadb.username").value shouldBe "notification"
                config.propertyNames shouldContain "quarkus.datasource.mariadb.jdbc.url"
            }
        }

        describe("precedence") {
            val named = "quarkus.datasource.postgresql.jdbc.url"

            it("prefers the per-engine key when it comes from the higher-ordinal source") {
                val config =
                    config(
                        flat("jdbc:postgresql://flat:5432/x"),
                        properties(named to "jdbc:postgresql://named:5432/x", ordinal = 300),
                    )

                config.getConfigValue(named).value shouldBe "jdbc:postgresql://named:5432/x"
            }

            it("prefers the engine-agnostic key when it comes from the higher-ordinal source") {
                val config =
                    config(
                        properties(
                            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://flat:5432/x",
                            ordinal = 300,
                        ),
                        properties(named to "jdbc:postgresql://named:5432/x", ordinal = 250),
                    )

                config.getConfigValue(named).value shouldBe "jdbc:postgresql://flat:5432/x"
            }

            it("prefers the per-engine key when both come from the same ordinal") {
                val config =
                    config(
                        properties(
                            "quarkus.datasource.jdbc.url" to "jdbc:postgresql://flat:5432/x",
                            named to "jdbc:postgresql://named:5432/x",
                        ),
                    )

                config.getConfigValue(named).value shouldBe "jdbc:postgresql://named:5432/x"
            }
        }

        describe("profiles") {
            it("reads the keys of the active profile") {
                val config =
                    config(
                        properties(
                            "%test.quarkus.datasource.jdbc.url" to "jdbc:oracle:thin:@db:1521/free",
                            "%test.notification.datasource.db-kind" to "oracle",
                        ),
                        profiles = listOf("test"),
                    )

                config.getConfigValue("quarkus.datasource.oracle.jdbc.url").value shouldBe
                    "jdbc:oracle:thin:@db:1521/free"
            }
        }

        describe("the engines application.yaml declares") {
            val applicationYaml =
                checkNotNull(EngineAgnosticDatasourceKeysFactoryTest::class.java.getResource("/application.yaml"))
                    .readText()

            SupportedDatabase.entries.forEach { engine ->
                it("${engine.dbKind} is a datasource with its own migrations") {
                    // db-kind, datasource name and Flyway location are the same string on purpose;
                    // this is the guard against them drifting apart.
                    applicationYaml shouldContainText "db-kind: ${engine.dbKind}"
                    applicationYaml shouldContainText "locations: db/migration/${engine.dbKind}"
                    EngineAgnosticDatasourceKeysFactoryTest::class.java
                        .getResource("/db/migration/${engine.dbKind}")
                        .shouldNotBeNull()
                }
            }
        }
    })
