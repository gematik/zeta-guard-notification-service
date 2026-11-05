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
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PushkeyOwners
import de.gematik.zeta.notificationservice.domain.model.TelematikId
import de.gematik.zeta.notificationservice.domain.model.UserId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration

class DefaultPushkeyOwnersRepositoryTest :
    DescribeSpec({
        val pusherId = PusherId(appId = "app.one", pushKey = "key-1")
        val kvnr = UserId.parse("X110411675")

        fun repository(storageRecords: StorageRecordDAO = InMemoryStorageRecordDAO()) =
            DefaultPushkeyOwnersRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
            )

        it("round-trips the owners, keeping the kind of each user id") {
            val repository = repository()
            val telematikId = UserId.parse("1-2-ARZT-Some-Doctor-01")
            repository.update(pusherId) { PushkeyOwners.create(pusherId, kvnr).add(telematikId) }

            val loaded = repository.findBy(pusherId).shouldNotBeNull()

            loaded.pusherId shouldBe pusherId
            loaded.owners shouldContainExactly setOf(kvnr, telematikId)
            loaded.owners.last().shouldBeInstanceOf<TelematikId>()
        }

        it("deletes the record when the last owner is released") {
            val storageRecords = InMemoryStorageRecordDAO()
            val repository = repository(storageRecords)
            repository.update(pusherId) { PushkeyOwners.create(pusherId, kvnr) }

            repository.update(pusherId) { it?.release(setOf(kvnr)) }

            repository.findBy(pusherId).shouldBeNull()
        }

        it("keeps pairs apart whose concatenation would be the same") {
            val repository = repository()
            val left = PusherId(appId = "app/one", pushKey = "key")
            val right = PusherId(appId = "app", pushKey = "one/key")
            repository.update(left) { PushkeyOwners.create(left, kvnr) }

            repository.findBy(right).shouldBeNull()
        }

        it("keeps the storage id within the column width for the longest pair, even without sealing") {
            val raw = InMemoryStorageRecordDAO()
            val savedIds = mutableListOf<String>()
            val recording =
                object : StorageRecordDAO by raw {
                    override fun save(
                        recordId: String,
                        payload: StorageRecord,
                        expectedTransVerifier: Int?,
                        ttl: Duration?,
                    ) {
                        savedIds += recordId
                        raw.save(recordId, payload, expectedTransVerifier, ttl)
                    }
                }
            val repository = repository(recording)
            val longest = PusherId(appId = "a".repeat(64), pushKey = "k".repeat(512))

            repository.update(longest) { PushkeyOwners.create(longest, kvnr) }

            savedIds.single().length shouldBeLessThanOrEqual 450
            repository.findBy(longest).shouldNotBeNull()
        }
    })
