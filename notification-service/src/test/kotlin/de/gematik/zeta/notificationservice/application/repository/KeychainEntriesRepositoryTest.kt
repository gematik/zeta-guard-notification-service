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
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.HmacPseudonymizer
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushkeyOwners
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.BEFORE_V2
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V1
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V2
import de.gematik.zeta.notificationservice.testsupport.storageIdOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class KeychainEntriesRepositoryTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val otherUserId = UserId.parse("X110411676")
        val pushKey = "key-1"
        val historyId = "kvnr:${userId.value}/$pushKey" + "_history"
        val pusherId = PusherId(appId = "app.one", pushKey = "key-1")

        // The first entry takes the writes: before V2's activeFrom when that is V1.
        fun pseudonymizer(vararg entryIds: String) =
            HmacPseudonymizer(
                TestKeychain.keychain(
                    *TestKeychain.DEFAULT_ENTRIES.filter { it.id in entryIds }.toTypedArray(),
                    clock = if (entryIds.first() == V1) FixedClock(BEFORE_V2) else FixedClock(),
                ),
            )

        fun history(
            storageRecords: StorageRecordDAO,
            vararg keyIds: String,
        ) = DefaultDeviceMessageHistoryRepository(storageRecords, pseudonymizer(*keyIds), NoOpPayloadSealer(), 30.days)

        fun owners(
            storageRecords: StorageRecordDAO,
            vararg keyIds: String,
        ) = DefaultPushkeyOwnersRepository(storageRecords, pseudonymizer(*keyIds), NoOpPayloadSealer())

        fun message(text: String) =
            HistoryMessage(
                notificationId = NotificationId.random(),
                payload = JsonObject(mapOf("message" to JsonPrimitive(text))),
                sentAt = Instant.parse("2026-10-08T10:00:00Z"),
            )

        fun DefaultDeviceMessageHistoryRepository.texts(pushKey: String = "key-1") =
            findBy(
                userId,
                pushKey,
            ).shouldNotBeNull().messages.map {
                it.payload
                    .getValue("message")
                    .jsonPrimitive.content
            }

        it("finds a record under an older entry and updates it where it lies") {
            val records = InMemoryStorageRecordDAO()
            history(records, V1).append(userId, pushKey, message("one"))

            history(records, V2, V1).append(userId, pushKey, message("two"))

            records.findById(pseudonymizer(V1).storageIdOf(historyId)).shouldNotBeNull()
            records.findById(pseudonymizer(V2).storageIdOf(historyId)).shouldBeNull()
            history(records, V2, V1).texts() shouldContainExactly listOf("one", "two")
        }

        it("creates a new record under the active entry") {
            val records = InMemoryStorageRecordDAO()

            history(records, V2, V1).append(userId, pushKey, message("one"))

            pseudonymizer(V2, V1).writeKeychainId() shouldBe V2
            records.findById(pseudonymizer(V2, V1).storageIdOf(historyId)).shouldNotBeNull()
            records.findById(pseudonymizer(V1).storageIdOf(historyId)).shouldBeNull()
        }

        it("lets pods on either side of a rollout see each other's records") {
            val records = InMemoryStorageRecordDAO()
            history(records, V1, V2).append(userId, "key-a", message("from the old side"))
            history(records, V2, V1).append(userId, "key-b", message("from the new side"))

            history(records, V2, V1).texts("key-a") shouldContainExactly listOf("from the old side")
            history(records, V1, V2).texts("key-b") shouldContainExactly listOf("from the new side")
        }

        it("removes a record under every entry, so no older copy surfaces again") {
            val records = InMemoryStorageRecordDAO()
            owners(records, V1).update(pusherId) { PushkeyOwners.create(pusherId, userId) }
            owners(records, V2).update(pusherId) { PushkeyOwners.create(pusherId, userId) }

            owners(records, V2, V1).update(pusherId) { it?.release(setOf(userId)) }

            owners(records, V1).findBy(pusherId).shouldBeNull()
            owners(records, V2).findBy(pusherId).shouldBeNull()
        }

        it("deletes a device history under every entry, since one with a retention period only moves in an emergency") {
            val records = InMemoryStorageRecordDAO()
            history(records, V1).append(userId, pushKey, message("old side"))
            history(records, V2).append(userId, pushKey, message("new side"))

            history(records, V2, V1).delete(userId, pushKey)

            records.findById(pseudonymizer(V1).storageIdOf(historyId)).shouldBeNull()
            records.findById(pseudonymizer(V2).storageIdOf(historyId)).shouldBeNull()
        }

        it("lands an update that races a move on the moved record") {
            val raw = InMemoryStorageRecordDAO()
            owners(raw, V1).update(pusherId) { PushkeyOwners.create(pusherId, userId) }
            var movedFrom: String? = null
            val moving =
                object : StorageRecordDAO by raw {
                    override fun save(
                        recordId: String,
                        payload: StorageRecord,
                        expectedTransVerifier: Int?,
                        ttl: Duration?,
                    ) {
                        if (movedFrom == null) {
                            movedFrom = recordId
                            val record = checkNotNull(raw.findById(recordId))
                            val newId =
                                pseudonymizer(V2).storageIdOf(checkNotNull(logicalIdOf(record.payload)))
                            raw.move(recordId, newId, record.copy(id = newId), checkNotNull(record.transVerifier))
                        }
                        raw.save(recordId, payload, expectedTransVerifier, ttl)
                    }
                }

            owners(moving, V2, V1).update(pusherId) { it?.add(otherUserId) }

            raw.findById(checkNotNull(movedFrom)).shouldBeNull()
            owners(raw, V2, V1).findBy(pusherId).shouldNotBeNull().owners shouldBe setOf(userId, otherUserId)
        }

        it("removes the moved record too when a removal races a move") {
            val raw = InMemoryStorageRecordDAO()
            owners(raw, V1).update(pusherId) { PushkeyOwners.create(pusherId, userId) }
            var moved = false
            val moving =
                object : StorageRecordDAO by raw {
                    override fun delete(recordId: String) {
                        if (!moved) {
                            moved = true
                            val record = checkNotNull(raw.findById(recordId))
                            val newId =
                                pseudonymizer(V2).storageIdOf(checkNotNull(logicalIdOf(record.payload)))
                            raw.move(recordId, newId, record.copy(id = newId), checkNotNull(record.transVerifier))
                        }
                        raw.delete(recordId)
                    }
                }

            owners(moving, V2, V1).update(pusherId) { it?.release(setOf(userId)) }

            moved shouldBe true
            owners(raw, V2, V1).findBy(pusherId).shouldBeNull()
        }
    })
