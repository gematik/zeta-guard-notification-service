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

import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayError
import de.gematik.zeta.notificationservice.application.port.outbound.PushGatewayPort
import de.gematik.zeta.notificationservice.domain.model.DeliveryStatus
import de.gematik.zeta.notificationservice.domain.model.Device
import de.gematik.zeta.notificationservice.domain.model.DispatchItemResult
import de.gematik.zeta.notificationservice.domain.model.DispatchResult
import de.gematik.zeta.notificationservice.domain.model.DispatchSummary
import de.gematik.zeta.notificationservice.domain.model.EncryptedPushItem
import de.gematik.zeta.notificationservice.domain.model.PlainPushItem
import de.gematik.zeta.notificationservice.domain.model.Priority
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.DelegatingSslContext
import io.netty.handler.ssl.SslContext
import io.quarkus.logging.Log
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import io.quarkus.runtime.Startup
import io.quarkus.tls.BaseTlsConfiguration
import io.quarkus.tls.TlsConfiguration
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.net.JdkSSLEngineOptions
import io.vertx.core.net.KeyCertOptions
import io.vertx.core.net.SSLOptions
import io.vertx.core.net.TrustOptions
import io.vertx.core.spi.tls.SslContextFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

// The Push Gateway is an external component (not part of ZETA Guard), so it is reached directly over the
// internet rather than through the service mesh — mesh mTLS is reserved for internal ZETA Guard traffic.
// When the target is HTTPS with a CA the default trust store does not know (e.g. Let's Encrypt staging or a
// self-signed test CA), list its PEM via push-gateway.trusted-ca-paths. Those certificates are trusted *in
// addition to* the system default trust anchors (the client still trusts publicly-signed CAs), because
// Quarkus' own trust-store config would replace rather than extend the defaults. Left empty (production,
// publicly-trusted CA), the default trust store is used unchanged.
@Startup
@ApplicationScoped
class RestPushGatewayClient(
    @param:ConfigProperty(name = "push-gateway.allowed-base-urls") private val allowedBaseUrls: List<String>,
    @param:ConfigProperty(name = "push-gateway.trusted-ca-paths") private val trustedCaPaths: Optional<List<String>>,
    @param:ConfigProperty(name = "push-gateway.mtls.client-certificate-path")
    private val clientCaPath: Optional<String>,
    @param:ConfigProperty(name = "push-gateway.mtls.client-key-path") private val clientKeyPath: Optional<String>,
    @param:ConfigProperty(name = "push-gateway.mtls.client-key-password")
    private val clientKeyPassword: Optional<String>,
    @param:ConfigProperty(name = "push-gateway.connect-timeout") private val connectTimeout: Duration,
    @param:ConfigProperty(name = "push-gateway.read-timeout") private val readTimeout: Duration,
) : PushGatewayPort {
    // Built once: default trust anchors + the configured PEM CA(s) (null → default trust store), plus the
    // optional mTLS client key.
    private val tlsConfiguration: TlsConfiguration = buildTlsConfiguration(buildAdditiveTrustStore(), buildKeyStore())

    private val applicationClassLoader: ClassLoader = RestPushGatewayClient::class.java.classLoader

    private val clients: Map<String, PushGatewayRestApi> =
        allowedBaseUrls.associateWith { buildClient(it) }

    override suspend fun sendEncrypted(
        targetUrl: String,
        items: List<EncryptedPushItem>,
    ): DispatchResult {
        val api = resolveClient(targetUrl)
        return execute { api.notifyEncryptedBatch(toEncryptedRequest(items)) }
    }

    override suspend fun sendPlain(
        targetUrl: String,
        items: List<PlainPushItem>,
    ): DispatchResult {
        val api = resolveClient(targetUrl)
        return execute { api.notifyPlainBatch(toPlainRequest(items)) }
    }

    /** Runs a Push Gateway call and maps transport/protocol failures to [PushGatewayError]s. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(call: () -> Uni<PushGatewayBatchResponse>): DispatchResult {
        val response =
            try {
                withApplicationClassLoader(call).awaitSuspending()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw toError(e)
            }
        return toResult(response)
    }

    // The generated REST client stub resolves its response type via Class.forName on the context
    // class loader; suspending moves the call to a thread whose loader lacks application classes.
    private fun <T> withApplicationClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = applicationClassLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun toError(e: Exception): PushGatewayError =
        when (e) {
            is WebApplicationException -> statusError(e)
            is SerializationException -> PushGatewayError.UnparsableResponse(e)
            is ProcessingException, is IOException -> PushGatewayError.ConnectionFailed(e)
            else -> throw e
        }

    private fun statusError(e: WebApplicationException): PushGatewayError {
        val status = e.response.status
        return when {
            status >= Response.Status.INTERNAL_SERVER_ERROR.statusCode -> PushGatewayError.ServerError(e)
            status == TOO_MANY_REQUESTS -> PushGatewayError.Throttled(e)
            status >= Response.Status.BAD_REQUEST.statusCode -> PushGatewayError.RequestRejected(status, e)
            else -> PushGatewayError.UnparsableResponse(e)
        }
    }

    private fun resolveClient(targetUrl: String): PushGatewayRestApi =
        clients[targetUrl] ?: throw PushGatewayError.UntrustedTarget(targetUrl)

    private fun buildClient(targetUrl: String): PushGatewayRestApi {
        // Quarkus decides TLS per request with a case-sensitive "https" check, so normalise the scheme first.
        val uri = URI.create(targetUrl).let { URI(it.scheme.lowercase() + targetUrl.substring(it.scheme.length)) }
        val builder =
            QuarkusRestClientBuilder
                .newBuilder()
                .baseUri(uri)
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)

        // Host verification stays on (the ingress cert's SAN covers the target host).
        // A TLS configuration also switches the client to the https.proxy* system properties, so an http://
        // target (compose, tests) keeps http.proxy*. Only https:// targets handshake anyway.
        if (uri.scheme == "https") {
            builder.tlsConfiguration(tlsConfiguration)
            builder.httpClientOptionsCustomizer { it.sslEngineOptions = NamedGroupsSslEngineOptions() }
        }

        return builder.build(PushGatewayRestApi::class.java)
    }

    // A TlsConfiguration makes the client ignore builder.trustStore/keyStore, so the trust and key material
    // travel through it; a null trust store means the JDK default. The client reads only the *Options getters,
    // each twice per build, so the managers are built here once.
    private fun buildTlsConfiguration(
        trustStore: KeyStore?,
        keyStore: KeyStore?,
    ): TlsConfiguration {
        // Always set, so the key-size floor also applies with the default trust store.
        val trustOptions = TrustOptions.wrap(MinimumKeySizeTrustManager(platformTrustManager(trustStore)))
        val keyOptions =
            keyStore?.let { store ->
                KeyCertOptions.wrap(
                    KeyManagerFactory
                        .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        .apply { init(store, "".toCharArray()) },
                )
            }
        return object : BaseTlsConfiguration() {
            override fun getTrustStoreOptions(): TrustOptions = trustOptions

            override fun getKeyStoreOptions(): KeyCertOptions? = keyOptions

            override fun getSSLOptions(): SSLOptions =
                SSLOptions().apply {
                    enabledSecureTransportProtocols = TLS_PROTOCOLS
                    TLS_CIPHER_SUITES.forEach { addEnabledCipherSuite(it) }
                }
        }
    }

    private fun buildKeyStore(): KeyStore? {
        val caPath = clientCaPath.orElse(null)?.let { Path.of(it) }
        val keyPath = clientKeyPath.orElse(null)?.let { Path.of(it) }

        require((caPath != null) == (keyPath != null)) {
            "push-gateway.mtls.client-certificate-path and push-gateway.mtls.client-key-path" +
                " are required for push gateway mTLS"
        }

        if (caPath == null || keyPath == null) {
            return null
        }

        val keyPassword = clientKeyPassword.orElse(null)?.toCharArray() ?: "".toCharArray()

        require(Files.isReadable(caPath)) { "push-gateway.mtls.client-certificate-path is not readable: $caPath" }
        require(Files.isReadable(keyPath)) { "push-gateway.mtls.client-key-path is not readable: $keyPath" }

        Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ?: Security.addProvider(BouncyCastleProvider())

        val certChain = mutableListOf<Certificate>()

        processCertificates(caPath) {
            certChain.add(it)
        }

        val key = readPrivateKey(keyPath, keyPassword)

        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
            }

        // keyPassword only decrypts the PEM; this store lives in memory only, and buildTlsConfiguration opens
        // it with an empty password.
        keyStore.setKeyEntry("push-gateway", key, CharArray(0), certChain.toTypedArray())

        return keyStore
    }

    private fun readPrivateKey(
        keyPath: Path,
        password: CharArray,
    ): PrivateKey {
        Files.newBufferedReader(keyPath).use { keyReader ->
            PEMParser(keyReader).use { pemParser ->
                val obj = pemParser.readObject()

                val converter = JcaPEMKeyConverter()

                return when (obj) {
                    is PEMEncryptedKeyPair -> {
                        require(password.isNotEmpty()) {
                            "push-gateway.mtls.client-key-password must be provided for encrypted key"
                        }

                        val pair =
                            obj.decryptKeyPair(
                                JcePEMDecryptorProviderBuilder()
                                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                    .build(password),
                            )

                        converter.getKeyPair(pair).private
                    }

                    is PEMKeyPair -> {
                        converter.getKeyPair(obj).private
                    }

                    is PrivateKeyInfo -> {
                        converter.getPrivateKey(obj)
                    }

                    is PKCS8EncryptedPrivateKeyInfo -> {
                        require(password.isNotEmpty()) {
                            "push-gateway.mtls.client-key-password must be provided for encrypted key"
                        }

                        val decryptor =
                            JceOpenSSLPKCS8DecryptorProviderBuilder()
                                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                .build(password)
                        val keyInfo = obj.decryptPrivateKeyInfo(decryptor)
                        converter.getPrivateKey(keyInfo)
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported private key type: ${obj::class.simpleName}")
                    }
                }
            }
        }
    }

    /**
     * Builds a trust store that trusts the configured PEM CA(s) *in addition to* the system default trust
     * anchors, or returns null when none are configured (use the default trust store unchanged). Quarkus'
     * trust-store config would replace the defaults, so the merge is done here (see quarkus#29146).
     */
    private fun buildAdditiveTrustStore(): KeyStore? {
        val paths = trustedCaPaths.orElse(emptyList()).filter { it.isNotBlank() }
        if (paths.isEmpty()) return null

        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        val defaults = defaultTrustAnchors()
        if (defaults.isEmpty()) {
            // In a correctly-built native image the runtime default anchors are present; an empty set would
            // mean the merge silently degraded to replace-only, dropping trust for publicly-signed CAs.
            Log.warn("No default trust anchors available — Push Gateway trust will only cover the configured CAs")
        }
        defaults.forEachIndexed { i, cert -> store.setCertificateEntry("default-$i", cert) }

        var custom = 0
        paths.forEach { p ->
            val path = Path.of(p)
            require(Files.isReadable(path)) { "push-gateway.trusted-ca-paths entry is not readable: $p" }
            processCertificates(path) {
                store.setCertificateEntry("custom-${custom++}", it)
            }
        }
        Log.info("Push Gateway TLS trust: ${defaults.size} default anchor(s) + $custom configured CA(s)")
        return store
    }

    private fun processCertificates(
        path: Path,
        forEach: (Certificate) -> Unit,
    ) {
        Files.newInputStream(path).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).forEach(forEach)
        }
    }

    private fun defaultTrustAnchors(): List<X509Certificate> = platformTrustManager(null).acceptedIssuers.asList()

    private fun platformTrustManager(store: KeyStore?): X509ExtendedTrustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509ExtendedTrustManager>()
            .single()

    private fun toEncryptedRequest(items: List<EncryptedPushItem>): PushGatewayEncryptedBatchRequest =
        PushGatewayEncryptedBatchRequest(
            notifications = items.map(::toPushGatewayBatchItem),
        )

    private fun toPushGatewayBatchItem(item: EncryptedPushItem): PushGatewayBatchItem =
        PushGatewayBatchItem(
            id = item.id,
            notification =
                PushGatewayEncryptedNotification(
                    ciphertext = item.ciphertext,
                    timeMessageEncrypted = item.timeMessageEncrypted,
                    keyIdentifier = item.keyIdentifier,
                    prio = toPushGatewayPrio(item.prio),
                    identifier = item.reference,
                    device = toPushGatewayDevice(item.device),
                ),
        )

    private fun toPlainRequest(items: List<PlainPushItem>): PushGatewayPlainBatchRequest =
        PushGatewayPlainBatchRequest(
            notifications = items.map(::toPushGatewayPlainBatchItem),
        )

    private fun toPushGatewayPlainBatchItem(item: PlainPushItem): PushGatewayPlainBatchItem =
        PushGatewayPlainBatchItem(
            id = item.id,
            notification =
                PushGatewayPlainNotification(
                    prio = toPushGatewayPrio(item.prio),
                    content = item.payload,
                    devices = item.devices.map(::toPushGatewayDevice),
                ),
        )

    private fun toPushGatewayDevice(device: Device): PushGatewayDevice =
        PushGatewayDevice(
            appId = device.appId,
            pushkey = device.pushkey,
            data =
                PushGatewayPusherData(
                    format = device.format,
                    platform = device.platform,
                ),
        )

    private fun toResult(response: PushGatewayBatchResponse): DispatchResult =
        DispatchResult(
            results =
                response.results.map {
                    DispatchItemResult(
                        id = it.id,
                        status = toStatus(it.status),
                        rejected = it.rejected,
                        error = it.error,
                    )
                },
            summary =
                DispatchSummary(
                    total = response.summary.total,
                    successful = response.summary.successful,
                    failed = response.summary.failed,
                    partial = response.summary.partial,
                ),
        )

    private fun toStatus(raw: String): DeliveryStatus =
        when (raw) {
            "success" -> DeliveryStatus.SUCCESS
            "partial" -> DeliveryStatus.PARTIAL
            else -> DeliveryStatus.FAILED
        }

    private fun toPushGatewayPrio(prio: Priority): String =
        when (prio) {
            Priority.HIGH -> "high"

            // NOTE: diff between our OpenAPI and the Push Gateway OpenAPI — the PGW supports only high/low.
            Priority.NORMAL -> "low"
        }

    private companion object {
        const val TOO_MANY_REQUESTS = 429

        val TLS_PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")

        // A_17322: only permitted suites may be *offered*. The TLS 1.2 ones are those GS-A_4384-03 and
        // A_17124-03 mandate, ECDSA ahead of RSA (A_17124-03 point 3); TLS 1.3 per [TR-02102-2, Tab. 13],
        // which leaves out ChaCha20-Poly1305. Order matters: the client offers them as listed.
        val TLS_CIPHER_SUITES =
            listOf(
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
            )

        // GS-A_4384-03 point 3: only P-256, P-384 and the brainpool curves may be used, and the server picks
        // from whatever the client offers. Brainpool is not offered because SunEC dropped it in JDK 16; no
        // ffdhe group is needed because TLS_CIPHER_SUITES holds no DHE suite.
        val TLS_NAMED_GROUPS = arrayOf("secp256r1", "secp384r1")

        // A_28976 / [TR-02102-2, Tab. 14]: RSA keys need at least 3000 bit. Checked on the leaf only — the key
        // used in the handshake — so CAs with RSA-2048 intermediates (e.g. Let's Encrypt) stay usable.
        const val MIN_RSA_KEY_BITS = 3000
    }

    // Runs the regular validation (path, trust anchors, hostname) first, then refuses an undersized leaf key.
    private class MinimumKeySizeTrustManager(
        private val delegate: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            delegate.checkServerTrusted(chain, authType)
            requireMinimumKeySize(chain)
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            delegate.checkServerTrusted(chain, authType, socket)
            requireMinimumKeySize(chain)
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            delegate.checkServerTrusted(chain, authType, engine)
            requireMinimumKeySize(chain)
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) = delegate.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) = delegate.checkClientTrusted(chain, authType, engine)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun requireMinimumKeySize(chain: Array<X509Certificate>) {
            val bits = (chain.first().publicKey as? RSAPublicKey)?.modulus?.bitLength() ?: return
            if (bits < MIN_RSA_KEY_BITS) {
                throw CertificateException(
                    "Push Gateway RSA key has $bits bit, at least $MIN_RSA_KEY_BITS required (TR-02102-2 Tab. 14)",
                )
            }
        }
    }

    // Vert.x has no option for the supported_groups extension, so it is set per SSLEngine instead of through
    // the JVM-wide jdk.tls.namedGroups property (read once at class init, possibly at native-image build time).
    private class NamedGroupsSslEngineOptions : JdkSSLEngineOptions() {
        // HttpClientOptions copies its engine options; the inherited copy() would drop this subclass.
        override fun copy(): JdkSSLEngineOptions = NamedGroupsSslEngineOptions()

        override fun sslContextFactory(): SslContextFactory = NamedGroupsSslContextFactory(super.sslContextFactory())
    }

    // Delegated by hand, not `by delegate`: Vert.x chains the builder calls and calls create() on the result,
    // so a delegated builder method would return the inner factory and skip the named groups.
    private class NamedGroupsSslContextFactory(
        private val delegate: SslContextFactory,
    ) : SslContextFactory {
        override fun useAlpn(useAlpn: Boolean) = apply { delegate.useAlpn(useAlpn) }

        override fun clientAuth(clientAuth: ClientAuth?) = apply { delegate.clientAuth(clientAuth) }

        override fun forClient(forClient: Boolean) = apply { delegate.forClient(forClient) }

        override fun keyMananagerFactory(kmf: KeyManagerFactory?) = apply { delegate.keyMananagerFactory(kmf) }

        override fun trustManagerFactory(tmf: TrustManagerFactory?) = apply { delegate.trustManagerFactory(tmf) }

        override fun enabledCipherSuites(suites: Set<String>?) = apply { delegate.enabledCipherSuites(suites) }

        override fun applicationProtocols(protocols: List<String>?) = apply { delegate.applicationProtocols(protocols) }

        override fun serverName(serverName: String?) = apply { delegate.serverName(serverName) }

        override fun create(): SslContext =
            object : DelegatingSslContext(delegate.create()) {
                override fun initEngine(engine: SSLEngine) {
                    engine.sslParameters = engine.sslParameters.apply { namedGroups = TLS_NAMED_GROUPS }
                }
            }
    }
}
