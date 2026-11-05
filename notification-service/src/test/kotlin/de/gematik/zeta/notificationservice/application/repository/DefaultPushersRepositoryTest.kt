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
package de.gematik.zeta.notificationservice.application.repository

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.ChannelStatus
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherEncryption
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class DefaultPushersRepositoryTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val channel = ChannelId.parse("epa.documents.new")
        val otherChannel = ChannelId.parse("epa.consent.changed")
        val logicalId = "kvnr:${userId.value}" + "_pushers"

        fun storageRecords() = InMemoryStorageRecordDAO()

        fun legacyPayload(extraField: String = ""): String =
            """
            {"userId":"${userId.value}","pushers":[{"pushKey":"key-1","appId":"app.one","kind":"http",
            "appDisplayName":"App","deviceDisplayName":"Device","lang":"de",
            "data":{"url":"https://push.example/gateway"}}]$extraField}
            """.trimIndent().replace("\n", "")

        fun seed(
            storageRecords: InMemoryStorageRecordDAO,
            payload: String,
        ) = storageRecords.save(
            logicalId,
            StorageRecord(id = logicalId, payload = payload, keychainId = ""),
            expectedTransVerifier = null,
        )

        fun repository(storageRecords: InMemoryStorageRecordDAO = storageRecords()) =
            DefaultPushersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        fun httpPusher(
            pushKey: String,
            appId: String,
        ): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = appId, pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = "Device",
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
            )

        describe("save and findBy") {
            it("persists the derived key material and never the iss") {
                val storageRecords = storageRecords()
                val iss = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
                val keyIdentifier = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"
                val material =
                    PusherKeyMaterial.initial(
                        PusherEncryption.create(
                            method = "aes-hmac-sha256",
                            timeIssCreated = "2026-09",
                            iss = iss,
                            keyIdentifier = keyIdentifier,
                        ),
                    )
                val pusher = httpPusher(pushKey = "key-1", appId = "app.one").withKeyMaterial(material)

                repository(storageRecords).update(userId) { PushersCollection.create(userId, pusher) }

                val payload = storageRecords.findById(logicalId).shouldNotBeNull().payload
                payload shouldNotContain iss
                payload shouldContain keyIdentifier
                val loaded = repository(storageRecords).findBy(userId).shouldNotBeNull()
                loaded.pushers.single().keyMaterial shouldBe material
            }

            it("stores collection and find returns it") {
                val repository = repository()
                val pusher = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, pusher)

                repository.update(userId) { collection }

                val loaded = repository.findBy(userId)
                loaded.shouldNotBeNull()
                loaded.userId.value shouldBe userId.value
                loaded.pushers shouldContainExactly listOf(pusher)
            }

            it("deletes storage record when collection is marked for removal") {
                val storageRecords = storageRecords()
                val repository = repository(storageRecords)
                val pusher = httpPusher(pushKey = "key-1", appId = "app.one")
                val collection = PushersCollection.create(userId, pusher)
                repository.update(userId) { collection }

                repository.update(userId) { it?.remove(PusherId(appId = "app.one", pushKey = "key-1")) }

                repository.findBy(userId).shouldBeNull()
                storageRecords.findById(logicalId) shouldBe null
            }

            it("round-trips the append flag") {
                val storageRecords = storageRecords()
                val pusher =
                    Pusher.create(
                        pusherId = PusherId(appId = "app.one", pushKey = "key-1"),
                        kind = "http",
                        appDisplayName = "App",
                        deviceDisplayName = "Device",
                        lang = "de",
                        data = PusherData(url = "https://push.example/gateway"),
                        append = true,
                    )

                repository(storageRecords).update(userId) { PushersCollection.create(userId, pusher) }

                repository(storageRecords)
                    .findBy(userId)
                    .shouldNotBeNull()
                    .pushers
                    .single()
                    .append shouldBe true
            }

            it("reads a pusher stored without the append flag as append = false") {
                val storageRecords = storageRecords()
                seed(storageRecords, legacyPayload())

                repository(storageRecords)
                    .findBy(userId)
                    .shouldNotBeNull()
                    .pushers
                    .single()
                    .append shouldBe false
            }

            it("returns null when user has no stored pushers") {
                repository().findBy(userId).shouldBeNull()
            }

            it("treats a stored collection without any pusher as an unknown user") {
                val storageRecords = storageRecords()
                seed(storageRecords, """{"userId":"${userId.value}","pushers":[],"channelConfig":{}}""")

                repository(storageRecords).findBy(userId).shouldBeNull()
            }
        }

        describe("channel configuration") {
            it("round-trips per-device channel statuses") {
                val repository = repository()
                val collection =
                    PushersCollection
                        .create(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                        .register(httpPusher(pushKey = "key-2", appId = "app.one"))
                        .configureChannels(
                            "key-1",
                            mapOf(channel to ChannelStatus.ENABLED, otherChannel to ChannelStatus.NOT_SET),
                        ).configureChannels("key-2", mapOf(channel to ChannelStatus.DISABLED))

                repository.update(userId) { collection }

                val loaded = repository.findBy(userId).shouldNotBeNull()
                loaded.channelConfig shouldBe collection.channelConfig
            }

            it("loads a record written before channel configuration existed") {
                val storageRecords = storageRecords()
                val repository = repository(storageRecords)
                seed(storageRecords, legacyPayload())

                val loaded = repository.findBy(userId).shouldNotBeNull()

                loaded.pushers shouldHaveSize 1
                loaded.channelConfig.shouldBeEmpty()
            }

            it("loads a record carrying a field written by a newer version") {
                val storageRecords = storageRecords()
                val repository = repository(storageRecords)
                seed(storageRecords, legacyPayload(extraField = ""","someFutureField":{"a":1}"""))

                repository.findBy(userId).shouldNotBeNull()
            }

            it("degrades an unrecognised status token to not_set rather than failing the read") {
                val storageRecords = storageRecords()
                val repository = repository(storageRecords)
                seed(
                    storageRecords,
                    legacyPayload(extraField = ""","channelConfig":{"key-1":{"epa.documents.new":"paused"}}"""),
                )

                val loaded = repository.findBy(userId).shouldNotBeNull()

                loaded.channelConfigOf("key-1")?.statusOf(channel) shouldBe ChannelStatus.NOT_SET
                loaded.channelStatus(channel).isActive() shouldBe false
            }

            it("loads a stored channel id that the current format rules would reject") {
                val storageRecords = storageRecords()
                val repository = repository(storageRecords)
                seed(
                    storageRecords,
                    legacyPayload(extraField = ""","channelConfig":{"key-1":{"no longer allowed":"enabled"}}"""),
                )

                val loaded = repository.findBy(userId).shouldNotBeNull()

                loaded.channelConfigOf("key-1")?.statuses?.keys shouldContainExactly
                    listOf(ChannelId.reconstitute("no longer allowed"))
            }
        }
    })
