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
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.model.DeviceMessageHistory
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.historyLogicalId
import de.gematik.zeta.notificationservice.testsupport.legacyHistoryLogicalId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DefaultDeviceMessageHistoryRepositoryTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val otherUserId = UserId.parse("X110411676")
        val pushKey = "key-1"
        val logicalId = historyLogicalId(userId, pushKey)
        val legacyId = legacyHistoryLogicalId(userId, pushKey)
        val sentAt = Instant.parse("2026-08-05T10:15:30Z")

        fun storageRecords() = InMemoryStorageRecordDAO()

        fun repository(storageRecords: StorageRecordDAO = storageRecords()) =
            DefaultDeviceMessageHistoryRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
                ttl = 30.days,
            )

        fun seed(
            storageRecords: InMemoryStorageRecordDAO,
            payload: String,
            id: String = logicalId,
            ttl: Duration? = null,
        ) = storageRecords.save(
            id,
            StorageRecord(id = id, payload = payload, keychainId = ""),
            expectedTransVerifier = null,
            ttl = ttl,
        )

        fun payloadOf(
            id: String,
            vararg messages: HistoryMessage,
        ): String =
            buildJsonObject {
                put("userId", userId.value)
                put("pushKey", pushKey)
                putJsonArray("messages") {
                    messages.forEach { message ->
                        addJsonObject {
                            put("notificationId", message.notificationId.toString())
                            put("sentAt", message.sentAt.toString())
                            put("payload", message.payload)
                        }
                    }
                }
                put("_lid", id)
            }.toString()

        fun message(
            text: String,
            at: Instant = sentAt,
        ): HistoryMessage =
            HistoryMessage(
                notificationId = NotificationId.random(),
                payload = Json.decodeFromString<JsonObject>("""{"message":"$text"}"""),
                sentAt = at,
            )

        describe("append and findBy") {
            it("creates the history on the first append") {
                val repository = repository()
                val first = message("one")

                repository.append(userId, pushKey, first)

                val loaded = repository.findBy(userId, pushKey).shouldNotBeNull()
                loaded.userId.value shouldBe userId.value
                loaded.pushKey shouldBe pushKey
                loaded.messages shouldContainExactly listOf(first)
            }

            it("extends an existing history and keeps the append order") {
                val repository = repository()
                val first = message("one")
                val second = message("two")

                repository.append(userId, pushKey, first)
                repository.append(userId, pushKey, second)

                repository.findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly
                    listOf(first, second)
            }

            it("returns null for a device without history") {
                repository().findBy(userId, "key-unknown").shouldBeNull()
            }

            it("keeps the histories of two users that registered the same pushkey apart") {
                val repository = repository()
                val mine = message("mine")
                val theirs = message("theirs")

                repository.append(userId, pushKey, mine)
                repository.append(otherUserId, pushKey, theirs)

                repository.findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly listOf(mine)
                repository.findBy(otherUserId, pushKey).shouldNotBeNull().messages shouldContainExactly listOf(theirs)
            }

            it("drops the oldest message once the cap is reached") {
                val repository = repository()
                val messages = (1..DeviceMessageHistory.MAX_MESSAGES + 1).map { message("m$it") }

                messages.forEach { repository.append(userId, pushKey, it) }

                val loaded = repository.findBy(userId, pushKey).shouldNotBeNull()
                loaded.messages shouldHaveSize DeviceMessageHistory.MAX_MESSAGES
                loaded.messages shouldContainExactly messages.drop(1)
            }
        }

        describe("storage id") {
            it("stays within the id column for the longest pushkey the API allows") {
                val raw = storageRecords()
                val writtenIds = mutableListOf<String>()
                val recording =
                    object : StorageRecordDAO by raw {
                        override fun save(
                            recordId: String,
                            payload: StorageRecord,
                            expectedTransVerifier: Int?,
                            ttl: Duration?,
                        ) {
                            writtenIds += recordId
                            raw.save(recordId, payload, expectedTransVerifier, ttl)
                        }
                    }
                val longPushKey = "k".repeat(512)
                val first = message("one")

                repository(recording).append(userId, longPushKey, first)

                writtenIds.single().length shouldBeLessThanOrEqual 450
                repository(raw).findBy(userId, longPushKey).shouldNotBeNull().messages shouldContainExactly
                    listOf(first)
            }
        }

        describe("legacy id") {
            it("reads a history still stored under the legacy id") {
                val storageRecords = storageRecords()
                val old = message("old")
                seed(storageRecords, payloadOf(legacyId, old), id = legacyId)

                repository(storageRecords).findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly
                    listOf(old)
            }

            it("moves it to the hashed id on the next append") {
                val storageRecords = storageRecords()
                val old = message("old")
                seed(storageRecords, payloadOf(legacyId, old), id = legacyId, ttl = 30.days)
                val new = message("new")

                repository(storageRecords).append(userId, pushKey, new)

                storageRecords.findById(legacyId).shouldBeNull()
                val moved = storageRecords.findById(logicalId).shouldNotBeNull()
                logicalIdOf(moved.payload) shouldBe logicalId
                repository(storageRecords).findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly
                    listOf(old, new)
            }

            it("keeps the retention deadline of the moved record") {
                val clock = FixedClock()
                val storageRecords = InMemoryStorageRecordDAO(clock)
                seed(storageRecords, payloadOf(legacyId, message("old")), id = legacyId, ttl = 30.days)
                clock.advanceBy(29.days)

                repository(storageRecords).append(userId, pushKey, message("new"))
                clock.advanceBy(1.days)

                repository(storageRecords).findBy(userId, pushKey).shouldBeNull()
            }

            it("merges both records when an old instance recreated the legacy one, without writing the merge back") {
                val storageRecords = storageRecords()
                val first = message("first", sentAt)
                val second = message("second", sentAt + 1.minutes)
                val third = message("third", sentAt + 2.minutes)
                seed(storageRecords, payloadOf(logicalId, first, third), ttl = 30.days)
                seed(storageRecords, payloadOf(legacyId, second), id = legacyId, ttl = 30.days)
                val fourth = message("fourth", sentAt + 3.minutes)

                repository(storageRecords).append(userId, pushKey, fourth)

                repository(storageRecords).findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly
                    listOf(first, second, third, fourth)
                storageRecords.findById(legacyId).shouldNotBeNull().payload shouldBe payloadOf(legacyId, second)
            }

            it("caps the merged history") {
                val storageRecords = storageRecords()
                val messages = (1..DeviceMessageHistory.MAX_MESSAGES + 1).map { message("m$it", sentAt + it.minutes) }
                seed(storageRecords, payloadOf(legacyId, *messages.take(2).toTypedArray()), id = legacyId)
                seed(storageRecords, payloadOf(logicalId, *messages.drop(2).toTypedArray()))

                repository(storageRecords).findBy(userId, pushKey).shouldNotBeNull().messages shouldContainExactly
                    messages.drop(1)
            }

            it("deletes the history under both ids") {
                val storageRecords = storageRecords()
                seed(storageRecords, payloadOf(legacyId, message("old")), id = legacyId)
                seed(storageRecords, payloadOf(logicalId, message("new")))

                repository(storageRecords).delete(userId, pushKey)

                storageRecords.findById(legacyId).shouldBeNull()
                storageRecords.findById(logicalId).shouldBeNull()
            }

            it("loses no message when appends race the move") {
                val storageRecords = storageRecords()
                seed(storageRecords, payloadOf(legacyId, message("old")), id = legacyId)
                val repository = repository(storageRecords)
                val appendsPerThread = 20
                val threads =
                    List(2) {
                        Thread.ofPlatform().start {
                            repeat(appendsPerThread) { repository.append(userId, pushKey, message("m")) }
                        }
                    }
                threads.forEach { it.join() }

                storageRecords.findById(legacyId).shouldBeNull()
                repository.findBy(userId, pushKey).shouldNotBeNull().messages shouldHaveSize 2 * appendsPerThread + 1
            }
        }

        describe("optimistic locking") {
            it("re-applies the append when a concurrent write intervenes") {
                val raw = storageRecords()
                val interfering =
                    object : StorageRecordDAO by raw {
                        private var pending = true

                        override fun save(
                            recordId: String,
                            payload: StorageRecord,
                            expectedTransVerifier: Int?,
                            ttl: Duration?,
                        ) {
                            if (pending) {
                                pending = false
                                repository(raw).append(userId, pushKey, message("sneaky"))
                            }
                            raw.save(recordId, payload, expectedTransVerifier, ttl)
                        }
                    }
                val repository = repository(interfering)

                repository.append(userId, pushKey, message("one"))

                val messages = repository.findBy(userId, pushKey).shouldNotBeNull().messages
                messages.map { it.payload.toString() } shouldContainExactly
                    listOf("""{"message":"sneaky"}""", """{"message":"one"}""")
            }

            it("loses no message under concurrent appends") {
                val repository = repository()
                val appendsPerThread = 20
                val threads =
                    List(2) {
                        Thread.ofPlatform().start {
                            repeat(appendsPerThread) { repository.append(userId, pushKey, message("m")) }
                        }
                    }
                threads.forEach { it.join() }

                val messages = repository.findBy(userId, pushKey).shouldNotBeNull().messages
                messages shouldHaveSize 2 * appendsPerThread
                messages.map { it.notificationId }.toSet() shouldHaveSize 2 * appendsPerThread
            }
        }

        describe("schema evolution") {
            it("loads a record written before any message was appended") {
                val storageRecords = storageRecords()
                seed(storageRecords, """{"userId":"${userId.value}","pushKey":"$pushKey"}""")

                repository(storageRecords)
                    .findBy(userId, pushKey)
                    .shouldNotBeNull()
                    .messages
                    .shouldBeEmpty()
            }

            it("loads a record carrying a field written by a newer version") {
                val storageRecords = storageRecords()
                seed(
                    storageRecords,
                    """{"userId":"${userId.value}","pushKey":"$pushKey","someFutureField":{"a":1}}""",
                )

                repository(storageRecords).findBy(userId, pushKey).shouldNotBeNull()
            }
        }

        describe("retention") {
            it("stops returning the history once the retention period has passed") {
                val clock = FixedClock()
                val repository = repository(InMemoryStorageRecordDAO(clock))
                repository.append(userId, pushKey, message("one"))

                clock.advanceBy(30.days)

                repository.findBy(userId, pushKey).shouldBeNull()
            }

            it("counts the retention period from the first message, not the last") {
                val clock = FixedClock()
                val repository = repository(InMemoryStorageRecordDAO(clock))
                repository.append(userId, pushKey, message("one"))
                clock.advanceBy(29.days)
                repository.append(userId, pushKey, message("two"))

                clock.advanceBy(1.days)

                repository.findBy(userId, pushKey).shouldBeNull()
            }
        }
    })
