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

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.KeychainRotationJob
import de.gematik.zeta.notificationservice.application.repository.encryption.PayloadSealerWithDek
import de.gematik.zeta.notificationservice.application.repository.logicalIdOf
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.HmacPseudonymizer
import de.gematik.zeta.notificationservice.integration.quarkusResources.InjectStorageRecordProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.StorageRecordProbe
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithPushGatewayMock
import de.gematik.zeta.notificationservice.testsupport.TestClient.pushkeysOf
import de.gematik.zeta.notificationservice.testsupport.TestClient.registerPusher
import de.gematik.zeta.notificationservice.testsupport.TestKeychain
import io.kotest.matchers.collections.shouldNotBeIn
import io.kotest.matchers.shouldBe
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@QuarkusTest
@WithPushGatewayMock
@WithDatabase
class KeychainRotationIT {
    @Inject
    lateinit var job: KeychainRotationJob

    @Inject
    lateinit var storageRecordDAO: StorageRecordDAO

    @InjectStorageRecordProbe
    lateinit var storageRecords: StorageRecordProbe

    @BeforeEach
    @AfterEach
    fun startFromAnEmptyStore() {
        storageRecords.deleteAll()
    }

    // A_29963
    @Test
    @DisplayName(
        "A_29963: once a newer entry of the keychain is active, every resting record under an older one " +
            "moves to it and stays readable before and after",
    )
    fun everyRestingRecordMovesToTheCurrentEntry() {
        val users = listOf("X299630001", "X299630002", "X299630003")
        users.forEach { registerPusher(it, pushkeyOf(it)) }
        users.forEach(::moveUnderV1)
        users.map(::keychainIdOf) shouldBe List(users.size) { TestKeychain.V1 }
        val underV1 = users.map(::storageIdOf)
        users.forEach { pushkeysOf(it) shouldBe listOf(pushkeyOf(it)) }

        job.rotate()

        users.map(::keychainIdOf) shouldBe List(users.size) { TestKeychain.V2 }
        users.map(::storageIdOf).forEach { it shouldNotBeIn underV1 }
        storageRecordDAO.countLiveRecordsPerKeychainId().keys shouldBe setOf(TestKeychain.V2)
        users.forEach { pushkeysOf(it) shouldBe listOf(pushkeyOf(it)) }
    }

    // Puts the record where an instance would have written it before V2 took over the writes.
    private fun moveUnderV1(user: String) {
        val id = storageIdOf(user)
        val record = checkNotNull(storageRecordDAO.findById(id))
        val plaintext = sealer.open(record)
        val v1Id = pseudonymizer.pseudonymize(checkNotNull(logicalIdOf(plaintext)), TestKeychain.V1)
        val underV1 = sealer.seal(v1Id, TestKeychain.V1, plaintext)
        storageRecordDAO.move(id, v1Id, underV1, checkNotNull(record.transVerifier))
    }

    private fun pushkeyOf(user: String): String = "kek-rotation-$user"

    private fun storageIdOf(user: String): String = checkNotNull(storageRecords.rawPusherRecord(user)).storageId

    private fun keychainIdOf(user: String): String = checkNotNull(storageRecords.rawPusherRecord(user)).keychainId

    private companion object {
        val keychain = TestKeychain.keychain()
        val sealer = PayloadSealerWithDek(keychain)
        val pseudonymizer = HmacPseudonymizer(keychain)
    }
}
