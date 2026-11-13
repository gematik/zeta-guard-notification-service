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
package de.gematik.zeta.notificationservice.application.usecase.managepusher

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.DefaultDeviceMessageHistoryRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyHoldersRepository
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyOwnersRepository
import de.gematik.zeta.notificationservice.application.repository.PushersRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.Pusher
import de.gematik.zeta.notificationservice.domain.model.PusherData
import de.gematik.zeta.notificationservice.domain.model.PusherEncryption
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import de.gematik.zeta.notificationservice.domain.model.PushersCollection
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ManagePusherServiceTest :
    DescribeSpec({
        val userId = UserId.parse("X110413001")
        val otherUserId = UserId.parse("X110413002")
        val logicalId = "kvnr:${userId.value}" + "_pushers"

        fun httpPusher(
            pushKey: String,
            appId: String = "app.one",
            deviceDisplayName: String = "Device",
            encryption: PusherEncryption? = null,
            append: Boolean = false,
        ): Pusher =
            Pusher.create(
                pusherId = PusherId(appId = appId, pushKey = pushKey),
                kind = "http",
                appDisplayName = "App",
                deviceDisplayName = deviceDisplayName,
                lang = "de",
                data = PusherData(url = "https://push.example/gateway"),
                encryption = encryption,
                append = append,
            )

        fun ownersRepository(storageRecords: StorageRecordDAO) =
            DefaultPushkeyOwnersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        fun holdersRepository(storageRecords: StorageRecordDAO) =
            DefaultPushkeyHoldersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        fun historyRepository(storageRecords: StorageRecordDAO) =
            DefaultDeviceMessageHistoryRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
                ttl = 30.days,
            )

        fun historyMessage() = HistoryMessage(NotificationId.random(), JsonObject(emptyMap()), Instant.DISTANT_PAST)

        // Real repositories over the given DAO with the no-op strategies that are active by default, so the
        // storage effects of a deregistration are observable rather than mocked away.
        fun service(
            storageRecords: StorageRecordDAO,
            encryptionEnabled: Boolean = false,
        ) = ManagePusherService(
            DefaultPushersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            ),
            ownersRepository(storageRecords),
            holdersRepository(storageRecords),
            historyRepository(storageRecords),
            FixedClock(),
            encryptionEnabled,
        )

        fun wiring(encryptionEnabled: Boolean = false): Pair<InMemoryStorageRecordDAO, ManagePusherService> =
            InMemoryStorageRecordDAO().let { it to service(it, encryptionEnabled) }

        describe("get") {
            it("returns an empty list for a user without pushers") {
                val (_, service) = wiring()

                service.get(userId).shouldBeEmpty()
            }

            it("returns the pushers registered for the user") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-1")
                service.register(userId, pusher)

                service.get(userId) shouldContainExactly listOf(pusher)
            }
        }

        describe("register") {
            it("creates a collection for the first pusher") {
                val (storageRecords, service) = wiring()

                service.register(userId, httpPusher(pushKey = "key-1"))

                storageRecords.findById(logicalId).shouldNotBeNull()
            }

            it("keeps pushers that differ in appId") {
                val (_, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.two"))

                service.get(userId) shouldHaveSize 2
            }

            it("replaces a pusher registered under the same appId and pushKey") {
                val (_, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-1", deviceDisplayName = "First"))
                service.register(userId, httpPusher(pushKey = "key-1", deviceDisplayName = "Second"))

                val pushers = service.get(userId)
                pushers shouldHaveSize 1
                pushers.single().deviceDisplayName shouldBe "Second"
            }
        }

        describe("key material") {
            val iss = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
            val keyIdentifier = "b7c1e0d2-4f39-4a51-9c8e-2d6a5f0b3e74"

            fun encryption(timeIssCreated: String = "2026-08"): PusherEncryption =
                PusherEncryption.create(
                    method = "aes-hmac-sha256",
                    timeIssCreated = timeIssCreated,
                    iss = iss,
                    keyIdentifier = keyIdentifier,
                )

            it("derives the material of time_iss_created at registration and never stores the ISS") {
                val (storageRecords, service) = wiring(encryptionEnabled = true)

                service.register(userId, httpPusher(pushKey = "key-1", encryption = encryption()))

                val material =
                    service
                        .get(userId)
                        .single()
                        .keyMaterial
                        .shouldNotBeNull()
                material.month shouldBe YearMonth.parse("2026-08")
                material shouldBe PusherKeyMaterial.initial(encryption())
                storageRecords.findById(logicalId).shouldNotBeNull().payload shouldNotContain iss
            }

            it("ignores the encryption block while encryption is disabled") {
                val (storageRecords, service) = wiring()

                service.register(userId, httpPusher(pushKey = "key-1", encryption = encryption()))

                service
                    .get(userId)
                    .single()
                    .keyMaterial
                    .shouldBeNull()
                storageRecords.findById(logicalId).shouldNotBeNull().payload shouldNotContain iss
            }

            it("requires the encryption block while encryption is enabled") {
                val (storageRecords, service) = wiring(encryptionEnabled = true)

                val thrown =
                    shouldThrow<MissingPusherFieldsException> {
                        service.register(userId, httpPusher(pushKey = "key-1"))
                    }

                thrown.fields shouldContainExactly listOf("encryption")
                storageRecords.findById(logicalId).shouldBeNull()
            }

            it("accepts time_iss_created one month ahead and rejects anything later, without echoing the ISS") {
                val (_, service) = wiring(encryptionEnabled = true)

                service.register(userId, httpPusher(pushKey = "key-1", encryption = encryption("2026-09")))
                val thrown =
                    shouldThrow<InvalidPusherException> {
                        service.register(userId, httpPusher(pushKey = "key-2", encryption = encryption("2026-10")))
                    }

                thrown.message.shouldNotBeNull() shouldNotContain iss
                service.get(userId) shouldHaveSize 1
            }

            it("replaces the material when the same pusher registers again") {
                val (_, service) = wiring(encryptionEnabled = true)
                service.register(userId, httpPusher(pushKey = "key-1", encryption = encryption("2026-07")))

                service.register(userId, httpPusher(pushKey = "key-1", encryption = encryption("2026-08")))

                service
                    .get(userId)
                    .single()
                    .keyMaterial
                    .shouldNotBeNull()
                    .month shouldBe YearMonth.parse("2026-08")
            }

            it("ratchets the material to the requested month and persists it") {
                val (_, service) = wiring(encryptionEnabled = true)
                val pusher = httpPusher(pushKey = "key-1", encryption = encryption("2026-06"))
                service.register(userId, pusher)

                val ratcheted =
                    service.currentKeyMaterial(userId, pusher.pusherId, YearMonth.parse("2026-08")).shouldNotBeNull()

                ratcheted.month shouldBe YearMonth.parse("2026-08")
                ratcheted shouldBe
                    PusherKeyMaterial.initial(encryption("2026-06")).ratchetTo(YearMonth.parse("2026-08"))
                service.get(userId).single().keyMaterial shouldBe ratcheted
            }

            it("never ratchets backwards") {
                val (_, service) = wiring(encryptionEnabled = true)
                val pusher = httpPusher(pushKey = "key-1", encryption = encryption("2026-08"))
                service.register(userId, pusher)

                val material = service.currentKeyMaterial(userId, pusher.pusherId, YearMonth.parse("2026-07"))

                material.shouldNotBeNull().month shouldBe YearMonth.parse("2026-08")
            }

            it("returns null for a pusher without material and for an unknown pusher") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-1")
                service.register(userId, pusher)

                service.currentKeyMaterial(userId, pusher.pusherId, YearMonth.parse("2026-08")).shouldBeNull()
                service
                    .currentKeyMaterial(
                        userId,
                        PusherId(appId = "app.one", pushKey = "key-absent"),
                        YearMonth.parse("2026-08"),
                    ).shouldBeNull()
            }
        }

        describe("pushers of other users (A_29978)") {
            it("rejects registering another user's pusher and leaves both users' pushers as they were") {
                val (_, service) = wiring()
                service.register(otherUserId, httpPusher(pushKey = "key-shared", deviceDisplayName = "Owner"))

                shouldThrow<PusherOfOtherUserException> {
                    service.register(userId, httpPusher(pushKey = "key-shared", deviceDisplayName = "Intruder"))
                }

                service.get(otherUserId).single().deviceDisplayName shouldBe "Owner"
                service.get(userId).shouldBeEmpty()
            }

            it("rejects it when append is set as well") {
                val (_, service) = wiring()
                service.register(otherUserId, httpPusher(pushKey = "key-shared"))

                shouldThrow<PusherOfOtherUserException> {
                    service.register(userId, httpPusher(pushKey = "key-shared", append = true))
                }

                service.get(otherUserId) shouldHaveSize 1
                service.get(userId).shouldBeEmpty()
            }

            it("keeps the message history of the owner when the registration is rejected (A_29989)") {
                val (storageRecords, service) = wiring()
                val history = historyRepository(storageRecords)
                service.register(otherUserId, httpPusher(pushKey = "key-shared"))
                history.append(otherUserId, "key-shared", historyMessage())

                shouldThrow<PusherOfOtherUserException> { service.register(userId, httpPusher(pushKey = "key-shared")) }

                history.findBy(otherUserId, "key-shared").shouldNotBeNull()
            }

            it("treats the same pushkey under another app ID as a different pusher") {
                val (_, service) = wiring()
                service.register(otherUserId, httpPusher(pushKey = "key-shared", appId = "app.two"))

                service.register(userId, httpPusher(pushKey = "key-shared", appId = "app.one"))

                service.get(otherUserId) shouldHaveSize 1
                service.get(userId) shouldHaveSize 1
            }

            it("rejects deregistering another user's pusher and keeps it") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-shared")
                service.register(otherUserId, pusher)

                shouldThrow<PusherOfOtherUserException> { service.remove(userId, pusher.pusherId) }

                service.get(otherUserId) shouldContainExactly listOf(pusher)
            }

            it("lets another user register the pusher once its owner deregistered it") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-shared")
                service.register(otherUserId, pusher)
                service.remove(otherUserId, pusher.pusherId)

                service.register(userId, pusher)

                service.get(userId) shouldContainExactly listOf(pusher)
            }

            it("lets another user register the pusher once the Push Gateway rejected its pushkey") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-shared")
                service.register(otherUserId, pusher)
                service.removeMany(otherUserId, setOf("key-shared"))

                service.register(userId, pusher)

                service.get(userId) shouldContainExactly listOf(pusher)
            }

            it("keeps the caller's own registration when the caller registers again") {
                val (_, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-shared", deviceDisplayName = "First"))

                service.register(userId, httpPusher(pushKey = "key-shared", deviceDisplayName = "Second"))

                service.get(userId).single().deviceDisplayName shouldBe "Second"
            }

            it("lets a user re-register a pusher it holds even when the index names another user as well") {
                // Pushers shared through `append: true` before A_29978, or registered before the index existed,
                // are held by more than one user. Each of them keeps managing its own registration.
                val (storageRecords, service) = wiring()
                val pusher = httpPusher(pushKey = "key-shared")
                service.register(userId, pusher)
                ownersRepository(storageRecords).update(pusher.pusherId) { it?.add(otherUserId) }

                service.register(userId, httpPusher(pushKey = "key-shared", deviceDisplayName = "Renamed"))
                service.remove(userId, pusher.pusherId)

                service.get(userId).shouldBeEmpty()
            }

            it("keeps the pushkey holder index in step with registration and removal") {
                val (storageRecords, service) = wiring()
                val holders = holdersRepository(storageRecords)
                service.register(userId, httpPusher(pushKey = "key-shared", appId = "app.one"))
                service.register(userId, httpPusher(pushKey = "key-shared", appId = "app.two"))

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-shared"))
                holders.findBy("key-shared").shouldNotBeNull().holders shouldContainExactly setOf(userId)

                service.remove(userId, PusherId(appId = "app.two", pushKey = "key-shared"))
                holders.findBy("key-shared").shouldBeNull()
            }

            it("drops a pushkey the Push Gateway rejected from both indexes") {
                val (storageRecords, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-shared"))

                service.removeMany(userId, setOf("key-shared"))

                holdersRepository(storageRecords).findBy("key-shared").shouldBeNull()
                ownersRepository(
                    storageRecords,
                ).findBy(PusherId(appId = "app.one", pushKey = "key-shared")).shouldBeNull()
            }

            it("drops a removed pusher's owner from the index") {
                val (storageRecords, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-shared"))

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-shared"))

                ownersRepository(
                    storageRecords,
                ).findBy(PusherId(appId = "app.one", pushKey = "key-shared")).shouldBeNull()
            }

            it("is not blocked by its own earlier attempt that failed halfway") {
                val raw = InMemoryStorageRecordDAO()
                val failing =
                    object : StorageRecordDAO by raw {
                        var failPushersSave = true

                        override fun save(
                            recordId: String,
                            payload: StorageRecord,
                            expectedTransVerifier: Int?,
                            ttl: Duration?,
                        ) {
                            check(!(failPushersSave && recordId == logicalId)) { "simulated outage" }
                            raw.save(recordId, payload, expectedTransVerifier, ttl)
                        }
                    }
                val service = service(failing)

                shouldThrow<IllegalStateException> { service.register(userId, httpPusher(pushKey = "key-shared")) }
                failing.failPushersSave = false
                service.register(userId, httpPusher(pushKey = "key-shared"))

                service.get(userId) shouldHaveSize 1
            }
        }

        describe("message history on registration (A_29989)") {
            it("starts with an empty message history for a pushkey the user does not hold yet (A_29989)") {
                val (storageRecords, service) = wiring()
                val history = historyRepository(storageRecords)
                history.append(userId, "key-1", historyMessage())

                service.register(userId, httpPusher(pushKey = "key-1"))

                history.findBy(userId, "key-1").shouldBeNull()
            }

            it("keeps the message history when the user re-registers a pushkey it already holds (A_29989)") {
                val (storageRecords, service) = wiring()
                val history = historyRepository(storageRecords)
                service.register(userId, httpPusher(pushKey = "key-1"))
                history.append(userId, "key-1", historyMessage())

                service.register(userId, httpPusher(pushKey = "key-1", deviceDisplayName = "Renamed"))

                history.findBy(userId, "key-1").shouldNotBeNull()
            }

            it("deletes the message history on retry when the first attempt failed to delete it (A_29989)") {
                val raw = InMemoryStorageRecordDAO()
                val failing =
                    object : StorageRecordDAO by raw {
                        var failHistoryDelete = true

                        override fun delete(recordId: String) {
                            check(!(failHistoryDelete && recordId.endsWith("_history"))) { "simulated outage" }
                            raw.delete(recordId)
                        }
                    }
                val service = service(failing)
                val history = historyRepository(raw)
                history.append(userId, "key-1", historyMessage())

                shouldThrow<IllegalStateException> { service.register(userId, httpPusher(pushKey = "key-1")) }
                service.get(userId).shouldBeEmpty()
                failing.failHistoryDelete = false
                service.register(userId, httpPusher(pushKey = "key-1"))

                history.findBy(userId, "key-1").shouldBeNull()
                service.get(userId) shouldHaveSize 1
            }
        }

        describe("remove") {
            it("deletes the storage record when the last pusher is removed (A_29989)") {
                val (storageRecords, service) = wiring()
                val pusher = httpPusher(pushKey = "key-1")
                service.register(userId, pusher)

                service.remove(userId, pusher.pusherId)

                service.get(userId).shouldBeEmpty()
                storageRecords.findById(logicalId).shouldBeNull()
            }

            it("keeps the record when one of two pushers is removed") {
                val (storageRecords, service) = wiring()
                val kept = httpPusher(pushKey = "key-keep")
                val dropped = httpPusher(pushKey = "key-drop")
                service.register(userId, kept)
                service.register(userId, dropped)

                service.remove(userId, dropped.pusherId)

                service.get(userId) shouldContainExactly listOf(kept)
                storageRecords.findById(logicalId).shouldNotBeNull()
            }

            it("is a no-op for a user we hold no record for") {
                // Deregistration of a pusher nobody holds is idempotent.
                val (storageRecords, service) = wiring()

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-absent"))

                storageRecords.findById(logicalId).shouldBeNull()
            }

            it("is a no-op for an unknown pushKey of a known user") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-1")
                service.register(userId, pusher)

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-absent"))

                service.get(userId) shouldContainExactly listOf(pusher)
            }

            it("keeps the message history while another app ID still holds the pushkey (A_29989)") {
                val (storageRecords, service) = wiring()
                val history = historyRepository(storageRecords)
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.two"))
                history.append(userId, "key-1", historyMessage())

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-1"))

                history.findBy(userId, "key-1").shouldNotBeNull()
            }

            it("does not store anything when no pusher matched") {
                // Every store re-seals the record under a fresh DEK, so a no-op must stay a no-op.
                val raw = InMemoryStorageRecordDAO()
                val counting =
                    object : StorageRecordDAO by raw {
                        var saves = 0

                        override fun save(
                            recordId: String,
                            payload: StorageRecord,
                            expectedTransVerifier: Int?,
                            ttl: Duration?,
                        ) {
                            saves++
                            raw.save(recordId, payload, expectedTransVerifier, ttl)
                        }
                    }
                val service = service(counting)
                service.register(userId, httpPusher(pushKey = "key-1"))
                val savesAfterSetup = counting.saves

                service.remove(userId, PusherId(appId = "app.one", pushKey = "key-absent"))

                counting.saves shouldBe savesAfterSetup
            }
        }

        describe("removeRejected") {
            it("removes every pusher registered for a rejected pushkey, whatever the appId (A_27374)") {
                val (_, service) = wiring()
                val kept = httpPusher(pushKey = "key-2")
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.one"))
                service.register(userId, httpPusher(pushKey = "key-1", appId = "app.two"))
                service.register(userId, kept)

                service.removeMany(userId, setOf("key-1"))

                service.get(userId) shouldContainExactly listOf(kept)
            }

            it("deletes the storage record when the rejected pushkeys were the last ones") {
                val (storageRecords, service) = wiring()
                service.register(userId, httpPusher(pushKey = "key-1"))
                service.register(userId, httpPusher(pushKey = "key-2"))

                service.removeMany(userId, setOf("key-1", "key-2"))

                storageRecords.findById(logicalId).shouldBeNull()
            }

            it("deletes the message history of a rejected pushkey (A_29989)") {
                val (storageRecords, service) = wiring()
                val history = historyRepository(storageRecords)
                service.register(userId, httpPusher(pushKey = "key-1"))
                history.append(userId, "key-1", historyMessage())

                service.removeMany(userId, setOf("key-1"))

                history.findBy(userId, "key-1").shouldBeNull()
            }

            it("keeps the pushers of a known user when it holds none of the rejected pushkeys") {
                val (_, service) = wiring()
                val pusher = httpPusher(pushKey = "key-1")
                service.register(userId, pusher)

                service.removeMany(userId, setOf("key-of-another-user"))

                service.get(userId) shouldContainExactly listOf(pusher)
            }

            it("is a no-op for a user we hold no record for") {
                val (storageRecords, service) = wiring()

                service.removeMany(userId, setOf("key-1"))

                storageRecords.findById(logicalId).shouldBeNull()
            }
        }

        describe("user binding") {
            it("only ever touches the lookup of the user it was given") {
                // The guard against a future change that takes the user from the request instead of the token.
                val recording =
                    RecordingPushersRepository(
                        DefaultPushersRepository(
                            storageRecords = InMemoryStorageRecordDAO(),
                            pseudonymizer = NoOpPseudonymizer(),
                            payloadSealer = NoOpPayloadSealer(),
                        ),
                    )
                val service =
                    ManagePusherService(
                        recording,
                        ownersRepository(InMemoryStorageRecordDAO()),
                        holdersRepository(InMemoryStorageRecordDAO()),
                        historyRepository(InMemoryStorageRecordDAO()),
                        FixedClock(),
                        false,
                    )
                val pusher = httpPusher(pushKey = "key-1")

                service.register(userId, pusher)
                service.get(userId)
                service.remove(userId, pusher.pusherId)
                service.get(otherUserId)

                recording.looked
                    .filterNot { it == otherUserId.value }
                    .toSet() shouldBe setOf(userId.value)
                recording.saved.toSet() shouldBe setOf(userId.value)
            }
        }
    })

/** Records which user every lookup and store was made for, without changing behaviour. */
private class RecordingPushersRepository(
    private val delegate: PushersRepository,
) : PushersRepository {
    val looked = mutableListOf<String>()
    val saved = mutableListOf<String>()

    override fun findBy(userId: UserId): PushersCollection? {
        looked += userId.value
        return delegate.findBy(userId)
    }

    override fun update(
        userId: UserId,
        transform: (PushersCollection?) -> PushersCollection?,
    ) {
        saved += userId.value
        delegate.update(userId, transform)
    }
}
