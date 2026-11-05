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
package de.gematik.zeta.notificationservice.application.repository.encryption

import de.gematik.zeta.notificationservice.adapter.outbound.persistence.InMemoryStorageRecordDAO
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.DefaultPushkeyOwnersRepository
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.HmacPseudonymizer
import de.gematik.zeta.notificationservice.application.repository.withLogicalId
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushkeyOwners
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.BEFORE_V2
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.Entry
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V1
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V2
import de.gematik.zeta.notificationservice.testsupport.storageIdOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class KeychainRotationJobTest :
    DescribeSpec({
        val emergencyEntries =
            arrayOf(TestKeychain.DEFAULT_ENTRIES[0], Entry(V2, "2026-01-01T00:00:00Z", emergency = true))

        fun hexId(prefix: String): String = prefix.padEnd(64, '1')

        fun keychain(
            vararg entries: Entry = TestKeychain.DEFAULT_ENTRIES,
            clock: FixedClock = FixedClock(),
        ): Keychain = TestKeychain.keychain(*entries, clock = clock)

        // The keychain as an instance sees it before V2 takes over the writes.
        fun beforeV2(vararg entries: Entry = TestKeychain.DEFAULT_ENTRIES): Keychain =
            keychain(*entries, clock = FixedClock(BEFORE_V2))

        fun sealer(keychain: Keychain = keychain()): PayloadSealer = PayloadSealerWithDek(keychain)

        fun job(
            records: StorageRecordDAO,
            keychain: Keychain = keychain(),
            sealingEnabled: Boolean = true,
        ): KeychainRotationJob = KeychainRotationJob(records, keychain, sealer(keychain), sealingEnabled)

        fun StorageRecordDAO.storeAggregate(
            keychain: Keychain,
            logicalId: String,
            payload: String = "{}",
            ttl: Duration? = null,
        ): String {
            val keychainId = keychain.writeId
            val storageId = HmacPseudonymizer(keychain).pseudonymize(logicalId, keychainId)
            save(storageId, sealer(keychain).seal(storageId, keychainId, withLogicalId(payload, logicalId)), null, ttl)
            return storageId
        }

        fun InMemoryStorageRecordDAO.racedOnFirstSave(race: () -> Unit): StorageRecordDAO {
            val raw = this
            return object : StorageRecordDAO by raw {
                private var pending = true

                override fun save(
                    recordId: String,
                    payload: StorageRecord,
                    expectedTransVerifier: Int?,
                    ttl: Duration?,
                ) {
                    if (pending) {
                        pending = false
                        race()
                    }
                    raw.save(recordId, payload, expectedTransVerifier, ttl)
                }
            }
        }

        fun InMemoryStorageRecordDAO.racedOnFirstMove(race: () -> Unit): StorageRecordDAO {
            val raw = this
            return object : StorageRecordDAO by raw {
                private var pending = true

                override fun move(
                    fromId: String,
                    toId: String,
                    record: StorageRecord,
                    expectedTransVerifier: Int,
                ) {
                    if (pending) {
                        pending = false
                        race()
                    }
                    raw.move(fromId, toId, record, expectedTransVerifier)
                }
            }
        }

        fun owners(
            records: StorageRecordDAO,
            keychain: Keychain = keychain(),
        ) = DefaultPushkeyOwnersRepository(records, HmacPseudonymizer(keychain), sealer(keychain))

        describe("rotate") {
            it("moves every record of an older entry to the current one and keeps its plaintext") {
                val records = InMemoryStorageRecordDAO()
                val logicalIds = listOf("kvnr:X000000001_pushers", "kvnr:X000000002_pushers", "kvnr:X000000003_pushers")
                val oldIds = logicalIds.map { records.storeAggregate(beforeV2(), it) }
                records.countLiveRecordsPerKeychainId() shouldBe mapOf(V1 to 3L)

                job(records).rotate()

                oldIds.forEach { records.findById(it).shouldBeNull() }
                logicalIds.forEach { logicalId ->
                    val moved =
                        records
                            .findById(
                                HmacPseudonymizer(keychain()).storageIdOf(logicalId),
                            ).shouldNotBeNull()
                    moved.keychainId shouldBe V2
                    sealer().open(moved) shouldBe withLogicalId("{}", logicalId)
                }
                records.countLiveRecordsPerKeychainId() shouldBe mapOf(V2 to 3L)
            }

            it("leaves records with a retention period under the older entry until they expire") {
                val records = InMemoryStorageRecordDAO()
                val id = records.storeAggregate(beforeV2(), "receipt_idempotency", ttl = 24.hours)

                job(records).rotate()

                records.findById(id).shouldNotBeNull().keychainId shouldBe V1
            }

            it("moves records with a retention period too after an emergency entry, keeping their expiry") {
                val clock = FixedClock()
                val records = InMemoryStorageRecordDAO(clock)
                val old = records.storeAggregate(beforeV2(*emergencyEntries), "receipt_idempotency", ttl = 24.hours)

                job(records, keychain(*emergencyEntries)).rotate()

                records.findById(old).shouldBeNull()
                val movedId =
                    HmacPseudonymizer(
                        keychain(*emergencyEntries),
                    ).storageIdOf("receipt_idempotency")
                records.findById(movedId).shouldNotBeNull().keychainId shouldBe V2
                clock.advanceBy(25.hours)
                records.findById(movedId).shouldBeNull()
            }

            it("never moves a record back from an entry newer than the current one") {
                val records = InMemoryStorageRecordDAO()
                val underV1 = records.storeAggregate(beforeV2(), "kvnr:X000000001_pushers")
                val underV2 = records.storeAggregate(keychain(), "kvnr:X000000002_pushers")

                job(records, beforeV2()).rotate()

                records.findById(underV1).shouldNotBeNull()
                records.findById(underV2).shouldNotBeNull()
            }

            it("yields to a write that lands while it moves a record, and moves it on the next run") {
                val records = InMemoryStorageRecordDAO()
                val id = records.storeAggregate(beforeV2(), "kvnr:X000000001_pushers")
                val writeMeanwhile = {
                    val current = checkNotNull(records.findById(id))
                    val payload = withLogicalId("""{"written":"meanwhile"}""", "kvnr:X000000001_pushers")
                    records.save(
                        id,
                        sealer().seal(id, V1, payload),
                        current.transVerifier,
                    )
                }

                job(records.racedOnFirstMove(writeMeanwhile)).rotate()

                records.findById(id).shouldNotBeNull()

                job(records).rotate()

                records.findById(id).shouldBeNull()
                val moved =
                    records.findById(
                        HmacPseudonymizer(keychain()).storageIdOf("kvnr:X000000001_pushers"),
                    )
                sealer().open(moved.shouldNotBeNull()) shouldBe
                    withLogicalId("""{"written":"meanwhile"}""", "kvnr:X000000001_pushers")
            }

            it("lands an update that races a move on the moved record") {
                val records = InMemoryStorageRecordDAO()
                val pusherId = PusherId(appId = "app.one", pushKey = "key-1")
                val first = UserId.parse("X110411675")
                val second = UserId.parse("X110411676")
                owners(records, beforeV2()).update(pusherId) { PushkeyOwners.create(pusherId, first) }
                val racing = records.racedOnFirstSave { job(records).rotate() }

                owners(racing).update(pusherId) { it?.add(second) }

                records.countLiveRecordsPerKeychainId() shouldBe mapOf(V2 to 1L)
                owners(records).findBy(pusherId).shouldNotBeNull().owners shouldBe setOf(first, second)
            }

            it("stops the run after ten failures in a row and keeps every record where it was") {
                val records = InMemoryStorageRecordDAO()
                val foreign = sealer(keychain(Entry(V1, "2025-01-01T00:00:00Z", dek = ByteArray(32) { 42 })))
                val ids = (1..15).map { hexId(it.toString(16).padStart(2, '0')) }
                ids.forEach { records.save(it, foreign.seal(it, V1, "payload-$it"), null) }
                var reads = 0
                val counting =
                    object : StorageRecordDAO by records {
                        override fun findById(recordId: String): StorageRecord? {
                            reads++
                            return records.findById(recordId)
                        }
                    }

                job(counting).rotate()

                reads shouldBe 10
                records.countLiveRecordsPerKeychainId() shouldBe mapOf(V1 to 15L)
                ids.forEach { foreign.open(checkNotNull(records.findById(it))) shouldBe "payload-$it" }
            }

            it("leaves a record that names no logical id where it is") {
                val records = InMemoryStorageRecordDAO()
                val id = hexId("ab")
                records.save(id, sealer().seal(id, V1, "{}"), expectedTransVerifier = null)

                job(records).rotate()

                sealer().open(records.findById(id).shouldNotBeNull()) shouldBe "{}"
            }

            it("walks no record while no older entry holds live records") {
                val records = InMemoryStorageRecordDAO()
                records.storeAggregate(keychain(), "kvnr:X000000001_pushers")
                var walks = 0
                val counting =
                    object : StorageRecordDAO by records {
                        override fun findUnderOtherKeychainId(
                            keychainId: String,
                            fromId: String,
                            untilId: String,
                            withRetention: Boolean,
                        ): Map<String, String> {
                            walks++
                            return records.findUnderOtherKeychainId(
                                keychainId,
                                fromId,
                                untilId,
                                withRetention,
                            )
                        }
                    }

                job(counting).rotate()

                walks shouldBe 0
            }

            it("does nothing while sealing is off") {
                val records = InMemoryStorageRecordDAO()
                records.storeAggregate(beforeV2(), "kvnr:X000000001_pushers")

                job(records, sealingEnabled = false).rotate()

                records.countLiveRecordsPerKeychainId() shouldBe mapOf(V1 to 1L)
            }
        }
    })
