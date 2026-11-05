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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class PushkeyOwnersTest :
    DescribeSpec({
        val pusherId = PusherId(appId = "app.one", pushKey = "key-1")
        val first = UserId.parse("X110411675")
        val second = UserId.parse("X110411676")

        it("lists every owner added and names the others than a given one") {
            val owners = PushkeyOwners.create(pusherId, first).add(second)

            owners.owners shouldContainExactly setOf(first, second)
            owners.othersThan(second) shouldContainExactly setOf(first)
        }

        it("returns the same instance when nothing changes") {
            val owners = PushkeyOwners.create(pusherId, first)

            owners.add(first) shouldBeSameInstanceAs owners
            owners.release(setOf(second)) shouldBeSameInstanceAs owners
        }

        it("is marked for removal once the last owner is released") {
            val owners = PushkeyOwners.create(pusherId, first).add(second)

            owners.release(setOf(first)).markedForRemoval() shouldBe false
            val empty = owners.release(setOf(first, second))
            empty.owners.shouldBeEmpty()
            empty.markedForRemoval() shouldBe true
        }
    })
