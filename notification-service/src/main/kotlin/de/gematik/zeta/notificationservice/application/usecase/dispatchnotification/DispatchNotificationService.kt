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
package de.gematik.zeta.notificationservice.application.usecase.dispatchnotification

import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayError
import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayPort
import de.gematik.zeta.notificationservice.application.port.outbound.PushPayloadEncryptor
import de.gematik.zeta.notificationservice.application.usecase.managepusher.ManagePusherUseCase
import de.gematik.zeta.notificationservice.domain.error.redacted
import de.gematik.zeta.notificationservice.domain.model.DeliveryStatus
import de.gematik.zeta.notificationservice.domain.model.Device
import de.gematik.zeta.notificationservice.domain.model.DispatchResult
import de.gematik.zeta.notificationservice.domain.model.EncryptedPushItem
import de.gematik.zeta.notificationservice.domain.model.Notification
import de.gematik.zeta.notificationservice.domain.model.NotificationId
import de.gematik.zeta.notificationservice.domain.model.PendingDispatch
import de.gematik.zeta.notificationservice.domain.model.PlainPushItem
import de.gematik.zeta.notificationservice.domain.model.PusherId
import de.gematik.zeta.notificationservice.domain.model.PusherKeyMaterial
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

@Startup
@ApplicationScoped
class DispatchNotificationService(
    private val pushGatewayPort: PushGatewayPort,
    private val managePusher: ManagePusherUseCase,
    private val encryptor: PushPayloadEncryptor,
    private val clock: Clock,
    @param:ConfigProperty(name = "notification.encryption.enabled")
    private val encryptionEnabled: Boolean,
    @param:ConfigProperty(name = "push-gateway.retry.give-up-after")
    private val giveUpAfter: Duration,
    @param:ConfigProperty(name = "push-gateway.retry.initial-delay")
    private val initialDelay: Duration,
    @param:ConfigProperty(name = "push-gateway.retry.max-delay")
    private val maxDelay: Duration,
    @param:ConfigProperty(name = "push-gateway.retry.max-attempts")
    private val failedItemAttempts: Int,
    @param:ConfigProperty(name = "push-gateway.batch.max-count")
    private val maxBatchCount: Int,
    @ConfigProperty(name = "push-gateway.batch.max-time")
    maxBatchTime: Duration,
    @ConfigProperty(name = "notification.persistence.retention")
    retention: Duration,
) : DispatchNotificationUseCase {
    private val logger = Logger.getLogger(DispatchNotificationService::class.java)
    private val rejectedPushkeys = ConcurrentHashMap<NotificationId, MutableSet<String>>()

    init {
        require(giveUpAfter < retention) {
            "push-gateway.retry.give-up-after must be shorter than notification.persistence.retention, " +
                "was $giveUpAfter and $retention"
        }
        require(initialDelay.isPositive && maxDelay >= initialDelay) {
            "push-gateway.retry.initial-delay must be positive and at most push-gateway.retry.max-delay, " +
                "was $initialDelay and $maxDelay"
        }
        require(failedItemAttempts >= 1) {
            "push-gateway.retry.max-attempts must be at least 1, was $failedItemAttempts"
        }
        require(maxBatchCount in 1..MAX_BATCH_COUNT_LIMIT) {
            "push-gateway.batch.max-count must lie between 1 and $MAX_BATCH_COUNT_LIMIT, was $maxBatchCount"
        }
        require(!maxBatchTime.isNegative && maxBatchTime <= MAX_BATCH_TIME_LIMIT) {
            "push-gateway.batch.max-time must lie between PT0S and $MAX_BATCH_TIME_LIMIT, was $maxBatchTime"
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun dispatch(pendingDispatch: PendingDispatch) {
        val notification = pendingDispatch.notification
        val missed =
            try {
                coroutineScope {
                    pendingDispatch.targets
                        .groupBy({ it.targetUrl }, { it.device })
                        .map { (url, devices) ->
                            async {
                                try {
                                    if (encryptionEnabled) {
                                        sendEncrypted(url, notification, devices)
                                    } else {
                                        sendPlain(url, notification, devices)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: RuntimeException) {
                                    logger.error(
                                        "Unexpected error dispatching notification ${notification.id} to $url",
                                        e.redacted(),
                                    )
                                    devices.map { it.pushkey }
                                }
                            }
                        }.awaitAll()
                        .flatten()
                }
            } finally {
                removeRejectedPushers(notification, rejectedPushkeys.remove(notification.id).orEmpty())
            }
        val outcome =
            when {
                missed.isEmpty() -> "dispatched"
                missed.containsAll(pendingDispatch.targets.map { it.device.pushkey }) -> "failed"
                else -> "partially_rejected"
            }
        val summary = "Notification ${notification.id} dispatch outcome: $outcome"
        if (missed.isEmpty()) {
            logger.info(summary)
        } else {
            logger.warn(summary)
        }
    }

    private suspend fun sendPlain(
        url: String,
        notification: Notification,
        devices: List<Device>,
    ): Set<String> {
        val item =
            PlainPushItem(
                id = notification.id.toString(),
                prio = notification.prio,
                devices = devices,
                payload = notification.payload,
            )
        return sendWithRetry(notification, url, mapOf(item.id to devices.map { it.pushkey })) { pending ->
            pushGatewayPort.sendPlain(url, listOf(item.copy(devices = devices.filter { it.pushkey in pending })))
        }
    }

    private suspend fun sendEncrypted(
        url: String,
        notification: Notification,
        devices: List<Device>,
    ): Set<String> {
        val month = PusherKeyMaterial.monthOf(clock.now())
        val items = devices.mapIndexedNotNull { index, device -> encryptedItem(notification, device, index, month) }
        val missed =
            coroutineScope {
                items
                    .chunked(maxBatchCount)
                    .map { batch ->
                        val pushkeysByItemId = batch.associate { it.id to listOf(it.device.pushkey) }
                        async {
                            sendWithRetry(notification, url, pushkeysByItemId) { pending ->
                                pushGatewayPort.sendEncrypted(url, batch.filter { it.device.pushkey in pending })
                            }
                        }
                    }.awaitAll()
            }
        val skipped = devices.map { it.pushkey } - items.map { it.device.pushkey }.toSet()
        return missed.flatten().toSet() + skipped
    }

    private fun encryptedItem(
        notification: Notification,
        device: Device,
        index: Int,
        month: YearMonth,
    ): EncryptedPushItem? {
        val pusherId = PusherId(appId = device.appId, pushKey = device.pushkey)
        val material = managePusher.currentKeyMaterial(notification.userId, pusherId, month)
        if (material == null) {
            logger.warn("Notification ${notification.id}: skipping a device without push key material")
            return null
        }
        val encrypted = encryptor.encrypt(notification.payload, material)
        return EncryptedPushItem(
            id = "${notification.id}:$index",
            prio = notification.prio,
            device = device,
            ciphertext = encrypted.cipherText,
            timeMessageEncrypted = encrypted.timeMessageEncrypted,
            keyIdentifier = material.keyIdentifier,
            reference = notification.reference,
        )
    }

    private suspend fun sendWithRetry(
        notification: Notification,
        url: String,
        pushkeysByItemId: Map<String, List<String>>,
        call: suspend (Set<String>) -> DispatchResult,
    ): Set<String> {
        val deadline = notification.acceptedAt + giveUpAfter.toKotlinDuration()
        val targeted = pushkeysByItemId.values.flatten().toSet()
        val delivered = mutableSetOf<String>()
        var pending = targeted
        var attempt = 1
        var failedResponses = 0
        var waitMillis = initialDelay.toMillis()
        do {
            val error =
                try {
                    val result = call(pending)
                    logResult(notification, url, result)
                    val rejected = result.results.flatMapTo(mutableSetOf()) { it.rejected }
                    rejectedPushkeys.computeIfAbsent(notification.id) { ConcurrentHashMap.newKeySet() }.addAll(rejected)
                    val (failed, answered) = result.results.partition { it.status == DeliveryStatus.FAILED }
                    delivered += pending intersect (answered.flatMap { pushkeysByItemId[it.id].orEmpty() } - rejected)
                    pending = pending intersect (failed.flatMap { pushkeysByItemId[it.id].orEmpty() } - rejected)
                    failedResponses += 1
                    null
                } catch (e: PushGatewayError) {
                    e
                }
            val retryable = if (error == null) failedResponses < failedItemAttempts else error.retryable
            val retrying = pending.isNotEmpty() && retryable && clock.now() + waitMillis.milliseconds < deadline
            if (pending.isNotEmpty()) {
                logFailure(notification, url, attempt, error, waitMillis.takeIf { retrying })
            }
            if (retrying) {
                delay(waitMillis)
                waitMillis = (waitMillis * 2).coerceAtMost(maxDelay.toMillis())
                attempt += 1
            }
        } while (retrying)
        return targeted - delivered
    }

    @Suppress("TooGenericExceptionCaught")
    private fun removeRejectedPushers(
        notification: Notification,
        rejected: Set<String>,
    ) {
        if (rejected.isEmpty()) {
            return
        }
        try {
            managePusher.removeMany(notification.userId, rejected)
            logger.info(
                "Notification ${notification.id}: removed ${rejected.size} pushkey(s) rejected by the Push Gateway",
            )
        } catch (e: RuntimeException) {
            logger.error("Notification ${notification.id}: could not deregister rejected pushers", e.redacted())
        }
    }

    private fun logFailure(
        notification: Notification,
        url: String,
        attempt: Int,
        error: PushGatewayError?,
        retryInMillis: Long?,
    ) {
        val cause =
            error
                ?.cause
                ?.message
                ?.let { " ($it)" }
                .orEmpty()
        val reason = if (error == null) "the Push Gateway reported failed items" else "${error.message}$cause"
        val outcome = retryInMillis?.let { "retrying in ${it}ms" } ?: "giving up"
        logger.error(
            "Push Gateway dispatch failed for notification ${notification.id} to $url " +
                "on attempt $attempt, $outcome: $reason",
        )
    }

    private fun logResult(
        notification: Notification,
        url: String,
        result: DispatchResult,
    ) {
        result.results.forEach { item ->
            if (item.status != DeliveryStatus.SUCCESS || item.rejected.isNotEmpty()) {
                logger.warn(
                    "Notification ${notification.id} via $url: status=${item.status}, " +
                        "rejected=${item.rejected.size} pushkey(s), error=${item.error}",
                )
            } else {
                logger.info("Notification ${notification.id} via $url delivered: ${result.summary}")
            }
        }
    }

    private companion object {
        const val MAX_BATCH_COUNT_LIMIT = 100
        val MAX_BATCH_TIME_LIMIT: Duration = Duration.ofSeconds(10)
    }
}
