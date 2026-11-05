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
package de.gematik.zeta.notificationservice.application.repository.pseudonymization

import de.gematik.zeta.notificationservice.testsupport.FixedClock
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.BEFORE_V2
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.Entry
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V1
import de.gematik.zeta.notificationservice.testsupport.TestKeychain.V2
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class HmacPseudonymizerTest :
    DescribeSpec({
        // HMAC pads a short key with zeros to the block size, so "Jefe" padded to the 32 bytes of a keychain
        // entry is still the key of RFC 4231 test case 2.
        fun key(secret: String) = secret.encodeToByteArray().copyOf(32)

        fun entry(
            id: String,
            secret: String,
        ) = Entry(id, if (id == V1) "2025-01-01T00:00:00Z" else "2026-01-01T00:00:00Z", pseudonymKey = key(secret))

        fun pseudonymizer(secret: String = "Jefe") = HmacPseudonymizer(TestKeychain.keychain(entry(V1, secret)))

        fun HmacPseudonymizer.storageId(id: String) = pseudonymize(id, writeKeychainId())

        describe("pseudonymize") {
            it("computes hmac-sha256 of the identifier (RFC 4231 test case 2)") {
                pseudonymizer().storageId("what do ya want for nothing?") shouldBe
                    "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
            }

            it("produces 64 lowercase hex characters") {
                pseudonymizer().storageId("kvnr:X110411675_pushers") shouldMatch Regex("[0-9a-f]{64}")
            }

            it("is deterministic across instances") {
                pseudonymizer().storageId("kvnr:X110411675_pushers") shouldBe
                    pseudonymizer().storageId("kvnr:X110411675_pushers")
            }

            it("separates identifier domains by their label") {
                pseudonymizer().storageId("kvnr:X110411675_pushers") shouldNotBe
                    pseudonymizer().storageId("tid:X110411675_pushers")
            }

            it("changes with the key") {
                pseudonymizer().storageId("kvnr:X110411675_pushers") shouldNotBe
                    pseudonymizer(secret = "other").storageId("kvnr:X110411675_pushers")
            }

            it("uses the pseudonymization key of the entry, not its DEK") {
                val keychain = TestKeychain.keychain(Entry(V1, "2025-01-01T00:00:00Z", dek = key("Jefe")))

                HmacPseudonymizer(keychain).storageId("what do ya want for nothing?") shouldNotBe
                    pseudonymizer().storageId("what do ya want for nothing?")
            }
        }

        describe("keychain entries") {
            it("derives the storage id with the pseudonymization key of the entry it is given") {
                val pseudonymizer = HmacPseudonymizer(TestKeychain.keychain(entry(V1, "old"), entry(V2, "new")))

                pseudonymizer.pseudonymize("kvnr:X110411675_pushers", V1) shouldBe
                    pseudonymizer(secret = "old").storageId("kvnr:X110411675_pushers")
                pseudonymizer.pseudonymize("kvnr:X110411675_pushers", V2) shouldBe
                    pseudonymizer(secret = "new").storageId("kvnr:X110411675_pushers")
            }

            it("looks an id up under every entry, oldest first") {
                val pseudonymizer = HmacPseudonymizer(TestKeychain.keychain(entry(V1, "old"), entry(V2, "new")))

                pseudonymizer.lookupIds("kvnr:X110411675_pushers") shouldBe
                    listOf(
                        pseudonymizer(secret = "old").storageId("kvnr:X110411675_pushers"),
                        pseudonymizer(secret = "new").storageId("kvnr:X110411675_pushers"),
                    )
            }

            it("writes under the active entry, the older one until the newer one is active") {
                HmacPseudonymizer(TestKeychain.keychain(entry(V1, "old"), entry(V2, "new"))).writeKeychainId() shouldBe
                    V2
                HmacPseudonymizer(
                    TestKeychain.keychain(entry(V1, "old"), entry(V2, "new"), clock = FixedClock(BEFORE_V2)),
                ).writeKeychainId() shouldBe V1
            }

            it("looks an id up only under itself without pseudonymization") {
                NoOpPseudonymizer().writeKeychainId() shouldBe ""
                NoOpPseudonymizer().pseudonymize("kvnr:X110411675_pushers", keychainId = "") shouldBe
                    "kvnr:X110411675_pushers"
                NoOpPseudonymizer().lookupIds("kvnr:X110411675_pushers") shouldBe listOf("kvnr:X110411675_pushers")
            }
        }
    })
