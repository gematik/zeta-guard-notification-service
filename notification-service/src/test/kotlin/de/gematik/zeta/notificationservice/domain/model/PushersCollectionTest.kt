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
package de.gematik.zeta.notificationservice.domain.model

import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

class PushersCollectionTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val channel = ChannelId.parse("epa.documents.new")
        val otherChannel = ChannelId.parse("epa.consent.changed")

        fun httpPusher(
            pushKey: String,
            appId: String,
            appDisplayName: String = "App",
            deviceDisplayName: String = "Device",
        ): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = appId, pushKey = pushKey),
                kind = "http",
                appDisplayName = appDisplayName,
                deviceDisplayName = deviceDisplayName,
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
            )

        describe("create") {
            it("starts collection with a single pusher") {
                val pusher = httpPusher(pushKey = "key-1", appId = "app.one")

                val result = PushersCollection.create(userId, pusher)

                result.userId shouldBe userId
                result.pushers shouldContainExactly listOf(pusher)
                result.markedForRemoval() shouldBe false
            }
        }

        describe("register") {
            it("keeps distinct pushers with different appId or pushKey") {
                val first = httpPusher(pushKey = "key-1", appId = "app.one")
                val second = httpPusher(pushKey = "key-2", appId = "app.one")
                val third = httpPusher(pushKey = "key-1", appId = "app.two")
                val collection = PushersCollection.create(userId, first)

                val result =
                    collection
                        .register(second)
                        .register(third)

                result.pushers shouldContainExactly listOf(first, second, third)
            }

            it("replaces existing pusher with same appId and pushKey") {
                val existing =
                    httpPusher(
                        pushKey = "key-1",
                        appId = "app.one",
                        appDisplayName = "Old App",
                        deviceDisplayName = "Old Device",
                    )
                val updated =
                    httpPusher(
                        pushKey = "key-1",
                        appId = "app.one",
                        appDisplayName = "New App",
                        deviceDisplayName = "New Device",
                    )
                val other = httpPusher(pushKey = "key-2", appId = "app.other")
                val collection =
                    PushersCollection
                        .create(userId, existing)
                        .register(other)

                val result = collection.register(updated)

                result.pushers shouldHaveSize 2
                result.pushers shouldContainExactly listOf(other, updated)
            }

            it("does not match on appId alone") {
                val existing = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, existing)

                val result =
                    collection.register(
                        httpPusher(pushKey = "key-2", appId = "app.one", appDisplayName = "Other"),
                    )

                result.pushers shouldHaveSize 2
                result.pushers[0] shouldBe existing
                result.pushers[1].pusherId.pushKey shouldBe "key-2"
            }

            it("does not match on pushKey alone") {
                val existing = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, existing)

                val result =
                    collection.register(
                        httpPusher(pushKey = "key-1", appId = "app.two", appDisplayName = "Other"),
                    )

                result.pushers shouldHaveSize 2
                result.pushers[0] shouldBe existing
                result.pushers[1].pusherId.appId shouldBe "app.two"
            }
        }

        describe("remove") {
            it("removes matching pusher") {
                val toRemove = httpPusher(pushKey = "key-1", appId = "app.one")
                val toKeep = httpPusher(pushKey = "key-2", appId = "app.one")
                val collection =
                    PushersCollection
                        .create(userId, toRemove)
                        .register(toKeep)

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "key-1"))

                result.pushers shouldContainExactly listOf(toKeep)
                result.markedForRemoval() shouldBe false
            }

            it("marks collection for removal when last pusher is removed") {
                val only = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, only)

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "key-1"))

                result.pushers.shouldBeEmpty()
                result.markedForRemoval() shouldBe true
            }

            it("does nothing when appId and pushKey do not match") {
                val existing = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, existing)

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "other-key"))

                result.pushers shouldContainExactly listOf(existing)
                result.markedForRemoval() shouldBe false
            }

            it("prunes channel configuration of the removed device") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.ENABLED))

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "key-1"))

                result.channelConfig.keys shouldContainExactly listOf("key-2")
            }

            it("keeps configuration of a pushkey another registered pusher still uses") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-1", appId = "app.two"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "key-1"))

                result.channelConfigOf("key-1")?.statusOf(channel) shouldBe ChannelStatus.ENABLED
            }

            it("leaves no configuration behind when the last device is removed") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                val result = collection.remove(PusherId(appId = "app.one", pushKey = "key-1"))

                result.channelConfig.shouldBeEmpty()
                result.markedForRemoval() shouldBe true
            }
        }

        describe("removeAll") {
            it("removes every pusher registered for a rejected pushkey, whatever the appId") {
                val kept = httpPusher(pushKey = "key-2", appId = "app.one")
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-1", appId = "app.two"))
                        .register(kept)

                val result = collection.removeAll(setOf("key-1"))

                result.pushers shouldContainExactly listOf(kept)
            }

            it("removes the pushers of every rejected pushkey") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))

                val result = collection.removeAll(setOf("key-1", "key-2"))

                result.pushers.shouldBeEmpty()
                result.markedForRemoval() shouldBe true
            }

            it("returns the same instance when no registered pusher uses a rejected pushkey") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.removeAll(setOf("other-key")) shouldBeSameInstanceAs collection
            }

            it("returns the same instance when nothing was rejected") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.removeAll(emptySet()) shouldBeSameInstanceAs collection
            }

            it("prunes the channel configuration of the removed devices") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-1", appId = "app.two"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.ENABLED))

                val result = collection.removeAll(setOf("key-1"))

                result.channelConfig.keys shouldContainExactly listOf("key-2")
            }
        }

        describe("channelConfigOf") {
            it("returns an empty configuration for a registered but unconfigured device") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.channelConfigOf("key-1") shouldBe DeviceChannelConfig.EMPTY
            }

            it("returns null for a pushkey no registered pusher uses") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.channelConfigOf("other-key").shouldBeNull()
            }
        }

        describe("configureChannels") {
            it("merges changes and leaves absent channels untouched") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels(
                            "key-1",
                            mapOf(channel to ChannelStatus.ENABLED, otherChannel to ChannelStatus.DISABLED),
                        )

                val result = collection.configureChannels("key-1", mapOf(channel to ChannelStatus.DISABLED))

                val config = result.channelConfigOf("key-1").shouldNotBeNull()
                config.statusOf(channel) shouldBe ChannelStatus.DISABLED
                config.statusOf(otherChannel) shouldBe ChannelStatus.DISABLED
            }

            it("resets a channel to not_set and drops the device entry once nothing is configured") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                val result = collection.configureChannels("key-1", mapOf(channel to ChannelStatus.NOT_SET))

                result.channelConfigOf("key-1")?.statusOf(channel) shouldBe ChannelStatus.NOT_SET
                result.channelConfig.shouldBeEmpty()
            }

            it("keeps the device entry when another channel stays configured") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels(
                            "key-1",
                            mapOf(channel to ChannelStatus.ENABLED, otherChannel to ChannelStatus.DISABLED),
                        )

                val result = collection.configureChannels("key-1", mapOf(channel to ChannelStatus.NOT_SET))

                val config = result.channelConfigOf("key-1").shouldNotBeNull()
                config.statusOf(channel) shouldBe ChannelStatus.NOT_SET
                config.statusOf(otherChannel) shouldBe ChannelStatus.DISABLED
                result.channelConfig.keys shouldContainExactly listOf("key-1")
            }

            it("shares one configuration between two pushers with the same pushkey") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-1", appId = "app.two"))

                val result = collection.configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                result.channelConfig.keys shouldContainExactly listOf("key-1")
            }

            it("returns the same instance for an empty change set") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.configureChannels("key-1", emptyMap()) shouldBeSameInstanceAs collection
            }

            it("rejects an unknown pushkey, even for an empty change set") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                shouldThrow<PusherNotFoundException> {
                    collection.configureChannels("other-key", mapOf(channel to ChannelStatus.ENABLED))
                }
                shouldThrow<PusherNotFoundException> { collection.configureChannels("other-key", emptyMap()) }
            }

            it("does not leak the pushkey in the error message") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                val thrown =
                    shouldThrow<PusherNotFoundException> {
                        collection.configureChannels(
                            "secret-token",
                            emptyMap(),
                        )
                    }

                thrown.message.orEmpty() shouldNotContain "secret-token"
            }
        }

        describe("register") {
            it("keeps the channel configuration of a re-registered device") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                val result =
                    collection.register(
                        httpPusher(pushKey = "key-1", appId = "app.one", appDisplayName = "Renamed"),
                    )

                result.channelConfigOf("key-1")?.statusOf(channel) shouldBe ChannelStatus.ENABLED
            }
        }

        describe("pushersWithChannelEnabled") {
            it("returns only pushers whose device has the channel enabled") {
                val enabled = httpPusher(pushKey = "key-1", appId = "app.one")
                val disabled = httpPusher(pushKey = "key-2", appId = "app.one")
                val unconfigured = httpPusher(pushKey = "key-3", appId = "app.one")
                val collection =
                    PushersCollection
                        .create(userId, enabled)
                        .register(disabled)
                        .register(unconfigured)
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.DISABLED))

                collection.pushersWithChannelEnabled(channel) shouldContainExactly listOf(enabled)
            }

            it("returns every device the channel is enabled on") {
                val first = httpPusher(pushKey = "key-1", appId = "app.one")
                val second = httpPusher(pushKey = "key-2", appId = "app.one")
                val collection =
                    PushersCollection
                        .create(userId, first)
                        .register(second)
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.ENABLED))

                collection.pushersWithChannelEnabled(channel) shouldContainExactly listOf(first, second)
            }

            it("returns one pusher per pushkey when two app ids share a device") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-1", appId = "app.two"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.ENABLED))

                collection.pushersWithChannelEnabled(channel) shouldHaveSize 1
            }

            it("is empty when nothing is configured") {
                val collection = PushersCollection.create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))

                collection.pushersWithChannelEnabled(channel).shouldBeEmpty()
            }

            it("is empty when the channel is only enabled for another channel's key") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .configureChannels("key-1", mapOf(otherChannel to ChannelStatus.ENABLED))

                collection.pushersWithChannelEnabled(channel).shouldBeEmpty()
            }
        }

        describe("channelStatus") {
            it("aggregates enabled across devices") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.DISABLED))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.ENABLED))

                collection.channelStatus(channel) shouldBe ChannelStatus.ENABLED
            }

            it("ignores configuration of a pushkey no registered pusher uses") {
                val orphaned =
                    PushersCollection.reconstitute(
                        userId = userId,
                        pushers = listOf(httpPusher(pushKey = "key-1", appId = "app.one")),
                        channelConfig =
                            mapOf("gone" to DeviceChannelConfig.of(mapOf(channel to ChannelStatus.ENABLED))),
                    )

                orphaned.channelStatus(channel) shouldBe ChannelStatus.NOT_SET
            }

            it("matches the aggregation function for the same devices") {
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))
                        .configureChannels("key-1", mapOf(channel to ChannelStatus.NOT_SET))
                        .configureChannels("key-2", mapOf(channel to ChannelStatus.DISABLED))
                val configs = listOf("key-1", "key-2").map { collection.channelConfigOf(it).shouldNotBeNull() }

                collection.channelStatuses(listOf(channel, otherChannel)) shouldBe
                    ChannelStatusAggregation.statusesOf(configs, listOf(channel, otherChannel))
            }
        }
    })
