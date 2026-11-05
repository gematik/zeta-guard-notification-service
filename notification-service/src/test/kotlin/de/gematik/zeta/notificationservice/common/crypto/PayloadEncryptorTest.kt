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
package de.gematik.zeta.notificationservice.common.crypto

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.repository.encryption.NoOpPayloadSealer
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PayloadEncryptorTest :
    DescribeSpec({
        val payloadSealer = NoOpPayloadSealer()

        describe("NoOpPayloadSealer") {
            it("open returns the payload unchanged") {
                val record = StorageRecord(id = "id", payload = "hello", keychainId = "")

                payloadSealer.open(record) shouldBe "hello"
            }

            it("seal returns the payload unchanged") {
                val sealed =
                    payloadSealer.seal(
                        storageId = "id",
                        keychainId = "",
                        payload = "hello",
                    )

                sealed.payload shouldBe "hello"
            }
        }
    })
