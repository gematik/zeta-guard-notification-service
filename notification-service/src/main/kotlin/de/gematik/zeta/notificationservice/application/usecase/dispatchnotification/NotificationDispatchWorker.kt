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
package de.gematik.zeta.notificationservice.application.usecase.dispatchnotification

import de.gematik.zeta.notificationservice.application.port.outbound.NotificationDispatchQueue
import de.gematik.zeta.notificationservice.domain.error.redacted
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@Startup
@ApplicationScoped
class NotificationDispatchWorker(
    private val dispatchQueue: NotificationDispatchQueue,
    private val dispatchNotification: DispatchNotificationUseCase,
    dispatcher: CoroutineDispatcher,
    @param:ConfigProperty(name = "notification.dispatch.concurrency", defaultValue = "4")
    private val concurrency: Int,
) {
    private val logger = Logger.getLogger(NotificationDispatchWorker::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val consumers = concurrency.coerceAtLeast(1)
    private lateinit var jobs: List<Job>

    @PostConstruct
    fun start() {
        jobs = List(consumers) { scope.launch { runConsumer() } }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runConsumer() {
        while (currentCoroutineContext().isActive) {
            try {
                dispatchQueue.consume { pendingDispatch ->
                    dispatchNotification.dispatch(pendingDispatch)
                }
                return
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.error("Dispatch consumer crashed, restarting in ${RESTART_DELAY_MILLIS}ms", t.redacted())
                delay(RESTART_DELAY_MILLIS)
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
        runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE_MILLIS) { jobs.joinAll() } }
    }

    private companion object {
        const val SHUTDOWN_GRACE_MILLIS = 5_000L
        const val RESTART_DELAY_MILLIS = 1_000L
    }
}
