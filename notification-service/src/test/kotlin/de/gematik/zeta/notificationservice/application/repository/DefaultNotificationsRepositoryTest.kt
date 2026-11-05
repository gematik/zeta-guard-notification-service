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
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DefaultNotificationsRepositoryTest :
    DescribeSpec({
        val userId = UserId.parse("X110411675")
        val channel = ChannelId.parse("epa.documents.new")
        val notificationId = NotificationId.parseOrNull("9b5f3f2e-4c2a-4d3b-9c1e-6a0f1b2c3d4e")!!
        val acceptedAt = Instant.parse("2026-08-05T10:15:30Z")
        val logicalId = "$notificationId" + "_notification"
        val payload = Json.decodeFromString<JsonObject>("""{"message":"Hello","document_id":"123"}""")

        fun storageRecords(clock: FixedClock = FixedClock(acceptedAt)) = InMemoryStorageRecordDAO(clock)

        fun repository(storageRecords: InMemoryStorageRecordDAO = storageRecords()) =
            DefaultNotificationsRepository(
                storageRecords = storageRecords,
                pseudonymizer = NoOpPseudonymizer(),
                payloadSealer = NoOpPayloadSealer(),
                ttl = 24.hours,
            )

        fun seed(
            storageRecords: InMemoryStorageRecordDAO,
            payload: String,
        ) = storageRecords.save(
            logicalId,
            StorageRecord(id = logicalId, payload = payload, keychainId = ""),
            expectedTransVerifier = null,
        )

        /** A record as the first version wrote it: no prio, status, payload or reference. */
        fun legacyPayload(extraField: String = ""): String =
            """
            {"id":"$notificationId","userId":"${userId.value}","channel":"${channel.value}",
            "acceptedAt":"$acceptedAt"$extraField}
            """.trimIndent().replace("\n", "")

        fun notification(
            id: NotificationId = notificationId,
            reference: String? = "trigger-42",
        ): Notification =
            Notification(
                id = id,
                userId = userId,
                channel = channel,
                prio = Priority.NORMAL,
                payload = payload,
                reference = reference,
                status = NotificationStatus.ACCEPTED,
                acceptedAt = acceptedAt,
            )

        describe("save and findBy") {
            it("stores a notification and find returns it") {
                val repository = repository()

                repository.save(notification())

                repository.findBy(notificationId).shouldNotBeNull() shouldBe notification()
            }

            it("round-trips an absent reference") {
                val repository = repository()

                repository.save(notification(reference = null))

                repository
                    .findBy(notificationId)
                    .shouldNotBeNull()
                    .reference
                    .shouldBeNull()
            }

            it("returns null for an unknown id") {
                repository().findBy(NotificationId.random()).shouldBeNull()
            }
        }

        describe("schema evolution") {
            it("loads a record written before prio, status, payload and reference existed") {
                val storageRecords = storageRecords()
                seed(storageRecords, legacyPayload())

                val loaded = repository(storageRecords).findBy(notificationId).shouldNotBeNull()

                loaded.prio shouldBe Priority.NORMAL
                loaded.status shouldBe NotificationStatus.ACCEPTED
                loaded.payload shouldBe JsonObject(emptyMap())
                loaded.reference.shouldBeNull()
            }

            it("loads a record carrying a field written by a newer version") {
                val storageRecords = storageRecords()
                seed(storageRecords, legacyPayload(extraField = ""","someFutureField":{"a":1}"""))

                repository(storageRecords).findBy(notificationId).shouldNotBeNull()
            }

            it("degrades an unrecognised status token to accepted rather than failing the read") {
                val storageRecords = storageRecords()
                seed(storageRecords, legacyPayload(extraField = ""","status":"dispatched""""))

                repository(storageRecords).findBy(notificationId).shouldNotBeNull().status shouldBe
                    NotificationStatus.ACCEPTED
            }
        }

        describe("retention") {
            it("stops returning a notification once the retention period has passed") {
                val clock = FixedClock(acceptedAt)
                val repository = repository(storageRecords(clock))
                repository.save(notification())

                clock.advanceBy(24.hours)

                repository.findBy(notificationId).shouldBeNull()
            }
        }
    })
