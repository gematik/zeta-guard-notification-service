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
package de.gematik.zeta.notificationservice.application.config

import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Turns the injected instance configuration into the domain-typed [ChannelRegistry], keeping
 * MicroProfile Config out of the domain.
 *
 * `notification.channels.allowed` has no default on purpose: an instance without channels would reject
 * every channel operation, and that should surface as a failed boot rather than as blanket 403s in
 * production. [Startup] makes the registry eager, so a malformed channel id fails the boot instead of
 * the first request.
 */
@Startup
@ApplicationScoped
class ChannelRegistryProducer(
    @param:ConfigProperty(name = "notification.channels.allowed") private val allowedChannels: List<String>,
) {
    private val registry: ChannelRegistry = ChannelRegistry.of(allowedChannels)

    /**
     * [Singleton] rather than [ApplicationScoped]: the registry is a final class with a private
     * constructor and therefore not proxyable, so a normal scope would only work through Quarkus'
     * `transform-unproxyable-classes`. A pseudo-scope needs no client proxy at all.
     */
    @Produces
    @Singleton
    fun channelRegistry(): ChannelRegistry = registry
}
