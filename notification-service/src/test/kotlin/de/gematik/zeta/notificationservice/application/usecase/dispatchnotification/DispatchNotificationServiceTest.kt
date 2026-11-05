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

import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayError
import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayPort
import de.gematik.zeta.notificationservice.application.port.outbound.PushPayloadEncryptor
import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.usecase.managepusher.ManagePusherUseCase
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.DeliveryStatus
import de.gematik.zeta.notificationservice.domain.model.Device
import de.gematik.zeta.notificationservice.domain.model.DispatchItemResult
import de.gematik.zeta.notificationservice.domain.model.DispatchResult
import de.gematik.zeta.notificationservice.domain.model.DispatchSummary
import de.gematik.zeta.notificationservice.domain.model.DispatchTarget
import de.gematik.zeta.notificationservice.domain.model.EncryptedPushItem
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.PlainPushItem
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.EncryptedPushTestSupport
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.recordedLog
import de.gematik.zeta.notificationservice.testsupport.withQuietLogger
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.YearMonth
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchNotificationServiceTest :
    DescribeSpec({
        val acceptedAt = Instant.parse("2026-08-06T10:00:00Z")
        val one = "https://pgw.one/push/v1/"
        val two = "https://pgw.two/push/v1/"

        fun TestScope.virtualClock(): Clock =
            object : Clock {
                override fun now(): Instant = acceptedAt + testScheduler.currentTime.milliseconds
            }

        fun notification(prio: Priority = Priority.NORMAL): Notification =
            Notification(
                id = NotificationId.random(),
                userId = UserId.parse("X110411675"),
                channel = ChannelId.parse("epa.documents.new"),
                prio = prio,
                payload = buildJsonObject { put("event", JsonPrimitive("created")) },
                reference = null,
                status = NotificationStatus.ACCEPTED,
                acceptedAt = acceptedAt,
            )

        fun device(pushkey: String): Device = Device(appId = "app.one", pushkey = pushkey)

        fun dispatchTo(vararg targets: Pair<String, String>): PendingDispatch =
            PendingDispatch(notification(), targets.map { (url, pushkey) -> DispatchTarget(url, device(pushkey)) })

        fun singleTarget(notification: Notification) =
            PendingDispatch(notification, listOf(DispatchTarget(one, device("key-1"))))

        fun resultOf(items: List<DispatchItemResult>): DispatchResult {
            fun count(status: DeliveryStatus) = items.count { it.status == status }
            return DispatchResult(
                results = items,
                summary =
                    DispatchSummary(
                        total = items.size,
                        successful = count(DeliveryStatus.SUCCESS),
                        failed = count(DeliveryStatus.FAILED),
                        partial = count(DeliveryStatus.PARTIAL),
                    ),
            )
        }

        fun success(ids: List<String>): DispatchResult =
            resultOf(ids.map { DispatchItemResult(it, DeliveryStatus.SUCCESS) })

        fun failing(
            id: String,
            vararg rejected: String,
        ): DispatchResult = resultOf(listOf(DispatchItemResult(id, DeliveryStatus.FAILED, rejected.toList())))

        fun rejecting(vararg pushkeys: String): suspend (String, List<PlainPushItem>) -> DispatchResult =
            {
                _,
                items,
                ->
                resultOf(listOf(DispatchItemResult(items.first().id, DeliveryStatus.PARTIAL, pushkeys.toList())))
            }

        class RecordingPushGatewayPort : PushGatewayPort {
            val sent = mutableListOf<Pair<String, List<PlainPushItem>>>()
            var resultFor: suspend (String, List<PlainPushItem>) -> DispatchResult =
                { _, items -> success(items.map { it.id }) }

            override suspend fun sendPlain(
                targetUrl: String,
                items: List<PlainPushItem>,
            ): DispatchResult {
                sent += targetUrl to items
                return resultFor(targetUrl, items)
            }

            val sentEncrypted = mutableListOf<Pair<String, List<EncryptedPushItem>>>()
            var encryptedResultFor: suspend (String, List<EncryptedPushItem>) -> DispatchResult =
                { _, items -> success(items.map { it.id }) }

            override suspend fun sendEncrypted(
                targetUrl: String,
                items: List<EncryptedPushItem>,
            ): DispatchResult {
                sentEncrypted += targetUrl to items
                return encryptedResultFor(targetUrl, items)
            }
        }

        class RecordingPusherManagement : ManagePusherUseCase {
            val rejected = mutableListOf<Set<String>>()
            val materials = mutableMapOf<String, PusherKeyMaterial>()
            var onRemoveRejected: () -> Unit = {}

            override fun get(userId: UserId): List<Pusher> = emptyList()

            override fun register(
                userId: UserId,
                pusher: Pusher,
            ) = Unit

            override fun currentKeyMaterial(
                userId: UserId,
                pusherId: PusherId,
                month: YearMonth,
            ): PusherKeyMaterial? = materials[pusherId.pushKey]

            override fun remove(
                userId: UserId,
                pusherId: PusherId,
            ) = Unit

            override fun removeMany(
                userId: UserId,
                pushKeys: Set<String>,
            ) {
                onRemoveRejected()
                this.rejected += pushKeys
            }
        }

        fun service(
            port: PushGatewayPort,
            pushers: ManagePusherUseCase = RecordingPusherManagement(),
            giveUpAfterMillis: Long = 0,
            initialDelayMillis: Long = 1,
            encryptionEnabled: Boolean = false,
            maxBatchCount: Int = 100,
            maxDelayMillis: Long = 60_000,
            clock: Clock = FixedClock(acceptedAt),
            failedItemAttempts: Int = 3,
            maxBatchTime: Duration = Duration.ofSeconds(1),
            retention: Duration = Duration.ofHours(24),
        ) = DispatchNotificationService(
            port,
            pushers,
            PushPayloadEncryptor(),
            clock,
            encryptionEnabled,
            Duration.ofMillis(giveUpAfterMillis),
            Duration.ofMillis(initialDelayMillis),
            Duration.ofMillis(maxDelayMillis),
            failedItemAttempts,
            maxBatchCount,
            maxBatchTime,
            retention,
        )

        describe("dispatch") {
            it("sends one batch per distinct pusher URL with the devices grouped") {
                val port = RecordingPushGatewayPort()

                service(port).dispatch(dispatchTo(one to "key-1", one to "key-2", two to "key-3"))

                port.sent.map { it.first } shouldContainExactlyInAnyOrder listOf(one, two)
                val batchForOne = port.sent.first { it.first == one }.second
                batchForOne shouldHaveSize 1
                batchForOne.single().devices.map { it.pushkey } shouldContainExactly listOf("key-1", "key-2")
            }

            it("sends to the distinct pusher URLs concurrently") {
                val latencyMillis = 1_000L
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items ->
                    delay(latencyMillis)
                    success(items.map { it.id })
                }
                val service = service(port)

                runTest {
                    service.dispatch(dispatchTo(one to "key-1", two to "key-2"))

                    currentTime shouldBe latencyMillis
                }

                port.sent shouldHaveSize 2
            }

            it("passes notification id, prio and payload through to the batch item") {
                val port = RecordingPushGatewayPort()
                val n = notification(prio = Priority.NORMAL)

                service(port).dispatch(singleTarget(n))

                val item =
                    port.sent
                        .single()
                        .second
                        .single()
                item.id shouldBe n.id.toString()
                item.prio shouldBe Priority.NORMAL
                item.payload shouldBe n.payload
            }

            it("continues with the remaining URLs when one send fails and logs a partial outcome") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { url, items ->
                    if (url == one) throw PushGatewayError.ConnectionFailed(null)
                    success(items.map { it.id })
                }

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        shouldNotThrowAny { service(port).dispatch(dispatchTo(one to "key-1", two to "key-2")) }
                    }

                port.sent shouldHaveSize 2
                log shouldContain "dispatch outcome: partially_rejected"
            }

            it("retries a retryable failure until the Push Gateway accepts the batch") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items ->
                    if (port.sent.size < 3) throw PushGatewayError.ServerError(null)
                    success(items.map { it.id })
                }

                withQuietLogger(DispatchNotificationService::class.java) {
                    service(port, giveUpAfterMillis = 60_000).dispatch(singleTarget(notification()))
                }

                port.sent shouldHaveSize 3
            }

            it("doubles the wait between attempts and gives up after give-up-after") {
                val port = RecordingPushGatewayPort()
                val attemptedAt = mutableListOf<Long>()

                runTest {
                    port.resultFor = { _, _ ->
                        attemptedAt += currentTime
                        throw PushGatewayError.ServerError(null)
                    }

                    withQuietLogger(DispatchNotificationService::class.java) {
                        service(port, giveUpAfterMillis = 1_000, initialDelayMillis = 100, clock = virtualClock())
                            .dispatch(singleTarget(notification()))
                    }
                }

                attemptedAt.zipWithNext { earlier, later -> later - earlier } shouldContainExactly
                    listOf(100L, 200L, 400L)
            }

            it("caps the wait between attempts at max-delay") {
                val port = RecordingPushGatewayPort()
                val attemptedAt = mutableListOf<Long>()

                runTest {
                    port.resultFor = { _, _ ->
                        attemptedAt += currentTime
                        throw PushGatewayError.ServerError(null)
                    }

                    withQuietLogger(DispatchNotificationService::class.java) {
                        service(
                            port,
                            giveUpAfterMillis = 1_000,
                            initialDelayMillis = 100,
                            maxDelayMillis = 250,
                            clock = virtualClock(),
                        ).dispatch(singleTarget(notification()))
                    }
                }

                attemptedAt.zipWithNext { earlier, later -> later - earlier } shouldContainExactly
                    listOf(100L, 200L, 250L, 250L)
            }

            it("retries an item the Push Gateway reported failed") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items ->
                    if (port.sent.size < 2) failing(items.single().id) else success(items.map { it.id })
                }

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, giveUpAfterMillis = 60_000).dispatch(singleTarget(notification()))
                    }

                port.sent shouldHaveSize 2
                log shouldContain "dispatch outcome: dispatched"
            }

            it("gives up on an item reported failed max-attempts times, not counting transport errors") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items ->
                    if (port.sent.size == 2) throw PushGatewayError.ServerError(null)
                    failing(items.single().id)
                }

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, giveUpAfterMillis = 60_000, failedItemAttempts = 2)
                            .dispatch(singleTarget(notification()))
                    }

                port.sent shouldHaveSize 3
                log shouldContain "dispatch outcome: failed"
            }

            it("retries a failed item without the devices the Push Gateway rejected and logs a partial outcome") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items ->
                    if (port.sent.size < 2) failing(items.single().id, "key-2") else success(items.map { it.id })
                }
                val pushers = RecordingPusherManagement()

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, pushers, giveUpAfterMillis = 60_000)
                            .dispatch(dispatchTo(one to "key-1", one to "key-2"))
                    }

                port.sent.map { (_, items) -> items.single().devices.map { it.pushkey } } shouldContainExactly
                    listOf(listOf("key-1", "key-2"), listOf("key-1"))
                pushers.rejected shouldContainExactly listOf(setOf("key-2"))
                log shouldContain "dispatch outcome: partially_rejected"
            }

            it("does not retry a failed item whose every device was rejected") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, items -> failing(items.single().id, "key-1") }

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, giveUpAfterMillis = 60_000).dispatch(singleTarget(notification()))
                    }

                port.sent shouldHaveSize 1
                log shouldContain "dispatch outcome: failed"
            }

            it("stops retrying once the dispatch is cancelled") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, _ -> throw PushGatewayError.ServerError(null) }

                runTest {
                    withQuietLogger(DispatchNotificationService::class.java) {
                        val dispatching =
                            launch {
                                service(port, giveUpAfterMillis = 60_000, initialDelayMillis = 1_000)
                                    .dispatch(singleTarget(notification()))
                            }

                        advanceTimeBy(1_500)
                        dispatching.cancelAndJoin()
                    }
                }

                port.sent shouldHaveSize 2
            }

            it("does not retry a request the Push Gateway rejected outright") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, _ -> throw PushGatewayError.RequestRejected(400, null) }

                withQuietLogger(DispatchNotificationService::class.java) {
                    service(port, giveUpAfterMillis = 60_000).dispatch(singleTarget(notification()))
                }

                port.sent shouldHaveSize 1
            }

            it("deregisters a rejected pushkey that was not part of the batch (A_27374)") {
                val port = RecordingPushGatewayPort()
                port.resultFor = rejecting("stale-key")
                val pushers = RecordingPusherManagement()

                withQuietLogger(DispatchNotificationService::class.java) {
                    shouldNotThrowAny {
                        service(port, pushers).dispatch(singleTarget(notification()))
                    }
                }

                pushers.rejected shouldContainExactly listOf(setOf("stale-key"))
            }

            it("deregisters what every Push Gateway rejected in one call once the last one answered (A_27374)") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { url, items ->
                    val id = items.single().id
                    when {
                        url == one -> resultOf(listOf(DispatchItemResult(id, DeliveryStatus.PARTIAL, listOf("key-1"))))
                        port.sent.count { (target, _) -> target == two } == 1 -> failing(id, "key-3")
                        else -> success(listOf(id))
                    }
                }
                val pushers = RecordingPusherManagement()
                var sentBeforeRemoval = 0
                pushers.onRemoveRejected = { sentBeforeRemoval = port.sent.size }

                withQuietLogger(DispatchNotificationService::class.java) {
                    service(port, pushers, giveUpAfterMillis = 60_000)
                        .dispatch(dispatchTo(one to "key-1", two to "key-2", two to "key-3"))
                }

                pushers.rejected shouldContainExactly listOf(setOf("key-1", "key-3"))
                sentBeforeRemoval shouldBe 3
            }

            it("deregisters exactly the pushkeys the Push Gateway rejected and logs a partial outcome") {
                val port = RecordingPushGatewayPort()
                port.resultFor = rejecting("key-2")
                val pushers = RecordingPusherManagement()

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, pushers).dispatch(dispatchTo(one to "key-1", one to "key-2"))
                    }

                pushers.rejected shouldContainExactly listOf(setOf("key-2"))
                log shouldContain "dispatch outcome: partially_rejected"
            }

            it("keeps every device and logs a dispatched outcome when the Push Gateway rejected nothing") {
                val port = RecordingPushGatewayPort()
                val pushers = RecordingPusherManagement()

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, pushers).dispatch(singleTarget(notification()))
                    }

                pushers.rejected.shouldBeEmpty()
                log shouldContain "dispatch outcome: dispatched"
            }

            it("deregisters nothing and logs a failed outcome when the batch never reached the Push Gateway") {
                val port = RecordingPushGatewayPort()
                port.resultFor = { _, _ -> throw PushGatewayError.ConnectionFailed(null) }
                val pushers = RecordingPusherManagement()

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, pushers).dispatch(singleTarget(notification()))
                    }

                pushers.rejected.shouldBeEmpty()
                log shouldContain "dispatch outcome: failed"
            }

            it("keeps the dispatch successful when the deregistration fails") {
                val port = RecordingPushGatewayPort()
                port.resultFor = rejecting("key-1")
                val pushers = RecordingPusherManagement()
                pushers.onRemoveRejected = { throw StaleRecordException() }

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        shouldNotThrowAny {
                            service(port, pushers).dispatch(dispatchTo(one to "key-1", one to "key-2"))
                        }
                    }

                port.sent shouldHaveSize 1
                log shouldContain "dispatch outcome: partially_rejected"
            }
        }

        describe("configuration") {
            it("refuses values outside their limits (A_28213, A_28216)") {
                val port = RecordingPushGatewayPort()

                shouldThrow<IllegalArgumentException> { service(port, maxBatchCount = 0) }
                shouldThrow<IllegalArgumentException> { service(port, maxBatchCount = 101) }
                shouldThrow<IllegalArgumentException> { service(port, maxBatchTime = Duration.ofMillis(10_001)) }
                shouldThrow<IllegalArgumentException> { service(port, maxBatchTime = Duration.ofMillis(-1)) }
                shouldThrow<IllegalArgumentException> { service(port, initialDelayMillis = 0) }
                shouldThrow<IllegalArgumentException> { service(port, initialDelayMillis = 2, maxDelayMillis = 1) }
                shouldThrow<IllegalArgumentException> { service(port, failedItemAttempts = 0) }
                shouldThrow<IllegalArgumentException> {
                    service(port, giveUpAfterMillis = 1_000, retention = Duration.ofSeconds(1))
                }
            }

            it("accepts the limits themselves") {
                shouldNotThrowAny {
                    service(RecordingPushGatewayPort(), maxBatchCount = 1, maxBatchTime = Duration.ZERO)
                    service(RecordingPushGatewayPort(), maxBatchCount = 100, initialDelayMillis = 1, maxDelayMillis = 1)
                    service(RecordingPushGatewayPort(), maxBatchTime = Duration.ofSeconds(10))
                    service(RecordingPushGatewayPort(), giveUpAfterMillis = 999, retention = Duration.ofSeconds(1))
                }
            }
        }

        describe("encrypted dispatch") {
            fun material(
                keyIdentifier: String,
                iss: String = EncryptedPushTestSupport.ISS,
            ): PusherKeyMaterial =
                EncryptedPushTestSupport.material(issHex = iss, month = "2026-08", keyIdentifier = keyIdentifier)

            fun targets(vararg pushkeys: String) =
                PendingDispatch(notification(), pushkeys.map { DispatchTarget(one, device(it)) })

            it("encrypts one item per device and uses the encrypted endpoint only") {
                val port = RecordingPushGatewayPort()
                val pushers = RecordingPusherManagement()
                pushers.materials["key-1"] = material("kid-1")
                pushers.materials["key-2"] = material("kid-2", EncryptedPushTestSupport.OTHER_ISS)
                val n = notification()

                service(port, pushers, encryptionEnabled = true)
                    .dispatch(
                        PendingDispatch(
                            n,
                            listOf(DispatchTarget(one, device("key-1")), DispatchTarget(one, device("key-2"))),
                        ),
                    )

                port.sent.shouldBeEmpty()
                val items = port.sentEncrypted.single().second
                items.map { it.id } shouldContainExactly listOf("${n.id}:0", "${n.id}:1")
                items.map { it.device.pushkey } shouldContainExactly listOf("key-1", "key-2")
                items.map { it.keyIdentifier } shouldContainExactly listOf("kid-1", "kid-2")
                items.map { it.ciphertext.length }.toSet() shouldContainExactly setOf(1404)
                items.map { it.timeMessageEncrypted }.toSet() shouldContainExactly setOf("2026-08")
                items.map { it.ciphertext }.toSet() shouldHaveSize 2
                EncryptedPushTestSupport.decrypt(items.first().ciphertext, material("kid-1").aesKey) shouldBe n.payload
            }

            it("skips a device without key material, never falls back to plain text and logs a partial outcome") {
                val port = RecordingPushGatewayPort()
                val pushers = RecordingPusherManagement()
                pushers.materials["key-1"] = material("kid-1")

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, pushers, encryptionEnabled = true).dispatch(targets("key-1", "key-2"))
                    }

                port.sent.shouldBeEmpty()
                port.sentEncrypted
                    .single()
                    .second
                    .map { it.device.pushkey } shouldContainExactly listOf("key-1")
                log shouldContain "dispatch outcome: partially_rejected"
            }

            it("sends nothing and logs a failed outcome when no device has key material") {
                val port = RecordingPushGatewayPort()

                val log =
                    recordedLog(DispatchNotificationService::class.java) {
                        service(port, encryptionEnabled = true).dispatch(singleTarget(notification()))
                    }

                port.sent.shouldBeEmpty()
                port.sentEncrypted.shouldBeEmpty()
                log shouldContain "dispatch outcome: failed"
            }

            it("splits the devices into batches of the configured max-count") {
                val port = RecordingPushGatewayPort()
                val pushers = RecordingPusherManagement()
                val pushkeys = (1..5).map { "key-$it" }
                pushkeys.forEach { pushers.materials[it] = material("kid-$it") }

                service(port, pushers, encryptionEnabled = true, maxBatchCount = 2)
                    .dispatch(targets(*pushkeys.toTypedArray()))

                port.sentEncrypted.map { it.second.size } shouldContainExactlyInAnyOrder listOf(2, 2, 1)
            }

            it("sends the batches for one Push Gateway concurrently") {
                val latencyMillis = 1_000L
                val port = RecordingPushGatewayPort()
                port.encryptedResultFor = { _, items ->
                    delay(latencyMillis)
                    success(items.map { it.id })
                }
                val pushers = RecordingPusherManagement()
                pushers.materials["key-1"] = material("kid-1")
                pushers.materials["key-2"] = material("kid-2")
                val service = service(port, pushers, encryptionEnabled = true, maxBatchCount = 1)

                runTest {
                    service.dispatch(targets("key-1", "key-2"))

                    currentTime shouldBe latencyMillis
                }

                port.sentEncrypted shouldHaveSize 2
            }

            it("retries only the encrypted items reported failed and deregisters the rejected ones") {
                val port = RecordingPushGatewayPort()
                port.encryptedResultFor = { _, items ->
                    if (port.sentEncrypted.size > 1) {
                        success(items.map { it.id })
                    } else {
                        resultOf(
                            items.map {
                                when (it.device.pushkey) {
                                    "key-1" -> DispatchItemResult(it.id, DeliveryStatus.SUCCESS)
                                    "key-2" -> DispatchItemResult(it.id, DeliveryStatus.FAILED)
                                    else -> DispatchItemResult(it.id, DeliveryStatus.FAILED, listOf(it.device.pushkey))
                                }
                            },
                        )
                    }
                }
                val pushers = RecordingPusherManagement()
                listOf("key-1", "key-2", "key-3").forEach { pushers.materials[it] = material("kid-$it") }

                withQuietLogger(DispatchNotificationService::class.java) {
                    service(port, pushers, giveUpAfterMillis = 60_000, encryptionEnabled = true)
                        .dispatch(targets("key-1", "key-2", "key-3"))
                }

                port.sentEncrypted.map { (_, items) -> items.map { it.device.pushkey } } shouldContainExactly
                    listOf(listOf("key-1", "key-2", "key-3"), listOf("key-2"))
                pushers.rejected shouldContainExactly listOf(setOf("key-3"))
            }

            it("keeps sending plain text while encryption is disabled") {
                val port = RecordingPushGatewayPort()
                val pushers = RecordingPusherManagement()
                pushers.materials["key-1"] = material("kid-1")

                service(port, pushers).dispatch(singleTarget(notification()))

                port.sentEncrypted.shouldBeEmpty()
                port.sent shouldHaveSize 1
            }
        }
    })
