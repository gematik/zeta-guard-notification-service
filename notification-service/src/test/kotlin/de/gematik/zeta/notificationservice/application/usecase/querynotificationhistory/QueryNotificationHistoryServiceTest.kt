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
package de.gematik.zeta.notificationservice.application.usecase.querynotificationhistory

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.config.HistoryFeature
import de.gematik.zeta.notificationservice.application.repository.DefaultDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultNotificationsRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.ToggleableDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.error.HistoryDisabledException
import de.gematik.zeta.notificationservice.domain.error.NotificationNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class QueryNotificationHistoryServiceTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val otherUserId = UserId.parse("X110411676")
        val channel = ChannelId.parse("channel1")
        val payload = Json.decodeFromString<JsonObject>("""{"message":"Hello"}""")
        val acceptedAt = Instant.parse("2026-08-05T10:15:30Z")

        fun httpPusher(pushKey: String): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = "app.one", pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
            )

        class Fixture(
            historyEnabled: () -> Boolean = { true },
        ) {
            private val storageRecords = InMemoryStorageRecordDAO(FixedClock(acceptedAt))
            private val feature = HistoryFeature { historyEnabled() }

            val pushers = DefaultPushersRepository(storageRecords, NoOpPseudonymizer(), NoOpPayloadSealer())
            val notifications =
                DefaultNotificationsRepository(
                    storageRecords = storageRecords,
                    pseudonymizer = NoOpPseudonymizer(),
                    payloadSealer = NoOpPayloadSealer(),
                    ttl = 24.hours,
                )
            val history: DeviceMessageHistoryRepository =
                ToggleableDeviceMessageHistoryRepository(
                    feature = feature,
                    delegate =
                        DefaultDeviceMessageHistoryRepository(
                            storageRecords = storageRecords,
                            pseudonymizer = NoOpPseudonymizer(),
                            payloadSealer = NoOpPayloadSealer(),
                            ttl = 30.days,
                        ),
                )

            val service: QueryNotificationHistoryUseCase =
                QueryNotificationHistoryService(notifications, history, pushers, feature)

            fun register(
                user: UserId,
                pushKey: String,
            ) = pushers.update(user) { PushersCollection.create(user, httpPusher(pushKey)) }

            fun store(owner: UserId): NotificationId {
                val id = NotificationId.random()
                notifications.save(
                    Notification(
                        id = id,
                        userId = owner,
                        channel = channel,
                        prio = Priority.NORMAL,
                        payload = payload,
                        reference = "trigger-42",
                        status = NotificationStatus.ACCEPTED,
                        acceptedAt = acceptedAt,
                    ),
                )
                return id
            }
        }

        describe("messageOf") {
            it("reports the notification id as the message identifier, not the reference") {
                val fixture = Fixture()
                val id = fixture.store(userId)

                val message = fixture.service.messageOf(userId, id.toString())

                message.notificationId shouldBe id
                message.payload shouldBe payload
                message.sentAt shouldBe acceptedAt
            }

            it("rejects an unknown notification id") {
                val fixture = Fixture()

                shouldThrow<NotificationNotFoundException> {
                    fixture.service.messageOf(userId, NotificationId.random().toString())
                }
            }

            it("answers a notification of another user exactly like an unknown one") {
                val fixture = Fixture()
                val id = fixture.store(otherUserId)

                shouldThrow<NotificationNotFoundException> { fixture.service.messageOf(userId, id.toString()) }
            }
        }

        describe("messagesOf") {
            it("lists the messages of a registered device in append order") {
                val fixture = Fixture()
                fixture.register(userId, "key-1")
                val first = HistoryMessage(NotificationId.random(), payload, acceptedAt)
                val second = HistoryMessage(NotificationId.random(), payload, acceptedAt)
                fixture.history.append(userId, "key-1", first)
                fixture.history.append(userId, "key-1", second)

                fixture.service.messagesOf(userId, "key-1") shouldContainExactly listOf(first, second)
            }

            it("rejects a pushkey the user has not registered") {
                val fixture = Fixture()
                fixture.register(userId, "key-1")

                shouldThrow<PusherNotFoundException> { fixture.service.messagesOf(userId, "key-unknown") }
            }

            it("does not expose the history of another user that registered the same pushkey") {
                val fixture = Fixture()
                fixture.register(otherUserId, "key-shared")
                fixture.history.append(
                    otherUserId,
                    "key-shared",
                    HistoryMessage(NotificationId.random(), payload, acceptedAt),
                )
                fixture.register(userId, "key-shared")

                fixture.service.messagesOf(userId, "key-shared").shouldBeEmpty()
            }
        }

        describe("disabled history feature") {
            it("does not serve the list of a registered device") {
                val fixture = Fixture(historyEnabled = { false })
                fixture.register(userId, "key-1")
                fixture.history.append(userId, "key-1", HistoryMessage(NotificationId.random(), payload, acceptedAt))

                shouldThrow<HistoryDisabledException> { fixture.service.messagesOf(userId, "key-1") }
            }

            it("answers an unregistered pushkey the same way") {
                val fixture = Fixture(historyEnabled = { false })
                fixture.register(userId, "key-1")

                shouldThrow<HistoryDisabledException> { fixture.service.messagesOf(userId, "key-unknown") }
            }

            it("does not serve a stored notification by id") {
                val fixture = Fixture(historyEnabled = { false })
                val id = fixture.store(userId)

                shouldThrow<HistoryDisabledException> { fixture.service.messageOf(userId, id.toString()) }
            }

            it("follows a toggle change at runtime without rebuilding the wiring") {
                var enabled = false
                val fixture = Fixture(historyEnabled = { enabled })
                fixture.register(userId, "key-1")
                fixture.history.append(userId, "key-1", HistoryMessage(NotificationId.random(), payload, acceptedAt))

                enabled = true
                val kept = HistoryMessage(NotificationId.random(), payload, acceptedAt)
                fixture.history.append(userId, "key-1", kept)

                fixture.service.messagesOf(userId, "key-1") shouldContainExactly listOf(kept)
            }
        }
    })
