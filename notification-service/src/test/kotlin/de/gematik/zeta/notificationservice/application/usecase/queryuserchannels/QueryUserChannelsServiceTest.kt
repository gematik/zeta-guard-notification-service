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
package de.gematik.zeta.notificationservice.application.usecase.queryuserchannels

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.application.usecase.managechannel.ManageChannelService
import de.gematik.zeta.notificationservice.domain.error.UserNotFoundException
import de.gematik.zeta.notificationservice.domain.model.ChannelConfiguration
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelRegistry
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

class QueryUserChannelsServiceTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val first = ChannelId.parse("channel1")
        val second = ChannelId.parse("channel2")

        fun registry() = ChannelRegistry.of(listOf("channel1", "channel2"))

        fun httpPusher(pushKey: String): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = "app.one", pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
            )

        fun repositoryWith(vararg pushKeys: String): PushersRepository {
            val repository =
                DefaultPushersRepository(
                    storageRecords = InMemoryStorageRecordDAO(),
                    pseudonymizer = NoOpPseudonymizer(),
                    payloadSealer = NoOpPayloadSealer(),
                )
            pushKeys.forEach { pushKey ->
                repository.update(userId) { collection ->
                    collection?.register(httpPusher(pushKey)) ?: PushersCollection.create(userId, httpPusher(pushKey))
                }
            }
            return repository
        }

        fun configure(
            repository: PushersRepository,
            pushKey: String,
            status: ChannelStatus,
            channelId: ChannelId = first,
        ) = ManageChannelService(
            repository,
            DefaultPushkeyHoldersRepository(InMemoryStorageRecordDAO(), NoOpPseudonymizer(), NoOpPayloadSealer()),
            registry(),
        ).configure(userId, pushKey, listOf(ChannelConfiguration(channelId, status)))

        fun service(repository: PushersRepository): QueryUserChannelsUseCase =
            QueryUserChannelsService(repository, registry())

        describe("channelsOf") {
            it("reports a channel as enabled as soon as one device has it enabled") {
                val repository = repositoryWith("key-1", "key-2")
                configure(repository, "key-1", ChannelStatus.DISABLED)
                configure(repository, "key-2", ChannelStatus.ENABLED)

                service(repository).channelsOf(userId, includeInactive = false).channels shouldBe
                    mapOf(first to ChannelStatus.ENABLED)
            }

            it("treats not_set identically to disabled: not_set plus disabled is not enabled") {
                val repository = repositoryWith("key-1", "key-2")
                configure(repository, "key-1", ChannelStatus.NOT_SET)
                configure(repository, "key-2", ChannelStatus.DISABLED)

                val active = service(repository).channelsOf(userId, includeInactive = false)
                val all = service(repository).channelsOf(userId, includeInactive = true)

                active.channels shouldNotContainKey first
                all.channels[first] shouldBe ChannelStatus.DISABLED
                all.channels[first]?.isActive() shouldBe false
            }

            it("reports a registered channel enabled on no device as inactive, not as a rejection") {
                val repository = repositoryWith("key-1")

                val all = service(repository).channelsOf(userId, includeInactive = true)

                all.channels shouldBe mapOf(first to ChannelStatus.NOT_SET, second to ChannelStatus.NOT_SET)
                all.channels.values.none { it.isActive() } shouldBe true
            }

            it("omits inactive channels by default and keeps them with includeInactive") {
                val repository = repositoryWith("key-1")
                configure(repository, "key-1", ChannelStatus.ENABLED)

                service(repository).channelsOf(userId, includeInactive = false).channels.keys shouldBe setOf(first)
                service(repository).channelsOf(userId, includeInactive = true).channels.keys shouldBe
                    setOf(first, second)
            }

            it("reports the number of registered devices") {
                val repository = repositoryWith("key-1", "key-2")

                service(repository).channelsOf(userId, includeInactive = true).registeredDeviceCount shouldBe 2
            }

            it("rejects a user without any registered device") {
                shouldThrow<UserNotFoundException> {
                    service(repositoryWith()).channelsOf(userId, includeInactive = true)
                }
            }

            it("ignores channels that are not in the registry") {
                val repository = repositoryWith("key-1")
                configure(repository, "key-1", ChannelStatus.ENABLED)
                val shrunk = QueryUserChannelsService(repository, ChannelRegistry.of(listOf("channel2")))

                shrunk.channelsOf(userId, includeInactive = true).channels shouldBe
                    mapOf(second to ChannelStatus.NOT_SET)
            }
        }
    })
