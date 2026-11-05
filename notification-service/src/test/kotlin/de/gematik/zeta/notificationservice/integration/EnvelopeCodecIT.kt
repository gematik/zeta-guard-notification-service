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
package de.gematik.zeta.notificationservice.integration

import de.gematik.zeta.notificationservice.application.repository.IdempotencyRepository
import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import de.gematik.zeta.notificationservice.domain.model.ChannelId
import de.gematik.zeta.notificationservice.domain.model.IdempotencyKey
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.NotificationStatus
import de.gematik.zeta.notificationservice.domain.model.SubmissionReceipt
import de.gematik.zeta.notificationservice.domain.model.UserId
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.testsupport.FakeHsmProxy
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@QuarkusTest
@WithDatabase
class EnvelopeCodecIT {
    @Inject
    lateinit var idempotencyRepository: IdempotencyRepository

    @Inject
    lateinit var fakeHsmProxy: FakeHsmProxy

    @Inject
    lateinit var keychain: Keychain

    @Test
    fun writesAndReadsNeedNoHsmOnceTheKeychainIsUnwrappedAndUnwrapItAgainAfterAFlush() {
        val (key, receipt) = receipt("codec-it-1", "2026-08-31T10:00:00Z")
        idempotencyRepository.remember(key, receipt)
        idempotencyRepository.find(key) shouldBe receipt
        val unwrapsAfterWarmUp = fakeHsmProxy.unwraps.get()

        val (otherKey, otherReceipt) = receipt("codec-it-2", "2026-08-31T10:30:00Z")
        idempotencyRepository.remember(otherKey, otherReceipt)
        repeat(3) { idempotencyRepository.find(otherKey) shouldBe otherReceipt }
        fakeHsmProxy.unwraps.get() shouldBe unwrapsAfterWarmUp

        keychain.close()
        idempotencyRepository.find(key) shouldBe receipt
        fakeHsmProxy.unwraps.get() shouldBe unwrapsAfterWarmUp + 2

        idempotencyRepository.find(otherKey) shouldBe otherReceipt
        fakeHsmProxy.unwraps.get() shouldBe unwrapsAfterWarmUp + 2
    }

    @Test
    fun theShutdownPathZeroisesEveryUnwrappedKeyAndForcesAFreshDecryptAfterwards() {
        val (key, receipt) = receipt("codec-it-3", "2026-08-31T11:00:00Z")
        idempotencyRepository.remember(key, receipt)
        idempotencyRepository.find(key) shouldBe receipt

        val pseudonymKey = keychain.pseudonymKey(TestKeychain.V2)
        val dek = keychain.key(TestKeychain.V2)
        val unwrapsBeforeShutdown = fakeHsmProxy.unwraps.get()

        keychain.close()

        pseudonymKey.isDestroyed shouldBe true
        dek.isDestroyed shouldBe true
        shouldThrow<IllegalStateException> { pseudonymKey.encoded }
        shouldThrow<IllegalStateException> { dek.encoded }

        idempotencyRepository.find(key) shouldBe receipt
        fakeHsmProxy.unwraps.get() shouldBe unwrapsBeforeShutdown + TestKeychain.DEFAULT_ENTRIES.size
    }

    private fun receipt(
        idempotencyKey: String,
        acceptedAt: String,
    ): Pair<IdempotencyKey, SubmissionReceipt> {
        val key =
            IdempotencyKey(
                userId = UserId.parse("X110477001"),
                channel = ChannelId.parse("epa.documents.new"),
                key = idempotencyKey,
            )
        val receipt =
            SubmissionReceipt(
                notificationId = NotificationId.random(),
                status = NotificationStatus.ACCEPTED,
                channel = key.channel,
                acceptedAt = Instant.parse(acceptedAt),
            )
        return key to receipt
    }
}
