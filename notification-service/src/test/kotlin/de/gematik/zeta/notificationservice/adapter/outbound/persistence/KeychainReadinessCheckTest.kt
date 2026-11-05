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

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyPort
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import de.gematik.zeta.notificationservice.testsupport.FakeHsmProxy
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.microprofile.health.HealthCheckResponse
import java.util.Optional

private class SwitchableHsmProxy(
    private val delegate: FakeHsmProxy = FakeHsmProxy(),
) : HsmProxyPort by delegate {
    var down = false

    override fun unwrapKeys(
        kekId: String,
        wrapped: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (down) throw HsmProxyException("HSM proxy Decrypt failed: UNAVAILABLE")
        return delegate.unwrapKeys(kekId, wrapped, aad)
    }
}

class KeychainReadinessCheckTest :
    DescribeSpec({
        fun keychain(
            hsm: HsmProxyPort,
            json: String = TestKeychain.json(FakeHsmProxy()),
        ): Keychain = Keychain(hsm, FixedClock(), Optional.of(json))

        fun check(
            keychain: Keychain,
            storageRecords: StorageRecordDAO = InMemoryStorageRecordDAO(),
        ): KeychainReadinessCheck = KeychainReadinessCheck(keychain, storageRecords, sealingEnabled = true)

        fun StorageRecordDAO.sealUnder(
            id: String,
            entryId: String,
        ) = save(id, StorageRecord(id = id, payload = "sealed", keychainId = entryId), null)

        describe("readiness") {
            it("is up without touching a key while sealing is off") {
                val hsm = FakeHsmProxy()
                val check =
                    KeychainReadinessCheck(
                        keychain(hsm),
                        InMemoryStorageRecordDAO(),
                        false,
                    )

                check.call().status shouldBe HealthCheckResponse.Status.UP
                hsm.unwraps.get() shouldBe 0
            }

            it("is up once the keychain is unwrapped") {
                val hsm = FakeHsmProxy()

                check(keychain(hsm)).call().status shouldBe HealthCheckResponse.Status.UP
                hsm.unwraps.get() shouldBe TestKeychain.DEFAULT_ENTRIES.size
            }

            it("stays up through an hsm outage once the keys are in memory") {
                val hsm = SwitchableHsmProxy()
                val check = check(keychain(hsm))
                check.call()

                hsm.down = true

                check.call().status shouldBe HealthCheckResponse.Status.UP
            }

            it("is down while the keychain cannot be unwrapped") {
                val hsm = SwitchableHsmProxy().apply { down = true }

                check(keychain(hsm)).call().status shouldBe HealthCheckResponse.Status.DOWN
            }

            it("is down when a single entry of the keychain does not unwrap") {
                val hsm = FakeHsmProxy()
                val relabelled =
                    TestKeychain.json(hsm).replace("\"${TestKeychain.V1}\"", "\"notification-service-keychain-v0\"")

                check(keychain(hsm, relabelled)).call().status shouldBe HealthCheckResponse.Status.DOWN
            }

            it("is down while live records are sealed under an entry the keychain does not hold") {
                val records = InMemoryStorageRecordDAO()
                records.sealUnder("record-1", TestKeychain.V1)
                records.sealUnder("record-2", "notification-service-keychain-v0")

                val response = check(keychain(FakeHsmProxy()), storageRecords = records).call()

                response.status shouldBe HealthCheckResponse.Status.DOWN
                response.data.get()["error"] shouldBe
                    "Live records are sealed under keychain entries it does not hold: {notification-service-keychain-v0=1}"
            }

            it("checks the records until every one is readable, and not again once it was") {
                val records = InMemoryStorageRecordDAO()
                records.sealUnder("record-1", "notification-service-keychain-v0")
                val check = check(keychain(FakeHsmProxy()), storageRecords = records)
                check.call().status shouldBe HealthCheckResponse.Status.DOWN

                records.delete("record-1")
                check.call().status shouldBe HealthCheckResponse.Status.UP

                records.sealUnder("record-2", "notification-service-keychain-v0")
                check.call().status shouldBe HealthCheckResponse.Status.UP
            }
        }
    })
