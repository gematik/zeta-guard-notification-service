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

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.math.BigInteger

class KeyVersionTest :
    DescribeSpec({
        val v1 = "notification-service-keychain-kek-aesgcm-v1"
        val v2 = "notification-service-keychain-kek-aesgcm-v2"

        describe("isSupersededVersion") {
            it("supersedes an older version of the same kek") {
                isSupersededVersion(v1, v2).shouldBeTrue()
            }

            it("compares versions as numbers, not as text") {
                isSupersededVersion(
                    "notification-service-keychain-kek-aesgcm-v9",
                    "notification-service-keychain-kek-aesgcm-v10",
                ).shouldBeTrue()
            }

            it("compares versions beyond the range of a long") {
                isSupersededVersion("a-b-c-v99999999999999999999", "a-b-c-v100000000000000000000").shouldBeTrue()
            }

            it("keeps a kek of another family in both directions") {
                isSupersededVersion("other-service-dek-kek-aesgcm-v1", v2).shouldBeFalse()
                isSupersededVersion(v1, "other-service-dek-kek-aesgcm-v2").shouldBeFalse()
            }

            it("keeps the current and any newer version") {
                isSupersededVersion(v1, v1).shouldBeFalse()
                isSupersededVersion(v2, v1).shouldBeFalse()
            }

            it("ignores records that name no versioned kek") {
                isSupersededVersion("", v1).shouldBeFalse()
                isSupersededVersion("unversioned", v1).shouldBeFalse()
            }
        }

        describe("keyVersion") {
            it("reads no version with a leading zero, so -v01 cannot pose as -v1") {
                keyVersion("notification-service-keychain-kek-aesgcm-v01") shouldBe null
                keyVersion("notification-service-keychain-kek-aesgcm-v0") shouldBe BigInteger.ZERO
            }
        }

        describe("followsKeyIdScheme") {
            it("accepts [component]-[purpose]-[algorithm]-[version] and nothing shorter") {
                followsKeyIdScheme(v1).shouldBeTrue()
                followsKeyIdScheme("dek-kek-v1").shouldBeFalse()
                followsKeyIdScheme("Notification-Service-DEK-KEK-v1").shouldBeFalse()
            }
        }
    })
