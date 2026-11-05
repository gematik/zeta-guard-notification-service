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

import io.quarkus.test.common.DevServicesContext
import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager.TestInjector.AnnotatedAndMatchesType
import org.testcontainers.containers.JdbcDatabaseContainer
import java.util.Optional

/**
 * Starts the database engine the test asked for in [WithDatabase] and points the application at it
 * with the same three keys a deployment uses — `quarkus.datasource.jdbc.url`, `.username`,
 * `.password` — naming the engine once, in `notification.datasource.db-kind`.
 *
 * Those keys carry no engine name, so the mapping onto the engine's *named* datasource is done by
 * `EngineAgnosticDatasourceKeysFactory`; giving that one datasource a url is also what activates it,
 * the other four have none and Quarkus deactivates them. Every per-engine runner therefore exercises
 * the real deployment path, on the real engine, rather than approximating it.
 */
class DatabaseTestResource :
    QuarkusTestResourceConfigurableLifecycleManager<WithDatabase>,
    DevServicesContext.ContextAware {
    private var engine = DatabaseEngine.POSTGRES

    // Lazy on purpose: Quarkus constructs the resource before it calls init(), so a container built
    // in a field initialiser would capture the default engine no matter what the annotation says —
    // and would fail silently, by testing PostgreSQL five times over.
    private val container: JdbcDatabaseContainer<*> by lazy { engine.newContainer() }

    private var containerNetworkId = Optional.empty<String>()

    override fun init(annotation: WithDatabase) {
        engine = annotation.engine
    }

    override fun setIntegrationTestContext(context: DevServicesContext) {
        containerNetworkId = context.containerNetworkId()
    }

    override fun start(): MutableMap<String, String> {
        container.start()
        probe = StorageRecordProbe.of(container)

        val jdbcUrl =
            if (containerNetworkId.isPresent) {
                // Replace hostname + port in the provided JDBC URL with the hostname of the Docker
                // container running the database and the listening port.
                fixJdbcUrl(container.jdbcUrl)
            } else {
                container.jdbcUrl
            }

        return mutableMapOf(
            "notification.datasource.db-kind" to engine.datasourceName,
            "quarkus.datasource.jdbc.url" to jdbcUrl,
            "quarkus.datasource.username" to container.username,
            "quarkus.datasource.password" to container.password,
        )
    }

    private fun fixJdbcUrl(jdbcUrl: String): String {
        val listeningPort = container.exposedPorts.first()

        // Part of the JDBC URL to replace
        val hostPort = container.host + ':' + container.getMappedPort(listeningPort)

        // Host/IP on the container network plus the unmapped port
        val networkHostPort = container.currentContainerInfo.config.hostName + ':' + listeningPort

        return jdbcUrl.replace(hostPort, networkHostPort)
    }

    override fun inject(testInjector: QuarkusTestResourceLifecycleManager.TestInjector?) {
        testInjector?.injectIntoFields(
            probe,
            AnnotatedAndMatchesType(InjectStorageRecordProbe::class.java, StorageRecordProbe::class.java),
        )
    }

    override fun stop() {
        container.stop()
        probe = null
    }

    companion object {
        var probe: StorageRecordProbe? = null
            private set
    }
}
