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
package de.gematik.zeta.notificationservice.application.usecase.managechannel

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.application.repository.PushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException
import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException
import de.gematik.zeta.notificationservice.domain.model.ChannelConfiguration
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.PushkeyHolders
import de.gematik.zeta.notificationservice.domain.model.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration

class ManageChannelServiceTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val otherUserId = UserId.parse("X110411676")
        val first = ChannelId.parse("channel1")
        val second = ChannelId.parse("channel2")
        val unregistered = ChannelId.parse("channel3")

        fun repository(storageRecords: StorageRecordDAO = InMemoryStorageRecordDAO()): PushersRepository =
            DefaultPushersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        fun httpPusher(pushKey: String): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = "app.one", pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
            )

        fun holdersRepository(): PushkeyHoldersRepository =
            DefaultPushkeyHoldersRepository(
                storageRecords = InMemoryStorageRecordDAO(),
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        fun serviceWith(
            repository: PushersRepository,
            vararg pushKeys: String,
            holders: PushkeyHoldersRepository = holdersRepository(),
        ): ManageChannelUseCase {
            pushKeys.forEach { pushKey ->
                repository.update(userId) { collection ->
                    collection?.register(httpPusher(pushKey)) ?: PushersCollection.create(userId, httpPusher(pushKey))
                }
                holders.updateHolders(pushKey) { it?.add(userId) ?: PushkeyHolders.create(pushKey, userId) }
            }
            return ManageChannelService(repository, holders, ChannelRegistry.of(listOf("channel1", "channel2")))
        }

        describe("pushkeys of other users (A_29978)") {
            fun serviceWithForeignKey(): ManageChannelUseCase {
                val holders = holdersRepository()
                holders.updateHolders("key-foreign") { PushkeyHolders.create("key-foreign", otherUserId) }
                return serviceWith(repository(), "key-1", holders = holders)
            }

            it("rejects reading the configuration of a pushkey another user holds") {
                shouldThrow<PusherOfOtherUserException> {
                    serviceWithForeignKey().channelsOfDevice(
                        userId,
                        "key-foreign",
                    )
                }
            }

            it("rejects changing the configuration of a pushkey another user holds") {
                shouldThrow<PusherOfOtherUserException> {
                    serviceWithForeignKey().configure(
                        userId,
                        "key-foreign",
                        listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)),
                    )
                }
            }

            it("rejects it for a user without any device as well") {
                val holders = holdersRepository()
                holders.updateHolders("key-foreign") { PushkeyHolders.create("key-foreign", otherUserId) }

                shouldThrow<PusherOfOtherUserException> {
                    serviceWith(repository(), holders = holders).channelsOfDevice(userId, "key-foreign")
                }
            }
        }

        describe("availableChannels") {
            it("returns the registry in configured order") {
                serviceWith(repository()).availableChannels() shouldContainExactly listOf(first, second)
            }
        }

        describe("channelsOfDevice") {
            it("projects the whole registry, reporting not_set for unconfigured channels") {
                val service = serviceWith(repository(), "key-1")

                service.channelsOfDevice(userId, "key-1") shouldBe
                    mapOf(first to ChannelStatus.NOT_SET, second to ChannelStatus.NOT_SET)
            }

            it("reports the configured status alongside the untouched channels") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                service.channelsOfDevice(userId, "key-1") shouldBe
                    mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.NOT_SET)
            }

            it("rejects a pushkey the user has no device for") {
                val service = serviceWith(repository(), "key-1")

                shouldThrow<PusherNotFoundException> { service.channelsOfDevice(userId, "other-key") }
            }

            it("rejects a user without any device") {
                shouldThrow<PusherNotFoundException> {
                    serviceWith(
                        repository(),
                    ).channelsOfDevice(userId, "key-1")
                }
            }

            it("hides a stored channel that is no longer in the registry, but keeps it stored") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(second, ChannelStatus.ENABLED)))
                val shrunk =
                    ManageChannelService(repository, holdersRepository(), ChannelRegistry.of(listOf("channel1")))

                shrunk.channelsOfDevice(userId, "key-1") shouldBe mapOf(first to ChannelStatus.NOT_SET)
                repository
                    .findBy(userId)
                    .shouldNotBeNull()
                    .channelConfigOf("key-1")
                    ?.statusOf(second) shouldBe ChannelStatus.ENABLED
            }
        }

        describe("configure") {
            it("merges across calls, keeping channels absent from the later request") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")

                service.configure(
                    userId,
                    "key-1",
                    listOf(
                        ChannelConfiguration(first, ChannelStatus.ENABLED),
                        ChannelConfiguration(second, ChannelStatus.DISABLED),
                    ),
                )
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.DISABLED)))

                service.channelsOfDevice(userId, "key-1") shouldBe
                    mapOf(first to ChannelStatus.DISABLED, second to ChannelStatus.DISABLED)
            }

            it("keeps the two devices of one user independent") {
                val repository = repository()
                val service = serviceWith(repository, "key-1", "key-2")

                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                service.channelsOfDevice(userId, "key-2")[first] shouldBe ChannelStatus.NOT_SET
            }

            it("rejects a channel that is not in the registry") {
                val service = serviceWith(repository(), "key-1")

                shouldThrow<ChannelNotAllowedException> {
                    service.configure(
                        userId,
                        "key-1",
                        listOf(ChannelConfiguration(unregistered, ChannelStatus.ENABLED)),
                    )
                }
            }

            it("persists nothing when one entry of the request is rejected") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                shouldThrow<ChannelNotAllowedException> {
                    service.configure(
                        userId,
                        "key-1",
                        listOf(
                            ChannelConfiguration(second, ChannelStatus.ENABLED),
                            ChannelConfiguration(unregistered, ChannelStatus.ENABLED),
                        ),
                    )
                }

                service.channelsOfDevice(userId, "key-1") shouldBe
                    mapOf(first to ChannelStatus.ENABLED, second to ChannelStatus.NOT_SET)
            }

            it("rejects a channel listed more than once") {
                val service = serviceWith(repository(), "key-1")

                shouldThrow<InvalidChannelException> {
                    service.configure(
                        userId,
                        "key-1",
                        listOf(
                            ChannelConfiguration(first, ChannelStatus.ENABLED),
                            ChannelConfiguration(first, ChannelStatus.DISABLED),
                        ),
                    )
                }
            }

            it("rejects an unregistered channel before reading the user's record") {
                val service = serviceWith(repository())

                shouldThrow<ChannelNotAllowedException> {
                    service.configure(
                        userId,
                        "key-1",
                        listOf(ChannelConfiguration(unregistered, ChannelStatus.ENABLED)),
                    )
                }
            }

            it("rejects an unknown pushkey, including for an empty request") {
                val service = serviceWith(repository(), "key-1")

                shouldThrow<PusherNotFoundException> {
                    service.configure(userId, "other-key", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))
                }
                shouldThrow<PusherNotFoundException> { service.configure(userId, "other-key", emptyList()) }
            }

            it("leaves the configuration untouched for an empty request") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                service.configure(userId, "key-1", emptyList())

                service.channelsOfDevice(userId, "key-1")[first] shouldBe ChannelStatus.ENABLED
            }

            it("stores not_set verbatim rather than normalising it to disabled") {
                val repository = repository()
                val service = serviceWith(repository, "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.NOT_SET)))

                service.channelsOfDevice(userId, "key-1")[first] shouldBe ChannelStatus.NOT_SET
            }

            it("writes when a status actually changes") {
                val counting = SaveCountingStorageRecordDAO()
                val service = serviceWith(repository(counting), "key-1")
                val savesAfterSetup = counting.saves

                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                counting.saves shouldBe savesAfterSetup + 1
            }

            it("does not write when the requested statuses are already stored") {
                val counting = SaveCountingStorageRecordDAO()
                val service = serviceWith(repository(counting), "key-1")
                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))
                val savesAfterFirstWrite = counting.saves

                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.ENABLED)))

                counting.saves shouldBe savesAfterFirstWrite
            }

            it("does not write when not_set is requested for a channel that was never set") {
                val counting = SaveCountingStorageRecordDAO()
                val service = serviceWith(repository(counting), "key-1")
                val savesAfterSetup = counting.saves

                service.configure(userId, "key-1", listOf(ChannelConfiguration(first, ChannelStatus.NOT_SET)))

                counting.saves shouldBe savesAfterSetup
            }
        }
    })

private class SaveCountingStorageRecordDAO(
    private val delegate: StorageRecordDAO = InMemoryStorageRecordDAO(),
) : StorageRecordDAO by delegate {
    var saves = 0
        private set

    override fun save(
        recordId: String,
        payload: StorageRecord,
        expectedTransVerifier: Int?,
        ttl: Duration?,
    ) {
        saves++
        delegate.save(recordId, payload, expectedTransVerifier, ttl)
    }
}
