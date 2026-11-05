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
package de.gematik.zeta.notificationservice.adapter.outbound.dispatch

import de.gematik.zeta.notificationservice.application.port.outbound.NotificationDispatchQueue
import de.gematik.zeta.notificationservice.domain.error.NotificationBacklogException
import de.gematik.zeta.notificationservice.domain.error.redacted
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class InMemoryDispatchQueue(
    @param:ConfigProperty(name = "notification.dispatch.queue-capacity", defaultValue = "10000")
    capacity: Int,
) : NotificationDispatchQueue {
    private val logger = Logger.getLogger(InMemoryDispatchQueue::class.java)
    private val channel = Channel<PendingDispatch>(capacity)

    override fun enqueue(pendingDispatch: PendingDispatch) {
        if (channel.trySend(pendingDispatch).isFailure) {
            throw NotificationBacklogException()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
        for (pendingDispatch in channel) {
            try {
                handler(pendingDispatch)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.error("Dispatch of notification ${pendingDispatch.notification.id} failed", e.redacted())
            }
        }
    }
}
