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
package de.gematik.zeta.notificationservice.application.usecase.submitnotification

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.config.HistoryFeature
import de.gematik.zeta.notificationservice.application.port.outbound.NotificationDispatchQueue
import de.gematik.zeta.notificationservice.application.port.outbound.PushPayloadCodec
import de.gematik.zeta.notificationservice.application.repository.DefaultDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultIdempotencyRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultNotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.NotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.ToggleableDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException
import de.gematik.zeta.notificationservice.domain.error.NotificationBacklogException
import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import de.gematik.zeta.notificationservice.domain.error.UserNotFoundException
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.IdempotencyKey
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.withQuietLogger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class SubmitNotificationServiceTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val channel = ChannelId.parse("channel1")
        val payload = Json.decodeFromString<JsonObject>("""{"message":"Hello"}""")
        val idempotencyKey = "9b5f3f2e-4c2a-4d3b-9c1e-6a0f1b2c3d4e"

        fun payloadOfSize(bytes: Int): JsonObject {
            val json = buildJsonObject { put("message", JsonPrimitive("a".repeat(bytes - 14))) }
            PushPayloadCodec.serialize(json).size shouldBe bytes
            return json
        }

        fun httpPusher(
            pushKey: String,
            url: String = "https://push.example/gateway",
        ): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = "app.one", pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(url = url),
            )

        fun urlLessPusher(pushKey: String): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = "app.one", pushKey = pushKey),
                kind = "other",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(),
            )

        class RecordingDispatchQueue : NotificationDispatchQueue {
            val enqueued = mutableListOf<PendingDispatch>()
            var onEnqueue: (PendingDispatch) -> Unit = {}

            override fun enqueue(pendingDispatch: PendingDispatch) {
                onEnqueue(pendingDispatch)
                enqueued += pendingDispatch
            }

            override suspend fun consume(handler: suspend (PendingDispatch) -> Unit) = Unit
        }

        /** One fixture holding the service and the repositories, so a test can assert what was written. */
        class Fixture(
            val clock: FixedClock = FixedClock(),
            historyEnabled: Boolean = true,
            encryptionEnabled: Boolean = false,
        ) {
            private val storageRecords = InMemoryStorageRecordDAO(clock)
            private val registry = ChannelRegistry.of(listOf(channel.value))

            val pushers =
                DefaultPushersRepository(storageRecords, NoOpPseudonymizer(), NoOpPayloadSealer())
            val notifications: NotificationsRepository =
                DefaultNotificationsRepository(
                    storageRecords = storageRecords,
                    pseudonymizer = NoOpPseudonymizer(),
                    payloadSealer = NoOpPayloadSealer(),
                    ttl = 24.hours,
                )
            private val storedHistory =
                DefaultDeviceMessageHistoryRepository(storageRecords, NoOpPseudonymizer(), NoOpPayloadSealer(), 30.days)
            val history: DeviceMessageHistoryRepository =
                ToggleableDeviceMessageHistoryRepository(
                    feature = HistoryFeature { historyEnabled },
                    delegate = storedHistory,
                )
            val idempotency =
                DefaultIdempotencyRepository(
                    storageRecords = storageRecords,
                    pseudonymizer = NoOpPseudonymizer(),
                    payloadSealer = NoOpPayloadSealer(),
                    ttl = 24.hours,
                )

            val dispatchQueue = RecordingDispatchQueue()

            val service: SubmitNotificationUseCase =
                SubmitNotificationService(
                    pushers,
                    notifications,
                    history,
                    idempotency,
                    dispatchQueue,
                    registry,
                    clock,
                    encryptionEnabled,
                )

            fun register(vararg pushKeys: Pusher) {
                pushKeys.forEach { pusher ->
                    pushers.update(userId) { collection ->
                        collection?.register(pusher) ?: PushersCollection.create(userId, pusher)
                    }
                }
            }

            fun enable(
                pushKey: String,
                status: ChannelStatus = ChannelStatus.ENABLED,
            ) {
                pushers.update(userId) { collection ->
                    collection.shouldNotBeNull().configureChannels(pushKey, mapOf(channel to status))
                }
            }

            fun command(
                key: String? = null,
                submittedChannel: ChannelId = channel,
                reference: String? = "trigger-42",
            ) = SubmitNotificationCommand(
                idempotencyKey = key,
                userId = userId,
                channel = submittedChannel,
                prio = Priority.NORMAL,
                payload = payload,
                reference = reference,
            )

            fun messagesOf(pushKey: String) = storedHistory.findBy(userId, pushKey)?.messages.orEmpty()
        }

        describe("submit") {
            it("persists the notification and reports it as accepted") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val receipt = fixture.service.submit(fixture.command())

                receipt.status shouldBe NotificationStatus.ACCEPTED
                receipt.channel shouldBe channel
                receipt.acceptedAt shouldBe fixture.clock.now()

                val stored = fixture.notifications.findBy(receipt.notificationId).shouldNotBeNull()
                stored.userId shouldBe userId
                stored.channel shouldBe channel
                stored.payload shouldBe payload
                stored.reference shouldBe "trigger-42"
                stored.status shouldBe NotificationStatus.ACCEPTED
                stored.acceptedAt shouldBe fixture.clock.now()
            }

            it("records the notification in the history of every enabled device") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"), httpPusher("key-2"), httpPusher("key-3"))
                fixture.enable("key-1")
                fixture.enable("key-2")
                fixture.enable("key-3", status = ChannelStatus.DISABLED)

                val receipt = fixture.service.submit(fixture.command())

                fixture.messagesOf("key-1").map { it.notificationId } shouldContainExactly
                    listOf(receipt.notificationId)
                fixture.messagesOf("key-2").map { it.notificationId } shouldContainExactly
                    listOf(receipt.notificationId)
                fixture.messagesOf("key-3").shouldBeEmpty()
            }

            it("stores no history when the history feature is disabled") {
                val fixture = Fixture(historyEnabled = false)
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val receipt = fixture.service.submit(fixture.command())

                receipt.status shouldBe NotificationStatus.ACCEPTED
                fixture.messagesOf("key-1").shouldBeEmpty()
                fixture.dispatchQueue.enqueued shouldHaveSize 1
            }

            it("reports no_active_channel transiently: nothing stored, nothing enqueued") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))

                val receipt = fixture.service.submit(fixture.command())

                receipt.status shouldBe NotificationStatus.NO_ACTIVE_CHANNEL
                fixture.notifications.findBy(receipt.notificationId).shouldBeNull()
                fixture.messagesOf("key-1").shouldBeEmpty()
                fixture.dispatchQueue.enqueued.shouldBeEmpty()
            }

            it("reports no_active_channel when the only enabled pusher has no push URL") {
                val fixture = Fixture()
                fixture.register(urlLessPusher("key-1"))
                fixture.enable("key-1")

                val receipt =
                    withQuietLogger(SubmitNotificationService::class.java) {
                        fixture.service.submit(fixture.command())
                    }

                receipt.status shouldBe NotificationStatus.NO_ACTIVE_CHANNEL
                fixture.dispatchQueue.enqueued.shouldBeEmpty()
            }

            it("rejects a user without any registered device") {
                val fixture = Fixture()

                shouldThrow<UserNotFoundException> { fixture.service.submit(fixture.command()) }
            }

            it("rejects a channel outside the registry as not allowed") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                shouldThrow<ChannelNotAllowedException> {
                    fixture.service.submit(fixture.command(submittedChannel = ChannelId.parse("epa.unknown")))
                }
            }

            it("rejects a reference longer than the contract allows") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                shouldThrow<IllegalArgumentException> {
                    fixture.service.submit(fixture.command(reference = "r".repeat(129)))
                }
            }

            it("accepts and stores a reference of exactly 128 characters unchanged") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")
                val reference = "r".repeat(128)

                val receipt = fixture.service.submit(fixture.command(reference = reference))

                fixture.notifications
                    .findBy(receipt.notificationId)
                    .shouldNotBeNull()
                    .reference shouldBe reference
            }
        }

        describe("payload size while encryption is enabled") {
            it("rejects a payload above 1018 serialized bytes and enqueues nothing") {
                val fixture = Fixture(encryptionEnabled = true)
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                shouldThrow<PayloadTooLargeException> {
                    fixture.service.submit(fixture.command().copy(payload = payloadOfSize(1019)))
                }

                fixture.dispatchQueue.enqueued.shouldBeEmpty()
            }

            it("accepts a payload of exactly 1018 serialized bytes") {
                val fixture = Fixture(encryptionEnabled = true)
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val receipt = fixture.service.submit(fixture.command().copy(payload = payloadOfSize(1018)))

                receipt.status shouldBe NotificationStatus.ACCEPTED
                fixture.dispatchQueue.enqueued shouldHaveSize 1
            }
        }

        describe("payload size while encryption is disabled") {
            it("rejects a payload above 3072 serialized bytes and enqueues nothing") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                shouldThrow<PayloadTooLargeException> {
                    fixture.service.submit(fixture.command().copy(payload = payloadOfSize(3073)))
                }

                fixture.dispatchQueue.enqueued.shouldBeEmpty()
            }

            it("accepts a payload of exactly 3072 serialized bytes") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val receipt = fixture.service.submit(fixture.command().copy(payload = payloadOfSize(3072)))

                receipt.status shouldBe NotificationStatus.ACCEPTED
                fixture.dispatchQueue.enqueued shouldHaveSize 1
            }
        }

        describe("dispatch handover") {
            it("enqueues the notification with every device the channel is enabled on") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"), httpPusher("key-2"), httpPusher("key-3"))
                fixture.enable("key-1")
                fixture.enable("key-3")

                val receipt = fixture.service.submit(fixture.command())

                val pending =
                    fixture.dispatchQueue.enqueued
                        .shouldHaveSize(1)
                        .single()
                pending.notification.id shouldBe receipt.notificationId
                pending.notification.payload shouldBe payload
                pending.notification.prio shouldBe Priority.NORMAL
                pending.targets.map { it.device.pushkey } shouldContainExactlyInAnyOrder listOf("key-1", "key-3")
                pending.targets.map { it.targetUrl }.toSet() shouldBe setOf("https://push.example/gateway")
                pending.targets.map { it.device.appId }.toSet() shouldBe setOf("app.one")
            }

            it("captures the pusher URL of each enabled device") {
                val fixture = Fixture()
                fixture.register(
                    httpPusher("key-1", url = "https://pgw.one/push/v1/"),
                    httpPusher("key-2", url = "https://pgw.two/push/v1/"),
                )
                fixture.enable("key-1")
                fixture.enable("key-2")

                fixture.service.submit(fixture.command())

                val pending = fixture.dispatchQueue.enqueued.single()
                pending.targets.map { it.targetUrl } shouldContainExactlyInAnyOrder
                    listOf("https://pgw.one/push/v1/", "https://pgw.two/push/v1/")
            }

            it("propagates a saturated queue and does not remember the receipt") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")
                fixture.dispatchQueue.onEnqueue = { throw NotificationBacklogException() }

                shouldThrow<NotificationBacklogException> {
                    fixture.service.submit(fixture.command(key = idempotencyKey))
                }

                fixture.dispatchQueue.onEnqueue = {}
                val retry = fixture.service.submit(fixture.command(key = idempotencyKey))
                retry.status shouldBe NotificationStatus.ACCEPTED
            }
        }

        describe("idempotent retransmission") {
            it("replays the first receipt without persisting a second notification") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val first = fixture.service.submit(fixture.command(key = idempotencyKey))
                val second = fixture.service.submit(fixture.command(key = idempotencyKey))

                second shouldBe first
                fixture.messagesOf("key-1") shouldHaveSize 1
                fixture.dispatchQueue.enqueued shouldHaveSize 1
            }

            it("does not replay a transient no_active_channel result") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))

                val first = fixture.service.submit(fixture.command(key = idempotencyKey))
                val second = fixture.service.submit(fixture.command(key = idempotencyKey))

                first.status shouldBe NotificationStatus.NO_ACTIVE_CHANNEL
                second.notificationId shouldNotBe first.notificationId
            }

            it("submits anew once the retention period has passed") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))
                fixture.enable("key-1")

                val first = fixture.service.submit(fixture.command(key = idempotencyKey))
                fixture.clock.advanceBy(24.hours)
                val second = fixture.service.submit(fixture.command(key = idempotencyKey))

                second.notificationId shouldNotBe first.notificationId
            }

            it("rejects a key longer than the contract allows") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))

                shouldThrow<IllegalArgumentException> {
                    fixture.service.submit(fixture.command(key = "k".repeat(IdempotencyKey.MAX_KEY_LENGTH + 1)))
                }
            }

            it("rejects an empty key instead of treating it as absent") {
                val fixture = Fixture()
                fixture.register(httpPusher("key-1"))

                shouldThrow<IllegalArgumentException> {
                    fixture.service.submit(fixture.command(key = ""))
                }
            }
        }
    })
