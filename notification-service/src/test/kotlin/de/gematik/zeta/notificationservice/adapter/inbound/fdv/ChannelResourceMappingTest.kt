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
package de.gematik.zeta.notificationservice.adapter.inbound.fdv

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsPostSchema
import de.gematik.zeta.notificationservice.application.usecase.managechannel.ManageChannelUseCase
import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import de.gematik.zeta.notificationservice.domain.model.ChannelConfiguration
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import kotlinx.serialization.json.JsonObject

/**
 * Covers the resource's own responsibilities without booting Quarkus: domain/API status mapping and
 * where validation happens relative to the use-case call.
 */
class ChannelResourceMappingTest :
    DescribeSpec({
        val identifier = "X110411675"
        val first = ChannelId.parse("channel1")
        val second = ChannelId.parse("channel2")

        class RecordingUseCase(
            private val available: List<ChannelId> = listOf(first, second),
            private val deviceChannels: Map<ChannelId, ChannelStatus> = emptyMap(),
        ) : ManageChannelUseCase {
            var lastUserId: UserId? = null
            var lastPushKey: String? = null
            var lastRequested: List<ChannelConfiguration>? = null

            override fun availableChannels(): List<ChannelId> = available

            override fun channelsOfDevice(
                userId: UserId,
                pushKey: String,
            ): Map<ChannelId, ChannelStatus> {
                lastUserId = userId
                lastPushKey = pushKey
                return deviceChannels
            }

            override fun configure(
                userId: UserId,
                pushKey: String,
                requested: List<ChannelConfiguration>,
            ) {
                lastUserId = userId
                lastPushKey = pushKey
                lastRequested = requested
            }
        }

        fun identity(name: String = identifier): SecurityIdentity =
            QuarkusSecurityIdentity
                .builder()
                .setPrincipal(QuarkusPrincipal(name))
                .build()

        fun resource(useCase: ManageChannelUseCase) = ChannelResource(useCase, identity())

        describe("getChannels") {
            it("reports every registry channel as not_set, in registry order") {
                val response = resource(RecordingUseCase()).getChannels()

                response.status shouldBe 200
                response.entity.channels.shouldNotBeNull() shouldContainExactly
                    listOf(
                        ChannelsInner(id = "channel1", status = ChannelsInner.Status.not_set),
                        ChannelsInner(id = "channel2", status = ChannelsInner.Status.not_set),
                    )
            }
        }

        describe("getChannelsOfDevice") {
            it("maps every domain status to its API counterpart") {
                val useCase =
                    RecordingUseCase(
                        deviceChannels =
                            linkedMapOf(
                                first to ChannelStatus.ENABLED,
                                second to ChannelStatus.DISABLED,
                                ChannelId.parse("channel3") to ChannelStatus.NOT_SET,
                            ),
                    )

                val response = resource(useCase).getChannelsOfDevice("key-1")

                response.entity.channels.shouldNotBeNull() shouldContainExactly
                    listOf(
                        ChannelsInner(id = "channel1", status = ChannelsInner.Status.enabled),
                        ChannelsInner(id = "channel2", status = ChannelsInner.Status.disabled),
                        ChannelsInner(id = "channel3", status = ChannelsInner.Status.not_set),
                    )
            }

            it("passes the authenticated user and the raw pushkey to the use case") {
                val useCase = RecordingUseCase()

                resource(useCase).getChannelsOfDevice("key-1")

                useCase.lastUserId shouldBe UserId.parse(identifier)
                useCase.lastPushKey shouldBe "key-1"
            }
        }

        describe("postChannelsOfDevice") {
            it("forwards the decoded changes and returns an empty object") {
                val useCase = RecordingUseCase()
                val body =
                    ChannelsPostSchema(
                        channels =
                            listOf(
                                ChannelsInner(id = "channel1", status = ChannelsInner.Status.enabled),
                                ChannelsInner(id = "channel2", status = ChannelsInner.Status.not_set),
                            ),
                    )

                val response = resource(useCase).postChannelsOfDevice("key-1", body)

                response.status shouldBe 200
                response.entity shouldBe JsonObject(emptyMap())
                useCase.lastUserId shouldBe UserId.parse(identifier)
                useCase.lastPushKey shouldBe "key-1"
                useCase.lastRequested.shouldNotBeNull() shouldContainExactly
                    listOf(
                        ChannelConfiguration(first, ChannelStatus.ENABLED),
                        ChannelConfiguration(second, ChannelStatus.NOT_SET),
                    )
            }

            it("forwards an empty list when the body omits channels") {
                val useCase = RecordingUseCase()

                resource(useCase).postChannelsOfDevice("key-1", ChannelsPostSchema(channels = null))

                useCase.lastRequested.shouldNotBeNull().shouldBeEmpty()
            }

            it("rejects a malformed channel id without entering the use case") {
                // Locks in the error precedence: moving ChannelId.parse into the use case would let a
                // format violation be reported as 404 for an unknown device.
                val useCase = RecordingUseCase()
                val body =
                    ChannelsPostSchema(
                        channels = listOf(ChannelsInner(id = "NOT VALID!", status = ChannelsInner.Status.enabled)),
                    )

                shouldThrow<InvalidChannelException> { resource(useCase).postChannelsOfDevice("key-1", body) }

                useCase.lastPushKey.shouldBeNull()
                useCase.lastRequested.shouldBeNull()
            }
        }
    })
