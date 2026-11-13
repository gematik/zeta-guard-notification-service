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

import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class PushkeyHoldersTest :
    DescribeSpec({
        val first = UserId.parse("X110411675")
        val second = UserId.parse("X110411676")

        it("lists every holder added") {
            PushkeyHolders.create("key-1", first).add(second).holders shouldContainExactly setOf(first, second)
        }

        it("returns the same instance when nothing changes") {
            val holders = PushkeyHolders.create("key-1", first)

            holders.add(first) shouldBeSameInstanceAs holders
            holders.release(second) shouldBeSameInstanceAs holders
        }

        it("is marked for removal once the last holder is released") {
            val holders = PushkeyHolders.create("key-1", first).add(second)

            holders.release(first).markedForRemoval() shouldBe false
            holders.release(first).release(second).markedForRemoval() shouldBe true
        }

        describe("errorFor (A_29978)") {
            it("is a 403 when another user holds the pushkey") {
                PushkeyHolders
                    .errorFor(PushkeyHolders.create("key-1", second), first)
                    .shouldBeInstanceOf<PusherOfOtherUserException>()
            }

            it("is a 404 when nobody holds the pushkey") {
                PushkeyHolders.errorFor(null, first).shouldBeInstanceOf<PusherNotFoundException>()
            }

            it("is a 404 when only the asking user is listed") {
                PushkeyHolders
                    .errorFor(PushkeyHolders.create("key-1", first), first)
                    .shouldBeInstanceOf<PusherNotFoundException>()
            }
        }
    })
