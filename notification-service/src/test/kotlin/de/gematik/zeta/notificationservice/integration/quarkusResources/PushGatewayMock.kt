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
package de.gematik.zeta.notificationservice.integration.quarkusResources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestWrapper
import com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.ResponseDefinition
import com.github.tomakehurst.wiremock.stubbing.ServeEvent
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.openapitools.apis.PushGatewayApiStubs
import org.openapitools.models.BatchNotificationResponse
import org.openapitools.models.BatchNotificationResponseResultsInner
import org.openapitools.models.BatchNotificationResponseSummary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * In-JVM mock of the Push Gateway for CI. Plain HTTP: the application does speak TLS to the real Push
 * Gateway (`RestPushGatewayClient`), but that is out of scope here — [TlsProbeServer] covers it.
 *
 * Comes with successful default mapping for plain batch notifications.
 *
 * Every stub is registered ad-hoc from the tests; there is
 * no default mapping, since encrypted and plain notifications need different responses.
 */
class PushGatewayMock(
    private val server: WireMockServer,
    private val responseGate: ResponseGate,
) {
    private val objectMapper = jacksonObjectMapper()

    fun start(): PushGatewayMock = apply { server.start() }

    fun stop(): Unit = server.stop()

    fun baseUrl(): String = server.baseUrl()

    fun wireMock(): WireMockServer = server

    fun holdGatedResponses(): Unit = responseGate.close()

    fun releaseGatedResponses(): Unit = responseGate.open()

    fun gateExpired(): Boolean = responseGate.expired

    fun findMatchingBatchRequests(pushkey: String): List<LoggedRequest> =
        server.findAll(
            postRequestedFor(urlPathMatching(".*/notify/batch")).withRequestBody(containing(pushkey)),
        )

    fun awaitRequest(
        pushkey: String,
        expectedCount: Int = 1,
        timeoutMillis: Long = 5000,
    ): List<LoggedRequest> = awaitRequests(expectedCount, timeoutMillis) { findMatchingBatchRequests(pushkey) }

    fun findMatchingEncryptedBatchRequests(pushkey: String): List<LoggedRequest> =
        server.findAll(
            postRequestedFor(urlPathMatching(".*/notifyEncrypted/batch")).withRequestBody(containing(pushkey)),
        )

    fun awaitEncryptedRequest(
        pushkey: String,
        expectedCount: Int = 1,
        timeoutMillis: Long = 5000,
    ): List<LoggedRequest> = awaitRequests(expectedCount, timeoutMillis) { findMatchingEncryptedBatchRequests(pushkey) }

    private fun awaitRequests(
        expectedCount: Int,
        timeoutMillis: Long,
        find: () -> List<LoggedRequest>,
    ): List<LoggedRequest> =
        runBlocking {
            val deadline = System.currentTimeMillis() + timeoutMillis

            var requests = find()
            while (requests.size < expectedCount && System.currentTimeMillis() < deadline) {
                delay(50.milliseconds)
                requests = find()
            }
            requests
        }

    fun stubDefaultSuccessPlainBatch(
        priority: Int = FALLBACK_STUB_PRIORITY,
        gated: Boolean = false,
    ) {
        val stub =
            PushGatewayApiStubs(objectMapper)
                .pushV1NotifyBatchPlain {
                    atPriority(priority)
                }.respondWith200(
                    // Placeholder body — BatchIdEchoingTransformer rewrites it per-request with the
                    // ids actually submitted before it's returned.
                    BatchNotificationResponse(
                        listOf(),
                        BatchNotificationResponseSummary(0, 0, 0, 0),
                    ),
                ) {
                    withTransformers(
                        *listOfNotNull(
                            BatchIdEchoingTransformer.NAME,
                            ResponseGate.NAME.takeIf { gated },
                        ).toTypedArray(),
                    )
                }

        server.stubFor(stub)
    }

    fun stubPlainBatchRejectedPushkey(
        pushkey: String,
        priority: Int = DEFAULT_STUB_PRIORITY,
    ) {
        val stub =
            PushGatewayApiStubs(objectMapper)
                .pushV1NotifyBatchPlain {
                    atPriority(priority)
                }.respondWith200(
                    BatchNotificationResponse(
                        listOf(
                            BatchNotificationResponseResultsInner(
                                "any",
                                "partial",
                                listOf(pushkey),
                            ),
                        ),
                        BatchNotificationResponseSummary(1, 0, 0, 1),
                    ),
                )

        server.stubFor(stub)
    }

    fun stubDefaultSuccessEncryptedBatch(
        priority: Int = FALLBACK_STUB_PRIORITY,
        gated: Boolean = false,
    ) {
        val stub =
            PushGatewayApiStubs(objectMapper)
                .pushV1NotifyBatchEncrypted {
                    atPriority(priority)
                }.respondWith200(
                    BatchNotificationResponse(
                        listOf(),
                        BatchNotificationResponseSummary(0, 0, 0, 0),
                    ),
                ) {
                    withTransformers(
                        *listOfNotNull(
                            BatchIdEchoingTransformer.NAME,
                            ResponseGate.NAME.takeIf { gated },
                        ).toTypedArray(),
                    )
                }

        server.stubFor(stub)
    }

    fun stubEncryptedBatchRejectedPushkey(
        pushkey: String,
        priority: Int = DEFAULT_STUB_PRIORITY,
    ) {
        val stub =
            PushGatewayApiStubs(objectMapper)
                .pushV1NotifyBatchEncrypted {
                    atPriority(priority)
                }.respondWith200(
                    BatchNotificationResponse(
                        listOf(
                            BatchNotificationResponseResultsInner(
                                "any",
                                "partial",
                                listOf(pushkey),
                            ),
                        ),
                        BatchNotificationResponseSummary(1, 0, 0, 1),
                    ),
                )

        server.stubFor(stub)
    }

    companion object {
        const val DEFAULT_STUB_PRIORITY = 5
        const val FALLBACK_STUB_PRIORITY = 10
        const val DEFAULT_PORT = 7931
        const val BASE_PATH = "/push/v1"

        fun create(): PushGatewayMock {
            val responseGate = ResponseGate()

            // A pusher's data.url points at BASE_PATH (see TestClient.DEFAULT_URL / docker-compose's
            // push-gateway.allowed-base-urls), but the generated stub builders (PushGatewayApiStubs) match against
            // paths relative to the Push Gateway's own OpenAPI spec, e.g. "/notify/batch" with no base path. Strip
            // it before stub matching so both styles of stub agree on what a request path looks like.
            val wiremock =
                WireMockServer(
                    wireMockConfig().port(DEFAULT_PORT).extensions(
                        responseGate,
                        BasePathStrippingFilter(BASE_PATH),
                        BatchIdEchoingTransformer(jacksonObjectMapper()),
                    ),
                )

            return PushGatewayMock(wiremock, responseGate)
        }
    }
}

/**
 * Strips [basePath] from every stub-bound request before it reaches WireMock's matcher, so stubs can be written
 * relative to the Push Gateway API's own paths (`/notify/batch`) regardless of the base path a pusher's `data.url`
 * targets it under. Admin requests (e.g. `__admin/requests`) are untouched — `StubRequestFilterV2` only applies to
 * stub matching.
 */
class BasePathStrippingFilter(
    private val basePath: String,
) : StubRequestFilterV2 {
    override fun getName(): String = "strip-$basePath-prefix"

    override fun filter(
        request: Request,
        serveEvent: ServeEvent,
    ): RequestFilterAction =
        RequestFilterAction.continueWith(
            RequestWrapper
                .create()
                .transformAbsoluteUrl { absoluteUrl -> absoluteUrl.replaceFirst(basePath, "") }
                .wrap(request),
        )
}

class ResponseGate : ResponseDefinitionTransformerV2 {
    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    var expired: Boolean = false
        private set

    fun close() {
        expired = false
        latch = CountDownLatch(1)
    }

    fun open() {
        latch?.countDown()
    }

    override fun getName(): String = NAME

    override fun applyGlobally(): Boolean = false

    override fun transform(serveEvent: ServeEvent): ResponseDefinition {
        val gate = latch
        if (gate != null && !gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            expired = true
        }
        return serveEvent.responseDefinition
    }

    companion object {
        const val NAME = "response-gate"
        const val GATE_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Rewrites a plain-batch response to echo back the `id` of each notification actually submitted in the request,
 * in submission order — mirroring the real Push Gateway's contract (`results` entries correspond 1:1 to
 * `notifications` entries) instead of a stub response fixed at registration time.
 */
class BatchIdEchoingTransformer(
    private val objectMapper: ObjectMapper,
) : ResponseDefinitionTransformerV2 {
    override fun getName(): String = NAME

    override fun applyGlobally(): Boolean = false

    override fun transform(serveEvent: ServeEvent): ResponseDefinition {
        val ids = objectMapper.readTree(serveEvent.request.bodyAsString)["notifications"].map { it["id"].asText() }
        val results = ids.map { BatchNotificationResponseResultsInner(it, "success", emptyList()) }
        val body =
            BatchNotificationResponse(results, BatchNotificationResponseSummary(results.size, results.size, 0, 0))

        return ResponseDefinitionBuilder
            .like(serveEvent.responseDefinition)
            .but()
            .withBody(objectMapper.writeValueAsString(body))
            .build()
    }

    companion object {
        const val NAME = "batch-id-echo"
    }
}
