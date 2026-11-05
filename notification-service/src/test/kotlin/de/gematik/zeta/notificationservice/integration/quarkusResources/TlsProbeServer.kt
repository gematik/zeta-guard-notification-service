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

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/** What the probe observed on one inbound connection. */
data class TlsProbeConnection(
    /** The offer the service made, or null if the connection died before a full ClientHello arrived. */
    val clientHello: ClientHello?,
    val handshakeSucceeded: Boolean,
    /** The decrypted HTTP request, or null when the handshake never completed. */
    val httpRequest: String?,
)

/**
 * A TLS server that stands in for the Push Gateway and records how the service negotiates with it.
 *
 * The Push Gateway leg is the only TLS the application itself speaks (`RestPushGatewayClient` builds its own
 * REST client per allowed base URL); the RS/FdV legs are terminated upstream. So this is where the
 * gemSpec_Krypt TLS requirements can be observed end-to-end.
 *
 * [PushGatewayMock] cannot do this job: WireMock exposes the negotiated request, not the handshake, and
 * gemSpec_Krypt constrains what a product *offers* (A_17322). Hence a raw [ServerSocket]. Every connection
 * is handled in two steps:
 *  1. the first TLS record — the ClientHello — is read verbatim and parsed by [ClientHello.parse];
 *  2. those same bytes are spliced back in front of the stream via the `consumed` overload of
 *     `SSLSocketFactory.createSocket`, so the handshake proceeds as if nothing had read them.
 *
 * [serve] narrows what the server side accepts, which is how a test forces a specific negotiation outcome.
 * Responses are a canned `notify/batch` success so a completed handshake also completes the dispatch.
 */
class TlsProbeServer private constructor(
    private val material: TlsProbeCertificates.Material,
    private val serverSocket: ServerSocket,
    private val caPem: Path,
) {
    /** Which server identity to present. See [TlsProbeCertificates]. */
    enum class ServerCert { STANDARD, WEAK_RSA }

    private val connections = CopyOnWriteArrayList<TlsProbeConnection>()
    private val workers: ExecutorService =
        Executors.newCachedThreadPool { runnable -> Thread(runnable, "tls-probe").apply { isDaemon = true } }

    @Volatile private var protocols: List<String>? = null

    @Volatile private var cipherSuites: List<String>? = null

    @Volatile private var serverCert: ServerCert = ServerCert.STANDARD

    fun start(): TlsProbeServer =
        apply {
            workers.submit { acceptLoop() }
        }

    fun stop() {
        serverSocket.close()
        workers.shutdownNow()
        Files.deleteIfExists(caPem)
    }

    fun baseUrl(): String = "https://localhost:${serverSocket.localPort}/push/v1/"

    /** Path to the PEM the service is pointed at via `push-gateway.trusted-ca-paths`. */
    fun caPemPath(): String = caPem.toAbsolutePath().toString()

    /**
     * Restricts what the server side will accept on subsequent connections. `null` means "leave the JSSE
     * default", so `serve()` with no arguments is the permissive baseline used by the offer-side scenarios.
     */
    fun serve(
        protocols: List<String>? = null,
        cipherSuites: List<String>? = null,
        certificate: ServerCert = ServerCert.STANDARD,
    ) {
        this.protocols = protocols
        this.cipherSuites = cipherSuites
        this.serverCert = certificate
    }

    fun reset() {
        // A refused handshake is retried (push-gateway.retry.give-up-after), and those retries outlive the
        // assertions of the scenario that triggered them. Settling first keeps them out of the next one —
        // otherwise a stale, failed connection is what the next scenario's awaitConnection() returns.
        // A scenario that never connected has nothing in flight to wait for.
        if (connections.isNotEmpty()) {
            Thread.sleep(SETTLE_MILLIS)
        }
        connections.clear()
        serve()
    }

    fun connections(): List<TlsProbeConnection> = connections.toList()

    /**
     * Waits for the dispatcher to reach the probe and returns the first connection it made.
     *
     * Callers must not assert on the connection *count*: a rejected handshake is retried
     * (`push-gateway.retry.give-up-after`), so a failing scenario produces several connections.
     */
    fun awaitConnection(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): TlsProbeConnection? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (connections.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return connections.firstOrNull()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    return // the socket was closed by stop()
                }
            workers.submit { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        var hello: ClientHello? = null
        var handshakeSucceeded = false
        var httpRequest: String? = null
        try {
            val record = readFirstRecord(socket.getInputStream()) ?: return
            hello = runCatching { ClientHello.parse(record) }.getOrNull()

            val ssl = negotiate(socket, record)
            handshakeSucceeded = true

            httpRequest = readHttpRequest(ssl)
            httpRequest?.let { respondTo(ssl, it) }
            ssl.close()
        } catch (_: IOException) {
            // An aborted handshake is a valid outcome
        } finally {
            connections.add(TlsProbeConnection(hello, handshakeSucceeded, httpRequest))
            runCatching { socket.close() }
        }
    }

    private fun negotiate(
        socket: Socket,
        consumed: ByteArray,
    ): SSLSocket {
        val context =
            when (serverCert) {
                ServerCert.STANDARD -> material.standardContext
                ServerCert.WEAK_RSA -> material.weakRsaContext
            }
        val ssl =
            context.socketFactory.createSocket(socket, ByteArrayInputStream(consumed), true) as SSLSocket
        ssl.useClientMode = false
        protocols?.let { ssl.enabledProtocols = it.toTypedArray() }
        cipherSuites?.let { ssl.enabledCipherSuites = it.toTypedArray() }
        ssl.startHandshake()
        return ssl
    }

    private fun readFirstRecord(input: InputStream): ByteArray? {
        val header = input.readNBytes(RECORD_HEADER_BYTES)
        if (header.size < RECORD_HEADER_BYTES) return null
        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        return header + input.readNBytes(length)
    }

    private fun readHttpRequest(socket: SSLSocket): String? {
        val input = socket.inputStream
        val head = StringBuilder()
        while (!head.endsWith(HEADER_TERMINATOR)) {
            val next = input.read()
            if (next == -1) return null
            head.append(next.toChar())
        }
        val length =
            CONTENT_LENGTH
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        return head.toString() + String(input.readNBytes(length), Charsets.UTF_8)
    }

    private fun respondTo(
        socket: SSLSocket,
        request: String,
    ) {
        val id = NOTIFICATION_ID.find(request)?.groupValues?.get(1)
        val body =
            if (id == null) {
                """{"results":[],"summary":{"total":0,"successful":0,"failed":0,"partial":0}}"""
            } else {
                """{"results":[{"id":"$id","status":"success","rejected":[]}],""" +
                    """"summary":{"total":1,"successful":1,"failed":0,"partial":0}}"""
            }
        val bytes = body.toByteArray()
        val head =
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.outputStream.apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val RECORD_HEADER_BYTES = 5
        private const val DEFAULT_TIMEOUT_MILLIS = 5000L
        private const val POLL_INTERVAL_MILLIS = 50L

        // Has to outlast the whole retry chain of the previous scenario: with %test's
        // push-gateway.retry.give-up-after = PT1.5S and initial-delay = PT0.25S the last retry lands ~0.75s
        // after the first attempt. Revisit when either of those two properties changes.
        private const val SETTLE_MILLIS = 1_500L
        private const val HEADER_TERMINATOR = "\r\n\r\n"
        private val CONTENT_LENGTH = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        // The batch item id, anchored on the opening brace so it cannot match the device's "app_id".
        private val NOTIFICATION_ID = Regex("\\{\"id\":\"([^\"]*)\"")

        fun create(): TlsProbeServer {
            val material = TlsProbeCertificates.generate()
            val pem = Files.createTempFile("tls-probe-ca", ".pem")
            Files.writeString(pem, material.trustedPem)
            return TlsProbeServer(
                material,
                ServerSocket(0, SOCKET_BACKLOG, InetAddress.getLoopbackAddress()),
                pem,
            )
        }

        private const val SOCKET_BACKLOG = 50
    }
}
