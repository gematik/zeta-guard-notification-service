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
package de.gematik.zeta.notificationservice.adapter.outbound.persistence

import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class InMemoryStorageRecordDAOTest :
    DescribeSpec({
        val recordId = "record-1"

        fun record(payload: String = "payload"): StorageRecord =
            StorageRecord(id = recordId, payload = payload, keychainId = "")

        describe("save") {
            it("creates a record when none is expected and none exists") {
                val dao = InMemoryStorageRecordDAO()

                dao.save(recordId, record(), expectedTransVerifier = null)

                val stored = dao.findById(recordId).shouldNotBeNull()
                stored.payload shouldBe "payload"
                stored.transVerifier.shouldNotBeNull()
            }

            it("rejects a create when the record already exists") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(), expectedTransVerifier = null)

                shouldThrow<StaleRecordException> {
                    dao.save(recordId, record(), expectedTransVerifier = null)
                }
            }

            it("keeps the winner's record untouched when a competing create loses") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(payload = "winner"), expectedTransVerifier = null)
                val stamped = dao.findById(recordId).shouldNotBeNull().transVerifier

                shouldThrow<StaleRecordException> {
                    dao.save(recordId, record(payload = "loser"), expectedTransVerifier = null)
                }

                val stored = dao.findById(recordId).shouldNotBeNull()
                stored.payload shouldBe "winner"
                stored.transVerifier shouldBe stamped
            }

            it("accepts a write carrying the verifier that was read") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(), expectedTransVerifier = null)
                val read = dao.findById(recordId).shouldNotBeNull()

                dao.save(recordId, record(payload = "changed"), expectedTransVerifier = read.transVerifier)

                dao.findById(recordId).shouldNotBeNull().payload shouldBe "changed"
            }

            it("rejects a write carrying a stale verifier") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(), expectedTransVerifier = null)
                val stale = dao.findById(recordId).shouldNotBeNull().transVerifier
                dao.save(recordId, record(payload = "concurrent"), expectedTransVerifier = stale)

                shouldThrow<StaleRecordException> {
                    dao.save(recordId, record(payload = "lost update"), expectedTransVerifier = stale)
                }
                dao.findById(recordId).shouldNotBeNull().payload shouldBe "concurrent"
            }

            it("rejects a write expecting a record that does not exist") {
                val dao = InMemoryStorageRecordDAO()

                shouldThrow<StaleRecordException> {
                    dao.save(recordId, record(), expectedTransVerifier = 42)
                }
            }

            it("stamps a fresh verifier on every write") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(), expectedTransVerifier = null)
                val first = dao.findById(recordId).shouldNotBeNull().transVerifier

                dao.save(recordId, record(), expectedTransVerifier = first)

                dao.findById(recordId).shouldNotBeNull().transVerifier shouldNotBe first
            }

            it("ignores the verifier the caller put into the payload record") {
                val dao = InMemoryStorageRecordDAO()

                dao.save(recordId, record().copy(transVerifier = 7), expectedTransVerifier = null)

                dao.findById(recordId).shouldNotBeNull().transVerifier shouldNotBe 7
            }

            it("allows recreating a deleted record") {
                val dao = InMemoryStorageRecordDAO()
                dao.save(recordId, record(), expectedTransVerifier = null)
                dao.delete(recordId)

                dao.save(recordId, record(payload = "again"), expectedTransVerifier = null)

                dao.findById(recordId).shouldNotBeNull().payload shouldBe "again"
            }
        }

        describe("retention") {
            it("hides a record once its retention period has passed") {
                val clock = FixedClock()
                val dao = InMemoryStorageRecordDAO(clock)
                dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)

                clock.advanceBy(24.hours)

                dao.findById(recordId).shouldBeNull()
            }

            it("keeps a record without a ttl forever") {
                val clock = FixedClock()
                val dao = InMemoryStorageRecordDAO(clock)
                dao.save(recordId, record(), expectedTransVerifier = null)

                clock.advanceBy(365.days)

                dao.findById(recordId).shouldNotBeNull()
            }

            it("lets a create take over an expired record") {
                val clock = FixedClock()
                val dao = InMemoryStorageRecordDAO(clock)
                dao.save(recordId, record(payload = "stale"), expectedTransVerifier = null, ttl = 24.hours)
                clock.advanceBy(24.hours)

                dao.save(recordId, record(payload = "fresh"), expectedTransVerifier = null, ttl = 24.hours)

                dao.findById(recordId).shouldNotBeNull().payload shouldBe "fresh"
            }

            it("rejects a write against an expired record") {
                val clock = FixedClock()
                val dao = InMemoryStorageRecordDAO(clock)
                dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
                val verifier = dao.findById(recordId).shouldNotBeNull().transVerifier
                clock.advanceBy(24.hours)

                shouldThrow<StaleRecordException> {
                    dao.save(recordId, record(payload = "revived"), expectedTransVerifier = verifier)
                }
            }

            it("does not extend the retention period on a write") {
                val clock = FixedClock()
                val dao = InMemoryStorageRecordDAO(clock)
                dao.save(recordId, record(), expectedTransVerifier = null, ttl = 24.hours)
                val verifier = dao.findById(recordId).shouldNotBeNull().transVerifier

                clock.advanceBy(12.hours)
                dao.save(recordId, record(payload = "changed"), expectedTransVerifier = verifier, ttl = 24.hours)
                clock.advanceBy(12.hours)

                dao.findById(recordId).shouldBeNull()
            }
        }
    })
