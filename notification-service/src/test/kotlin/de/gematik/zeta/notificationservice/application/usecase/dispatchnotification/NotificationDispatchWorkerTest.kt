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
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.withQuietLogger
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

class NotificationDispatchWorkerTest :
    DescribeSpec({
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()

        afterSpec {
            dispatcher.close()
            executor.shutdownNow()
        }

        fun pendingDispatch(): PendingDispatch =
            PendingDispatch(
                notification =
                    Notification(
                        id = NotificationId.random(),
                        userId = UserId.parse("X110411675"),
                        channel = ChannelId.parse("epa.documents.new"),
                        prio = Priority.NORMAL,
                        payload = JsonObject(emptyMap()),
                        reference = null,
                        status = NotificationStatus.ACCEPTED,
                        acceptedAt = Instant.parse("2026-08-06T10:00:00Z"),
                    ),
                targets = emptyList(),
            )

        val noopUseCase =
            object : DispatchNotificationUseCase {
                override suspend fun dispatch(pendingDispatch: PendingDispatch) = Unit
            }

        describe("start") {
            it("routes consumed items to the dispatch use case") {
                val item = pendingDispatch()
                val dispatched = CountDownLatch(1)
                val queue =
                    object : NotificationDispatchQueue {
                        override fun enqueue(pendingDispatch: PendingDispatch) = Unit

                        override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                            handler(item)
                            awaitCancellation()
                        }
                    }
                val useCase =
                    object : DispatchNotificationUseCase {
                        override suspend fun dispatch(pendingDispatch: PendingDispatch) {
                            if (pendingDispatch === item) dispatched.countDown()
                        }
                    }
                val worker = NotificationDispatchWorker(queue, useCase, dispatcher, concurrency = 1)
                worker.start()

                try {
                    dispatched.await(5, TimeUnit.SECONDS).shouldBeTrue()
                } finally {
                    worker.stop()
                }
            }
        }

        describe("concurrency") {
            it("runs as many consumers as configured") {
                val items = Channel<PendingDispatch>(Channel.UNLIMITED)
                val queue =
                    object : NotificationDispatchQueue {
                        override fun enqueue(pendingDispatch: PendingDispatch) {
                            items.trySend(pendingDispatch)
                        }

                        override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                            for (item in items) {
                                handler(item)
                            }
                        }
                    }
                val bothInFlight = CountDownLatch(2)
                val release = CompletableDeferred<Unit>()
                val useCase =
                    object : DispatchNotificationUseCase {
                        override suspend fun dispatch(pendingDispatch: PendingDispatch) {
                            bothInFlight.countDown()
                            release.await()
                        }
                    }
                val worker = NotificationDispatchWorker(queue, useCase, dispatcher, concurrency = 2)
                worker.start()

                try {
                    queue.enqueue(pendingDispatch())
                    queue.enqueue(pendingDispatch())

                    bothInFlight.await(5, TimeUnit.SECONDS).shouldBeTrue()
                } finally {
                    release.complete(Unit)
                    worker.stop()
                }
            }
        }

        describe("resilience") {
            fun crashingOnceQueue(
                item: PendingDispatch,
                attempts: AtomicInteger,
                crash: () -> Throwable,
            ): NotificationDispatchQueue =
                object : NotificationDispatchQueue {
                    override fun enqueue(pendingDispatch: PendingDispatch) = Unit

                    override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                        if (attempts.incrementAndGet() == 1) {
                            throw crash()
                        }
                        handler(item)
                        awaitCancellation()
                    }
                }

            fun countingUseCase(delivered: CountDownLatch): DispatchNotificationUseCase =
                object : DispatchNotificationUseCase {
                    override suspend fun dispatch(pendingDispatch: PendingDispatch) {
                        delivered.countDown()
                    }
                }

            it("restarts a consumer whose consumption crashes") {
                withQuietLogger(NotificationDispatchWorker::class.java) {
                    val item = pendingDispatch()
                    val attempts = AtomicInteger()
                    val delivered = CountDownLatch(1)
                    val queue = crashingOnceQueue(item, attempts) { IllegalStateException("consumer crash") }
                    val worker =
                        NotificationDispatchWorker(queue, countingUseCase(delivered), dispatcher, concurrency = 1)
                    worker.start()

                    try {
                        delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    } finally {
                        worker.stop()
                    }
                }
            }

            it("restarts a consumer after a stray CancellationException") {
                withQuietLogger(NotificationDispatchWorker::class.java) {
                    val item = pendingDispatch()
                    val attempts = AtomicInteger()
                    val delivered = CountDownLatch(1)
                    val queue = crashingOnceQueue(item, attempts) { CancellationException("stray cancellation") }
                    val worker =
                        NotificationDispatchWorker(queue, countingUseCase(delivered), dispatcher, concurrency = 1)
                    worker.start()

                    try {
                        delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    } finally {
                        worker.stop()
                    }
                }
            }

            it("keeps dispatching when one consumer crashes with an Error") {
                withQuietLogger(NotificationDispatchWorker::class.java) {
                    val items = Channel<PendingDispatch>(Channel.UNLIMITED)
                    val attempts = AtomicInteger()
                    val queue =
                        object : NotificationDispatchQueue {
                            override fun enqueue(pendingDispatch: PendingDispatch) {
                                items.trySend(pendingDispatch)
                            }

                            override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                                if (attempts.incrementAndGet() == 1) {
                                    throw AssertionError("consumer death")
                                }
                                for (item in items) {
                                    handler(item)
                                }
                            }
                        }
                    val delivered = CountDownLatch(1)
                    val worker =
                        NotificationDispatchWorker(queue, countingUseCase(delivered), dispatcher, concurrency = 2)
                    worker.start()

                    try {
                        queue.enqueue(pendingDispatch())

                        delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    } finally {
                        worker.stop()
                    }
                }
            }
        }

        describe("stop") {
            it("cancels the consumption") {
                val started = CountDownLatch(1)
                val cancelled = CountDownLatch(1)
                val queue =
                    object : NotificationDispatchQueue {
                        override fun enqueue(pendingDispatch: PendingDispatch) = Unit

                        override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                            started.countDown()
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.countDown()
                            }
                        }
                    }
                val worker = NotificationDispatchWorker(queue, noopUseCase, dispatcher, concurrency = 1)
                worker.start()
                started.await(5, TimeUnit.SECONDS).shouldBeTrue()

                worker.stop()

                cancelled.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }

            it("cancels a dispatch that is still in flight") {
                val item = pendingDispatch()
                val queue =
                    object : NotificationDispatchQueue {
                        override fun enqueue(pendingDispatch: PendingDispatch) = Unit

                        override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) {
                            handler(item)
                        }
                    }
                val dispatchEntered = CountDownLatch(1)
                val cancelled = CountDownLatch(1)
                val useCase =
                    object : DispatchNotificationUseCase {
                        override suspend fun dispatch(pendingDispatch: PendingDispatch) {
                            dispatchEntered.countDown()
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.countDown()
                            }
                        }
                    }
                val worker = NotificationDispatchWorker(queue, useCase, dispatcher, concurrency = 1)
                worker.start()
                dispatchEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()

                worker.stop()

                cancelled.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    })
