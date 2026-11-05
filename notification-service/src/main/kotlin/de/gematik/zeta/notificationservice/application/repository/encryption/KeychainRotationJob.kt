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
import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecordDAO
import de.gematik.zeta.notificationservice.application.repository.logicalIdOf
import de.gematik.zeta.notificationservice.application.repository.pseudonymization.HmacPseudonymizer
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.GeneralSecurityException

/**
 * Moves the records of older keychain entries to the entry that takes the writes: a new storage id from
 * its pseudonymization key, sealed under its DEK. Records with a retention period are left to expire,
 * unless that keychain entry indicates an emergency rotation.
 */
@ApplicationScoped
class KeychainRotationJob(
    private val storageRecords: StorageRecordDAO,
    private val keychain: Keychain,
    private val payloadSealer: PayloadSealer,
    @param:ConfigProperty(name = "notification.persistence.sealing.enabled")
    private val sealingEnabled: Boolean,
) {
    private val logger = Logger.getLogger(KeychainRotationJob::class.java)
    private val pseudonymizer = HmacPseudonymizer(keychain)

    @Scheduled(
        every = "{notification.persistence.sealing.rotation-interval:1h}",
        delayed = "1m",
        concurrentExecution = ConcurrentExecution.SKIP,
    )
    fun rotate() {
        if (!sealingEnabled) {
            return
        }
        val currentId = keychain.writeId
        val emergency = keychain.isEmergency(currentId)
        val outcomes =
            if (storageRecords.countLiveRecordsPerKeychainId().keys.any { isSupersededVersion(it, currentId) }) {
                walk(candidates(currentId, emergency), currentId)
            } else {
                emptyMap()
            }
        logger.infof(
            "Key rotation: %d records moved to keychain entry %s%s, %d failed; live records per entry: %s",
            outcomes[Outcome.MOVED] ?: 0,
            currentId,
            if (emergency) " (emergency, records with a retention period included)" else "",
            outcomes[Outcome.FAILED] ?: 0,
            storageRecords.countLiveRecordsPerKeychainId(),
        )
    }

    private fun walk(
        candidates: Sequence<String>,
        currentId: String,
    ): Map<Outcome, Int> {
        val outcomes = mutableMapOf<Outcome, Int>()
        var failedInARow = 0
        for (id in candidates) {
            val outcome = move(id, currentId)
            outcomes.merge(outcome, 1, Int::plus)
            failedInARow =
                when (outcome) {
                    Outcome.FAILED -> failedInARow + 1
                    Outcome.SKIPPED -> failedInARow
                    Outcome.MOVED -> 0
                }
            if (failedInARow == MAX_FAILURES_IN_A_ROW) {
                logger.warnf("Key rotation stopped after %d failures in a row; the next run retries", failedInARow)
                break
            }
        }
        return outcomes
    }

    // Only records of older entries: one under a newer entry was written by an instance whose clock is
    // ahead, and moving it back would only have to be undone.
    private fun candidates(
        currentId: String,
        withRetention: Boolean,
    ): Sequence<String> =
        ID_PREFIXES.shuffled().asSequence().flatMap { prefix ->
            storageRecords
                .findUnderOtherKeychainId(currentId, prefix, prefix + ABOVE_ANY_HEX_DIGIT, withRetention)
                .filterValues { isSupersededVersion(it, currentId) }
                .keys
        }

    private fun move(
        id: String,
        currentId: String,
    ): Outcome {
        val record = storageRecords.findById(id) ?: return Outcome.SKIPPED
        return attempt(id) {
            val plaintext = payloadSealer.open(record)
            val logicalId = logicalIdOf(plaintext)
            when {
                logicalId == null -> {
                    logRecordFailure(id, "the record names no logical id")
                }

                id != pseudonymizer.pseudonymize(logicalId, record.keychainId) -> {
                    logRecordFailure(id, "its id is not the one its keychain entry derives")
                }

                else -> {
                    val target = pseudonymizer.pseudonymize(logicalId, currentId)
                    val moved = payloadSealer.seal(target, currentId, plaintext)
                    storageRecords.move(id, target, moved, checkNotNull(record.transVerifier))
                    Outcome.MOVED
                }
            }
        }
    }

    private fun attempt(
        id: String,
        work: () -> Outcome,
    ): Outcome =
        try {
            work()
        } catch (_: StaleRecordException) {
            Outcome.SKIPPED
        } catch (e: HsmProxyException) {
            logRecordFailure(id, e.toString())
        } catch (e: GeneralSecurityException) {
            logRecordFailure(id, e.toString())
        } catch (e: IllegalArgumentException) {
            logRecordFailure(id, e.toString())
        } catch (e: IllegalStateException) {
            logRecordFailure(id, e.toString())
        }

    private fun logRecordFailure(
        id: String,
        reason: String,
    ): Outcome {
        logger.warnf("Moving record %s to the current keychain entry failed: %s", id, reason)
        return Outcome.FAILED
    }

    private enum class Outcome { MOVED, SKIPPED, FAILED }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 10
        const val ABOVE_ANY_HEX_DIGIT = "g"
        val ID_PREFIXES = (0 until 256).map { it.toString(16).padStart(2, '0') }
    }
}
