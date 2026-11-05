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

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.encryption.Keychain
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@Readiness
@ApplicationScoped
class KeychainReadinessCheck(
    private val keychain: Keychain,
    private val storageRecords: StorageRecordDAO,
    @param:ConfigProperty(name = "notification.persistence.sealing.enabled")
    private val sealingEnabled: Boolean,
) : HealthCheck {
    @Volatile
    private var keychainUsable = false

    @Suppress("TooGenericExceptionCaught")
    override fun call(): HealthCheckResponse {
        if (!sealingEnabled) {
            return HealthCheckResponse
                .named(NAME)
                .up()
                .withData("sealing", "disabled")
                .build()
        }
        return try {
            if (!keychainUsable) {
                checkKeychainUsable()
                keychainUsable = true
            }
            HealthCheckResponse.named(NAME).up().build()
        } catch (e: Exception) {
            HealthCheckResponse
                .named(NAME)
                .down()
                .withData("error", e.message ?: e.javaClass.simpleName)
                .build()
        }
    }

    /**
     * Unwraps the keychain, the only HSM call the service makes, and checks that every live record is
     * under an entry it holds. Once both have passed, the instance has its keys and needs neither again,
     * so an HSM outage afterwards does not take it out of service.
     */
    private fun checkKeychainUsable() {
        keychain.key(keychain.writeId)
        val unreadable = storageRecords.countLiveRecordsPerKeychainId().filterKeys { !keychain.holds(it) }
        check(unreadable.isEmpty()) { "Live records are sealed under keychain entries it does not hold: $unreadable" }
    }

    private companion object {
        const val NAME = "keychain"
    }
}
