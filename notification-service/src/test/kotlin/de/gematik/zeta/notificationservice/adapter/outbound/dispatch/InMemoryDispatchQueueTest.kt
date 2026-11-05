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

import de.gematik.zeta.notificationservice.domain.error.NotificationBacklogException
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.withQuietLogger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class InMemoryDispatchQueueTest :
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

        describe("consume") {
            it("delivers enqueued items to the handler in order") {
                val queue = InMemoryDispatchQueue(capacity = 10)
                val first = pendingDispatch()
                val second = pendingDispatch()
                val received = mutableListOf<PendingDispatch>()
                val done = CompletableDeferred<Unit>()
                val consumer =
                    CoroutineScope(dispatcher).launch {
                        queue.consume {
                            received += it
                            if (received.size == 2) done.complete(Unit)
                        }
                    }

                try {
                    queue.enqueue(first)
                    queue.enqueue(second)

                    withTimeout(5.seconds) { done.await() }
                    received shouldContainExactly listOf(first, second)
                } finally {
                    consumer.cancelAndJoin()
                }
            }

            it("keeps consuming when the handler throws") {
                withQuietLogger(InMemoryDispatchQueue::class.java) {
                    val queue = InMemoryDispatchQueue(capacity = 10)
                    var calls = 0
                    val done = CompletableDeferred<Unit>()
                    val consumer =
                        CoroutineScope(dispatcher).launch {
                            queue.consume {
                                calls += 1
                                if (calls == 1) error("boom")
                                done.complete(Unit)
                            }
                        }

                    try {
                        queue.enqueue(pendingDispatch())
                        queue.enqueue(pendingDispatch())

                        withTimeout(5.seconds) { done.await() }
                        calls shouldBe 2
                    } finally {
                        consumer.cancelAndJoin()
                    }
                }
            }

            it("keeps consuming when the handler throws a stray CancellationException") {
                withQuietLogger(InMemoryDispatchQueue::class.java) {
                    val queue = InMemoryDispatchQueue(capacity = 10)
                    var calls = 0
                    val done = CompletableDeferred<Unit>()
                    val consumer =
                        CoroutineScope(dispatcher).launch {
                            queue.consume {
                                calls += 1
                                if (calls == 1) throw CancellationException("stray cancellation")
                                done.complete(Unit)
                            }
                        }

                    try {
                        queue.enqueue(pendingDispatch())
                        queue.enqueue(pendingDispatch())

                        withTimeout(5.seconds) { done.await() }
                        calls shouldBe 2
                    } finally {
                        consumer.cancelAndJoin()
                    }
                }
            }

            it("propagates cancellation that arrives while the handler is running") {
                val queue = InMemoryDispatchQueue(capacity = 10)
                val inHandler = CountDownLatch(1)
                var deliveries = 0
                val consumer =
                    CoroutineScope(dispatcher).launch {
                        queue.consume {
                            deliveries += 1
                            inHandler.countDown()
                            awaitCancellation()
                        }
                    }

                queue.enqueue(pendingDispatch())
                inHandler.await(5, TimeUnit.SECONDS).shouldBeTrue()

                consumer.cancelAndJoin()

                queue.enqueue(pendingDispatch())
                deliveries shouldBe 1
            }

            it("stops delivering once the consumer is cancelled") {
                val queue = InMemoryDispatchQueue(capacity = 1)
                val firstDelivered = CountDownLatch(1)
                var deliveries = 0
                val consumer =
                    CoroutineScope(dispatcher).launch {
                        queue.consume {
                            deliveries += 1
                            firstDelivered.countDown()
                        }
                    }

                queue.enqueue(pendingDispatch())
                firstDelivered.await(5, TimeUnit.SECONDS).shouldBeTrue()

                consumer.cancelAndJoin()

                queue.enqueue(pendingDispatch())
                deliveries shouldBe 1
            }
        }

        describe("enqueue") {
            it("rejects a submission once the queue is saturated") {
                val queue = InMemoryDispatchQueue(capacity = 1)
                val handlerBlocked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val consumer =
                    CoroutineScope(dispatcher).launch {
                        queue.consume {
                            handlerBlocked.countDown()
                            runInterruptible { release.await(5, TimeUnit.SECONDS) }
                        }
                    }

                try {
                    queue.enqueue(pendingDispatch())
                    handlerBlocked.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    queue.enqueue(pendingDispatch())

                    shouldThrow<NotificationBacklogException> { queue.enqueue(pendingDispatch()) }
                } finally {
                    release.countDown()
                    consumer.cancelAndJoin()
                }
            }
        }
    })
