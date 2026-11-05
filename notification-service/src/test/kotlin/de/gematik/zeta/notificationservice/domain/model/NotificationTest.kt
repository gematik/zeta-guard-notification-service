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

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock

class NotificationTest :
    DescribeSpec({
        val text = "Neuer Befund: Laborwerte vom 12.10."
        val notification =
            Notification(
                id = NotificationId.random(),
                userId = UserId.parse("X110414002"),
                channel = ChannelId.parse("epa.documents.new"),
                prio = Priority.entries.first(),
                payload = buildJsonObject { put("message", JsonPrimitive(text)) },
                reference = "trigger-42",
                status = NotificationStatus.ACCEPTED,
                acceptedAt = Clock.System.now(),
            )

        describe("toString") {
            it("does not expose the payload, the user or the reference") {
                val rendered = notification.toString()

                rendered shouldNotContain text
                rendered shouldNotContain "X110414002"
                rendered shouldNotContain "trigger-42"
            }

            it("still identifies the notification") {
                notification.toString() shouldContain notification.id.toString()
            }
        }
    })
