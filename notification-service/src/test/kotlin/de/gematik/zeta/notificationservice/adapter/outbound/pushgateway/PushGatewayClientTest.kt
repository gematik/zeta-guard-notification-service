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
package de.gematik.zeta.notificationservice.adapter.outbound.pushgateway

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayError
import de.gematik.zeta.notificationservice.domain.model.DeliveryStatus
import de.gematik.zeta.notificationservice.domain.model.Device
import de.gematik.zeta.notificationservice.domain.model.EncryptedPushItem
import de.gematik.zeta.notificationservice.domain.model.PlainPushItem
import de.gematik.zeta.notificationservice.domain.model.Priority
import de.gematik.zeta.notificationservice.integration.quarkusResources.PushGatewayMock
import de.gematik.zeta.notificationservice.integration.quarkusResources.TlsProbeCertificates
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.quarkus.test.junit.QuarkusTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

@QuarkusTest
@WithDatabase
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushGatewayClientTest {
    private val mock = PushGatewayMock.create()

    private fun url() = mock.baseUrl() + "/push/v1/"

    /** A client whose allow-list is exactly [urls]; no custom trust store (plain-HTTP mock target). */
    private fun restPushGatewayClientFor(vararg urls: String): RestPushGatewayClient =
        RestPushGatewayClient(
            urls.toList(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            java.time.Duration.ofSeconds(3),
            java.time.Duration.ofSeconds(10),
        )

    private lateinit var client: RestPushGatewayClient

    // Any string of the contract-mandated length passes the ciphertext invariant; content is irrelevant here.
    private val ciphertext = "A".repeat(EncryptedPushItem.CIPHERTEXT_LENGTH)

    private fun target(pushkey: String) =
        Device(appId = "app_id", pushkey = pushkey, format = "event_id_only", platform = "apns")

    private fun encryptedItem(
        device: Device,
        prio: Priority = Priority.HIGH,
        id: String = "item-0",
    ) = EncryptedPushItem(
        id = id,
        prio = prio,
        device = device,
        ciphertext = ciphertext,
        timeMessageEncrypted = "2026-07",
        keyIdentifier = "123e4567-e89b-12d3-a456-426614174000",
        reference = "evt-1",
    )

    private fun encryptedBatchOf(vararg targets: Device) =
        targets.mapIndexed { index, device -> encryptedItem(device = device, id = "item-$index") }

    private fun stubEncryptedBatch(
        body: String,
        status: Int = 200,
    ) {
        mock.wireMock().stubFor(
            post(urlPathMatching(".*notifyEncrypted/batch"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )
    }

    private fun successBody(vararg ids: String): String {
        val results =
            ids.joinToString(separator = ",") { id ->
                """
                {
                  "id": "$id",
                  "status": "success",
                  "rejected": []
                }
                """.trimIndent()
            }

        return """
            {
              "results": [
                $results
              ],
              "summary": {
                "total": ${ids.size},
                "successful": ${ids.size},
                "failed": 0,
                "partial": 0
              }
            }
            """.trimIndent()
    }

    private val plainPayload = buildJsonObject { put("message", "new-doc") }

    private fun plainBatchOf(vararg targets: Device) =
        listOf(PlainPushItem(id = "item-0", prio = Priority.HIGH, devices = targets.toList(), payload = plainPayload))

    private fun stubPlainBatch(
        body: String,
        status: Int = 200,
    ) {
        mock.wireMock().stubFor(
            post(urlPathMatching(".*notify/batch"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )
    }

    @BeforeAll
    fun startMock() {
        mock.start()
        client = restPushGatewayClientFor(url())
    }

    @AfterAll
    fun stopMock() {
        mock.stop()
    }

    @AfterEach
    fun reset() {
        mock.wireMock().resetAll()
    }

    @Test
    fun `boots with a password-protected mTLS client key`(
        @TempDir dir: Path,
    ) {
        val keyPair = TlsProbeCertificates.generateRsa(2048)
        val certPath = dir.resolve("client.crt")
        val keyPath = dir.resolve("client.key")
        Files.writeString(
            certPath,
            TlsProbeCertificates.toPem(TlsProbeCertificates.selfSigned(keyPair, "SHA256withRSA")),
        )
        Files.newBufferedWriter(keyPath).use { out ->
            JcaPEMWriter(out).use {
                it.writeObject(
                    keyPair.private,
                    JcePEMEncryptorBuilder(
                        "AES-256-CBC",
                    ).setProvider(BouncyCastleProvider()).build("secret".toCharArray()),
                )
            }
        }

        RestPushGatewayClient(
            listOf(url()),
            Optional.empty(),
            Optional.of(certPath.toString()),
            Optional.of(keyPath.toString()),
            Optional.of("secret"),
            java.time.Duration.ofSeconds(3),
            java.time.Duration.ofSeconds(10),
        )
    }

    @Test
    fun `posts a contract-shaped encrypted batch to the target url`() =
        runTest {
            stubEncryptedBatch(successBody("item-0"))

            client.sendEncrypted(url(), encryptedBatchOf(target("pushkey_1")))

            val requests = mock.wireMock().findAll(postRequestedFor(urlPathMatching(".*notifyEncrypted/batch")))
            requests shouldHaveSize 1
            val body = requests[0].bodyAsString
            body shouldContain "\"notifications\""
            body shouldContain "\"ciphertext\""
            body shouldContain "\"pushkey_1\""
        }

    @Test
    fun `encrypted call succeeds and parses a success outcome`() =
        runTest {
            stubEncryptedBatch(successBody("item-0"))

            val result = client.sendEncrypted(url(), encryptedBatchOf(target("pk")))

            result.summary.total shouldBe 1
            result.results[0].status shouldBe DeliveryStatus.SUCCESS
        }

    @Test
    fun `parses partial encrypted results with a rejected pushkey list`() =
        runTest {
            stubEncryptedBatch(
                """{"results":[{"id":"x","status":"partial","rejected":["expired_pk"]}],""" +
                    """"summary":{"total":1,"successful":0,"failed":0,"partial":1}}""",
            )

            val result = client.sendEncrypted(url(), encryptedBatchOf(target("pk")))

            result.results[0].status shouldBe DeliveryStatus.PARTIAL
            result.results[0].rejected shouldBe listOf("expired_pk")
        }

    @Test
    fun `maps a normal priority encrypted notification to the low priority field in the request`() =
        runTest {
            stubEncryptedBatch(successBody("item-0"))

            client.sendEncrypted(url(), listOf(encryptedItem(device = target("pk"), prio = Priority.NORMAL)))

            val requests =
                mock.wireMock().findAll(
                    postRequestedFor(urlPathMatching(".*notifyEncrypted/batch")),
                )

            requests shouldHaveSize 1
            requests.single().bodyAsString shouldContain "\"prio\":\"low\""
        }

    @Test
    fun `parses a failed encrypted result with an error message`() =
        runTest {
            stubEncryptedBatch(
                """{"results":[{"id":"x","status":"failed","rejected":[],"error":"bad format"}],""" +
                    """"summary":{"total":1,"successful":0,"failed":1,"partial":0}}""",
            )

            val result = client.sendEncrypted(url(), encryptedBatchOf(target("pk")))

            result.results[0].status shouldBe DeliveryStatus.FAILED
            result.results[0].error shouldBe "bad format"
        }

    @Test
    fun `tolerates unknown fields in the encrypted response (ignore-unknown-keys)`() =
        runTest {
            stubEncryptedBatch(
                """{"results":[{"id":"x","status":"success","rejected":[],"extra_field":"ignored"}],""" +
                    """"summary":{"total":1,"successful":1,"failed":0,"partial":0,"unknown_field":42}}""",
            )

            val result = client.sendEncrypted(url(), encryptedBatchOf(target("pk")))

            result.results[0].status shouldBe DeliveryStatus.SUCCESS
            result.summary.total shouldBe 1
        }

    @Test
    fun `rejects a target url that was not configured`() =
        runTest {
            shouldThrow<PushGatewayError.UntrustedTarget> {
                client.sendEncrypted(
                    "http://not.configured.example/push/v1/",
                    encryptedBatchOf(target("pk")),
                )
            }
        }

    @Test
    fun `encrypted http 500 maps to a ServerError`() =
        runTest {
            stubEncryptedBatch("{}", status = 500)

            shouldThrow<PushGatewayError.ServerError> { client.sendEncrypted(url(), encryptedBatchOf(target("pk"))) }
        }

    @Test
    fun `http 429 maps to a retryable Throttled error`() =
        runTest {
            mock.wireMock().stubFor(
                post(urlPathMatching(".*notifyEncrypted/batch")).willReturn(aResponse().withStatus(429)),
            )

            val error =
                shouldThrow<PushGatewayError.Throttled> { client.sendEncrypted(url(), encryptedBatchOf(target("pk"))) }

            error.retryable shouldBe true
        }

    @Test
    fun `http 400 maps to a non-retryable RequestRejected`() =
        runTest {
            stubEncryptedBatch("{}", status = 400)

            val error =
                shouldThrow<PushGatewayError.RequestRejected> {
                    client.sendEncrypted(url(), encryptedBatchOf(target("pk")))
                }

            error.status shouldBe 400
            error.retryable shouldBe false
        }

    @Test
    fun `unparsable body maps to an UnparsableResponse`() =
        runTest {
            stubEncryptedBatch("this-is-not-json", status = 200)

            shouldThrow<PushGatewayError.UnparsableResponse> {
                client.sendEncrypted(
                    url(),
                    encryptedBatchOf(target("pk")),
                )
            }
        }

    @Test
    fun `connection refused maps to a ConnectionFailed`() =
        runTest {
            // Ask the OS for a free port, read its number, then close the socket so no server is listening there.
            val unusedPort = ServerSocket(0).use { it.localPort }

            val unreachableUrl = "http://127.0.0.1:$unusedPort/push/v1/"
            val clientWithUnreachableUrl = restPushGatewayClientFor(unreachableUrl)

            shouldThrow<PushGatewayError.ConnectionFailed> {
                clientWithUnreachableUrl.sendEncrypted(unreachableUrl, encryptedBatchOf(target("pk")))
            }
        }

    @Test
    fun `an upper-case scheme in the allow-list still reaches the target`() =
        runTest {
            val upperCaseUrl = url().replaceFirst("http://", "HTTP://")
            mock.stubDefaultSuccessPlainBatch()

            restPushGatewayClientFor(upperCaseUrl).sendPlain(upperCaseUrl, plainBatchOf(target("pk")))

            mock.wireMock().findAll(postRequestedFor(urlPathMatching(".*notify/batch"))) shouldHaveSize 1
        }

    @Test
    fun `an http target goes through the http proxy system properties`() =
        runTest {
            // A proxy that refuses connections: the send fails only if the client actually picked it.
            val unusedPort = ServerSocket(0).use { it.localPort }
            val previous =
                listOf(
                    "http.proxyHost",
                    "http.proxyPort",
                    "http.nonProxyHosts",
                ).associateWith(System::getProperty)
            try {
                System.setProperty("http.proxyHost", "127.0.0.1")
                System.setProperty("http.proxyPort", unusedPort.toString())
                System.setProperty("http.nonProxyHosts", "")
                val proxiedClient = restPushGatewayClientFor(url())

                shouldThrow<PushGatewayError.ConnectionFailed> {
                    proxiedClient.sendPlain(url(), plainBatchOf(target("pk")))
                }
            } finally {
                previous.forEach { (key, value) ->
                    if (value == null) System.clearProperty(key) else System.setProperty(key, value)
                }
            }
        }

    @Test
    fun `posts a plain notification to the notify batch url`() =
        runTest {
            stubPlainBatch(successBody("item-0"))

            client.sendPlain(url(), plainBatchOf(target("pushkey_1")))

            val requests = mock.wireMock().findAll(postRequestedFor(urlPathMatching(".*notify/batch")))
            requests shouldHaveSize 1
            val body = requests[0].bodyAsString
            body shouldContain "\"notifications\""
            body shouldContain "\"content\":{\"message\":\"new-doc\"}"
            body shouldContain "\"devices\""
            body shouldContain "\"pushkey_1\""
        }

    @Test
    fun `plain call succeeds and parses a success outcome`() =
        runTest {
            stubPlainBatch(successBody("item-0"))

            val result = client.sendPlain(url(), plainBatchOf(target("pk")))

            result.summary.total shouldBe 1
            result.results[0].status shouldBe DeliveryStatus.SUCCESS
        }

    @Test
    fun `stubDefaultSuccessPlainBatch echoes the submitted notification ids back in the response`() =
        runTest {
            mock.stubDefaultSuccessPlainBatch()
            val result =
                client.sendPlain(
                    url(),
                    listOf(
                        PlainPushItem(
                            id = "req-1",
                            prio = Priority.HIGH,
                            devices = listOf(target("pk-1")),
                            payload = plainPayload,
                        ),
                        PlainPushItem(
                            id = "req-2",
                            prio = Priority.HIGH,
                            devices = listOf(target("pk-2")),
                            payload = plainPayload,
                        ),
                    ),
                )

            result.results.map { it.id } shouldBe listOf("req-1", "req-2")
        }

    @Test
    fun `maps a normal priority plain notification to the low priority field in the request`() =
        runTest {
            stubPlainBatch(successBody("item-0"))

            client.sendPlain(
                url(),
                listOf(
                    PlainPushItem(
                        id = "item-0",
                        prio = Priority.NORMAL,
                        devices = listOf(target("pk")),
                        payload = plainPayload,
                    ),
                ),
            )

            val requests = mock.wireMock().findAll(postRequestedFor(urlPathMatching(".*notify/batch")))
            requests shouldHaveSize 1
            requests.single().bodyAsString shouldContain "\"prio\":\"low\""
        }

    @Test
    fun `a plain notification fans out to multiple devices in one request`() =
        runTest {
            stubPlainBatch(successBody("item-0"))

            client.sendPlain(url(), plainBatchOf(target("pushkey_1"), target("pushkey_2")))

            val requests = mock.wireMock().findAll(postRequestedFor(urlPathMatching(".*notify/batch")))
            requests shouldHaveSize 1
            val body = requests[0].bodyAsString
            body shouldContain "\"pushkey_1\""
            body shouldContain "\"pushkey_2\""
        }

    @Test
    fun `a cancelled call aborts the in-flight request instead of waiting it out`() =
        runBlocking<Unit> {
            mock.wireMock().stubFor(
                post(urlPathMatching(".*notify/batch"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withFixedDelay(GATEWAY_DELAY_MILLIS)
                            .withHeader("Content-Type", "application/json")
                            .withBody(successBody("item-0")),
                    ),
            )
            val caught = AtomicReference<Throwable>()
            val call =
                launch(Dispatchers.IO) {
                    try {
                        client.sendPlain(url(), plainBatchOf(target("pk")))
                    } catch (e: Throwable) {
                        caught.set(e)
                        throw e
                    }
                }
            delay(CANCEL_AFTER_MILLIS)

            val abortMillis = measureTimeMillis { call.cancelAndJoin() }

            caught.get().shouldBeInstanceOf<CancellationException>()
            abortMillis shouldBeLessThan GATEWAY_DELAY_MILLIS.toLong()
        }

    @Test
    fun `plain http 500 maps to a ServerError`() =
        runTest {
            stubPlainBatch("{}", status = 500)

            shouldThrow<PushGatewayError.ServerError> { client.sendPlain(url(), plainBatchOf(target("pk"))) }
        }

    private companion object {
        const val GATEWAY_DELAY_MILLIS = 3000
        const val CANCEL_AFTER_MILLIS = 500L
    }
}
