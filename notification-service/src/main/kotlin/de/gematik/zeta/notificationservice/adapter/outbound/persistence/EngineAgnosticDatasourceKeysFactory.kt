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

import io.quarkus.runtime.annotations.StaticInitSafe
import io.smallrye.config.ConfigSourceInterceptor
import io.smallrye.config.ConfigSourceInterceptorContext
import io.smallrye.config.ConfigSourceInterceptorFactory
import io.smallrye.config.ConfigValue
import io.smallrye.config.FallbackConfigSourceInterceptor

/**
 * Lets a deployment configure its database the way it would configure a single-engine service —
 * `quarkus.datasource.jdbc.url`, `.username`, `.password` — and routes those keys to the named
 * datasource of the engine it picked.
 *
 * `quarkus.datasource.db-kind` is fixed at build time, so one image that serves five engines has to
 * declare five *named* datasources (see `application.yaml`), and Quarkus reads their connection
 * settings under `quarkus.datasource.<db-kind>.*`. Spelling the engine into every key is a poor
 * deal for the operator: the engine ends up repeated three times, and moving a deployment to
 * another engine means renaming variables rather than changing values. This interceptor takes the
 * engine name out of the keys and puts it in one place, `notification.datasource.db-kind` — or
 * infers it from the JDBC url, which already carries it.
 *
 * The named form keeps working and keeps precedence: [FallbackConfigSourceInterceptor] resolves the
 * named key first and only falls back to the engine-agnostic one, and when both are set it prefers
 * whichever comes from the higher-ordinal config source. An interceptor is used rather than a
 * `ConfigSourceFactory` for exactly that reason — a synthesized config source would have to carry
 * one fixed ordinal, which loses where the value came from and would let a named key in
 * `application.yaml` beat an engine-agnostic one from the environment.
 *
 * It deliberately neither throws nor logs. A `Config` is built during augmentation, at static init,
 * at runtime init and inside the test JVM of an `@QuarkusIntegrationTest`, so a complaint raised
 * here is a complaint raised up to four times, long before logging is configured. When the engine
 * cannot be determined nothing is mapped, no datasource ends up with a url, and
 * [ActiveDataSourceProducer] — which runs once, at startup, and knows what the operator set — fails
 * the boot with the explanation.
 */
@StaticInitSafe
class EngineAgnosticDatasourceKeysFactory : ConfigSourceInterceptorFactory {
    override fun getInterceptor(context: ConfigSourceInterceptorContext): ConfigSourceInterceptor {
        // A db-kind that was set but names no supported engine deliberately does *not* fall back to
        // the url: a deployment that says `mariadb` and mistypes it must not be handed the MySQL
        // driver because its url happens to read `jdbc:mysql:`. Nothing is mapped, no datasource
        // becomes active, and ActiveDataSourceProducer names the typo while failing the boot.
        val configuredKind = context.lookup(DB_KIND)
        val engine =
            when {
                configuredKind != null -> SupportedDatabase.byDbKind(configuredKind)
                else -> SupportedDatabase.inferredFrom(context.lookup(FLAT_URL))
            } ?: return PassThroughInterceptor()

        return EngineAgnosticDatasourceKeys(engine.dbKind)
    }

    // Blank counts as unset: `docker-compose.yaml` and Helm values both pass an unused variable
    // through as an empty string, and Quarkus treats an empty jdbc.url as "not configured" too.
    private fun ConfigSourceInterceptorContext.lookup(name: String): String? =
        proceed(name)?.value?.takeUnless(String::isBlank)

    internal companion object {
        const val DB_KIND = "notification.datasource.db-kind"
        const val FLAT_URL = "quarkus.datasource.jdbc.url"
        const val FLAT_USERNAME = "quarkus.datasource.username"
        const val FLAT_PASSWORD = "quarkus.datasource.password"
    }
}

/**
 * Maps the three engine-agnostic datasource keys onto one engine's named datasource.
 *
 * Only [ConfigSourceInterceptor.iterateNames] needs writing: SmallRye builds the datasource map
 * from the *names* it can see, so a value alone is not enough — the named key has to appear in the
 * iteration or the datasource is never picked up. The library's own mapping interceptors iterate
 * names in the opposite direction, which is why this cannot be a plain
 * [FallbackConfigSourceInterceptor].
 */
private class EngineAgnosticDatasourceKeys(
    private val fallbacks: Map<String, String>,
) : FallbackConfigSourceInterceptor(fallbacks) {
    constructor(dbKind: String) : this(
        mapOf(
            "quarkus.datasource.$dbKind.jdbc.url" to EngineAgnosticDatasourceKeysFactory.FLAT_URL,
            "quarkus.datasource.$dbKind.username" to EngineAgnosticDatasourceKeysFactory.FLAT_USERNAME,
            "quarkus.datasource.$dbKind.password" to EngineAgnosticDatasourceKeysFactory.FLAT_PASSWORD,
        ),
    )

    // The names are derived by asking for the engine-agnostic keys rather than by rewriting the
    // names the sources report: an environment variable is reported as QUARKUS_DATASOURCE_JDBC_URL,
    // and turning that back into a dotted name cannot tell a dot from a dash. A lookup has no such
    // problem - the env source matches both spellings itself.
    override fun iterateNames(context: ConfigSourceInterceptorContext): Iterator<String> {
        val derived = fallbacks.filterValues { context.proceed(it)?.value != null }.keys
        return (context.iterateNames().asSequence() + derived).iterator()
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A no-op interceptor. [ConfigSourceInterceptor.EMPTY] cannot serve: it ends the chain. */
private class PassThroughInterceptor : ConfigSourceInterceptor {
    override fun getValue(
        context: ConfigSourceInterceptorContext,
        name: String,
    ): ConfigValue? = context.proceed(name)

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
