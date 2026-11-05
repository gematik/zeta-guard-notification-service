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

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.testsupport.FakeHsmProxy
import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.BEFORE_V2
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.Entry
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V1
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import java.util.Optional
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.time.Instant

class KeychainTest :
    DescribeSpec({
        fun keychain(
            hsm: FakeHsmProxy = FakeHsmProxy(),
            clock: FixedClock = FixedClock(),
            json: String = TestKeychain.json(hsm),
        ): Keychain = Keychain(hsm, clock, Optional.of(json))

        describe("writeId") {
            it("names the newest entry whose activeFrom has passed") {
                keychain(clock = FixedClock(BEFORE_V2)).writeId shouldBe V1
                keychain(clock = FixedClock()).writeId shouldBe V2
            }

            it("does not depend on the order of the entries") {
                val hsm = FakeHsmProxy()
                val reversed = TestKeychain.json(hsm, *TestKeychain.DEFAULT_ENTRIES.reversedArray())

                keychain(hsm, json = reversed).writeId shouldBe V2
            }

            it("refuses to name one while no entry is active yet") {
                val keychain = keychain(clock = FixedClock(Instant.parse("2024-12-31T23:59:59Z")))

                shouldThrow<IllegalStateException> { keychain.writeId }
            }

            it("asks the hsm for nothing") {
                val hsm = FakeHsmProxy()

                keychain(hsm).writeId

                hsm.unwraps.get() shouldBe 0
            }
        }

        describe("key") {
            it("unwraps every entry once, on first use, with its id as aad") {
                val hsm = FakeHsmProxy()
                val keychain = keychain(hsm)

                keychain.key(V1).encoded shouldBe TestKeychain.dekOf(V1)
                keychain.key(V2).encoded shouldBe TestKeychain.dekOf(V2)
                keychain.key(V1).encoded shouldBe TestKeychain.dekOf(V1)

                hsm.unwraps.get() shouldBe 2
            }

            it("unwraps every entry under the kek it names, so a keychain can span a kek rotation") {
                val hsm = FakeHsmProxy()
                val underOldKek = TestKeychain.json(hsm, TestKeychain.DEFAULT_ENTRIES[0])
                hsm.wrapKekId = "notification-service-keychain-kek-aesgcm-v2"
                val underNewKek = TestKeychain.json(hsm, TestKeychain.DEFAULT_ENTRIES[1])
                val mixed =
                    JsonArray(
                        Json.parseToJsonElement(underOldKek).jsonArray + Json.parseToJsonElement(underNewKek).jsonArray,
                    ).toString()

                val keychain = keychain(hsm, json = mixed)

                keychain.key(V1).encoded shouldBe TestKeychain.dekOf(V1)
                keychain.key(V2).encoded shouldBe TestKeychain.dekOf(V2)
            }

            it("rejects an id the keychain does not list") {
                shouldThrow<IllegalStateException> { keychain().key("notification-service-keychain-v9") }
            }

            it("fails when an entry was wrapped for another id") {
                val hsm = FakeHsmProxy()
                val relabelled = TestKeychain.json(hsm).replace("\"$V1\"", "\"notification-service-keychain-v0\"")

                shouldThrow<HsmProxyException> { keychain(hsm, json = relabelled).key(V2) }
            }

            it("rejects keys that are not 64 bytes long together") {
                val hsm = FakeHsmProxy()
                val short = TestKeychain.json(hsm, Entry(V1, "2025-01-01T00:00:00Z", dek = ByteArray(16)))

                shouldThrow<IllegalStateException> { keychain(hsm, json = short).key(V1) }
            }

            it("unwraps the pseudonymization key of an entry along with its DEK, in one call") {
                val hsm = FakeHsmProxy()
                val keychain = keychain(hsm)

                keychain.pseudonymKey(V1).encoded shouldBe TestKeychain.pseudonymKeyOf(V1)
                keychain.pseudonymKey(V2).encoded shouldBe TestKeychain.pseudonymKeyOf(V2)
                keychain.key(V2).encoded shouldBe TestKeychain.dekOf(V2)

                hsm.unwraps.get() shouldBe 2
            }

            it("unwraps an emergency entry with the flag in its aad") {
                val hsm = FakeHsmProxy()
                val entries =
                    arrayOf(TestKeychain.DEFAULT_ENTRIES[0], Entry(V2, "2026-01-01T00:00:00Z", emergency = true))
                val keychain = keychain(hsm, json = TestKeychain.json(hsm, *entries))

                keychain.key(V2).encoded shouldBe TestKeychain.dekOf(V2)
                keychain.isEmergency(V1) shouldBe false
                keychain.isEmergency(V2) shouldBe true
            }

            it("fails when the emergency flag was changed after wrapping") {
                val hsm = FakeHsmProxy()
                val added = TestKeychain.json(hsm).replace("\"activeFrom\":", "\"emergency\":true,\"activeFrom\":")
                val entries =
                    arrayOf(TestKeychain.DEFAULT_ENTRIES[0], Entry(V2, "2026-01-01T00:00:00Z", emergency = true))
                val removed = TestKeychain.json(hsm, *entries).replace("\"emergency\":true,", "")

                shouldThrow<HsmProxyException> { keychain(hsm, json = added).key(V1) }
                shouldThrow<HsmProxyException> { keychain(hsm, json = removed).key(V1) }
            }

            it("zeroes every key on close and unwraps again on the next use") {
                val hsm = FakeHsmProxy()
                val keychain = keychain(hsm)
                val key = keychain.key(V2)

                keychain.close()

                key.isDestroyed shouldBe true
                keychain.key(V2).encoded shouldBe TestKeychain.dekOf(V2)
                hsm.unwraps.get() shouldBe 4
            }
        }

        describe("configuration") {
            it("is read from keychain-file instead of the value") {
                val hsm = FakeHsmProxy()
                val file = createTempFile("keychain", ".json")
                try {
                    file.writeText(TestKeychain.json(hsm))
                    val keychain =
                        Keychain(hsm, FixedClock(), Optional.empty(), Optional.of(file.toString()))

                    keychain.writeId shouldBe V2
                    keychain.key(V1).encoded shouldBe TestKeychain.dekOf(V1)
                } finally {
                    file.deleteIfExists()
                }
            }

            it("rejects the value and keychain-file together, and a file it cannot read") {
                val hsm = FakeHsmProxy()

                shouldThrow<IllegalStateException> {
                    Keychain(hsm, FixedClock(), Optional.of(TestKeychain.json(hsm)), Optional.of("/nonexistent"))
                }
                shouldThrow<IllegalStateException> {
                    Keychain(hsm, FixedClock(), Optional.empty(), Optional.of("/nonexistent/keychain.json"))
                }
            }

            it("is required once sealing needs it") {
                shouldThrow<IllegalStateException> { Keychain(FakeHsmProxy(), FixedClock(), Optional.empty()) }
                shouldThrow<IllegalStateException> { keychain(json = "[]") }
            }

            it("rejects an id without a -v<N> version") {
                val hsm = FakeHsmProxy()

                shouldThrow<IllegalStateException> {
                    keychain(
                        hsm,
                        json = TestKeychain.json(hsm, Entry("notification-service-record-dek", "2025-01-01T00:00:00Z")),
                    )
                }
            }

            it("rejects versions of different key ids and repeated versions") {
                val hsm = FakeHsmProxy()
                val foreign = Entry("other-service-record-dek-v2", "2026-01-01T00:00:00Z")
                val repeated = Entry(V1, "2026-01-01T00:00:00Z")

                shouldThrow<IllegalStateException> {
                    keychain(hsm, json = TestKeychain.json(hsm, TestKeychain.DEFAULT_ENTRIES[0], foreign))
                }
                shouldThrow<IllegalStateException> {
                    keychain(hsm, json = TestKeychain.json(hsm, TestKeychain.DEFAULT_ENTRIES[0], repeated))
                }
            }

            it("rejects an activeFrom that does not rise with the version") {
                val hsm = FakeHsmProxy()
                val json = TestKeychain.json(hsm, Entry(V1, "2026-01-01T00:00:00Z"), Entry(V2, "2026-01-01T00:00:00Z"))

                shouldThrow<IllegalStateException> { keychain(hsm, json = json) }
            }

            it("rejects a kek id that does not follow the A_28831 scheme") {
                val hsm = FakeHsmProxy()
                val json = TestKeychain.json(hsm).replace(FakeHsmProxy.KEK_ID, "dek-kek-v1")

                shouldThrow<IllegalStateException> { keychain(hsm, json = json) }
            }

            it("rejects a keychain that is no JSON array of complete entries, without echoing it") {
                val previousFormat =
                    """[{"id":"$V1","kekId":"${FakeHsmProxy.KEK_ID}","activeFrom":"2025-01-01T00:00:00Z",""" +
                        """"wrapped":"secret-sentinel"}]"""
                val partial = """[{"id":"$V1","wrapped":"secret-sentinel"}]"""
                listOf("not json", "{}", partial, previousFormat).forEach { json ->
                    shouldThrow<IllegalStateException> { keychain(json = json) }.message shouldNotContain "sentinel"
                }
            }

            it("rejects an activeFrom that is no instant") {
                val hsm = FakeHsmProxy()

                shouldThrow<IllegalStateException> {
                    keychain(hsm, json = TestKeychain.json(hsm, Entry(V1, "2025-01-01")))
                }
            }

            it("rejects wrapped keys that are no base64") {
                val hsm = FakeHsmProxy()
                val json = TestKeychain.json(hsm).replace(Regex("\"keys\":\"[^\"]*\""), "\"keys\":\"%%%\"")

                shouldThrow<IllegalStateException> { keychain(hsm, json = json).key(V1) }
            }
        }
    })
