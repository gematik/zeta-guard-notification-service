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
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.NoOpPseudonymizer
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.IdempotencyKey
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.SubmissionReceipt
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours

class DefaultIdempotencyRepositoryTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val channel = ChannelId.parse("epa.documents.new")
        val ttl = 24.hours

        val key =
            IdempotencyKey(
                userId = userId,
                channel = channel,
                key = "9b5f3f2e-4c2a-4d3b-9c1e-6a0f1b2c3d4e",
            )

        fun repository(clock: FixedClock = FixedClock()) =
            DefaultIdempotencyRepository(
                storageRecords = InMemoryStorageRecordDAO(clock),
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
                ttl = ttl,
            )

        fun receipt(clock: FixedClock) =
            SubmissionReceipt(
                notificationId = NotificationId.random(),
                status = NotificationStatus.ACCEPTED,
                channel = channel,
                acceptedAt = clock.now(),
            )

        describe("remember and find") {
            it("replays the remembered receipt") {
                val clock = FixedClock()
                val repository = repository(clock = clock)
                val remembered = receipt(clock)

                repository.remember(key, remembered)

                repository.find(key) shouldBe remembered
            }

            it("returns null for a key that was never remembered") {
                repository().find(key).shouldBeNull()
            }

            it("keeps the first receipt when a second remember races the same key") {
                val clock = FixedClock()
                val repository = repository(clock = clock)
                val winner = receipt(clock)
                val loser = receipt(clock)

                repository.remember(key, winner) shouldBe winner
                repository.remember(key, loser) shouldBe winner

                repository.find(key) shouldBe winner
            }

            it("remembers anew when only an expired receipt occupies the key") {
                val clock = FixedClock()
                val repository = repository(clock = clock)
                repository.remember(key, receipt(clock))
                clock.advanceBy(ttl)
                val fresh = receipt(clock)

                repository.remember(key, fresh) shouldBe fresh

                repository.find(key) shouldBe fresh
            }

            it("scopes by user, channel and key alike") {
                val clock = FixedClock()
                val repository = repository(clock = clock)
                repository.remember(key, receipt(clock))

                repository.find(key.copy(userId = UserId.parse("X110411676"))).shouldBeNull()
                repository.find(key.copy(channel = ChannelId.parse("epa.consent.changed"))).shouldBeNull()
                repository.find(key.copy(key = "another-key")).shouldBeNull()
                repository.find(key).shouldNotBeNull()
            }
        }

        describe("retention") {
            it("stops replaying once the retention period has passed") {
                val clock = FixedClock()
                val repository = repository(clock = clock)
                repository.remember(key, receipt(clock))

                clock.advanceBy(ttl)

                repository.find(key).shouldBeNull()
            }
        }
    })
