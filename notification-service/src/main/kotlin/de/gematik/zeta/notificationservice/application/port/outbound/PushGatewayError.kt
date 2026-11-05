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
package de.gematik.zeta.notificationservice.application.port.outbound

/** Transport/protocol failures of the Push Gateway outbound port. */
sealed class PushGatewayError(
    message: String,
    cause: Throwable?,
    val retryable: Boolean,
) : RuntimeException(message, cause) {
    /** The Push Gateway answered with an HTTP 5xx status. */
    class ServerError(
        cause: Throwable?,
    ) : PushGatewayError("Push Gateway returned server error ", cause, retryable = true)

    /** The Push Gateway rate-limited the call (HTTP 429). */
    class Throttled(
        cause: Throwable?,
    ) : PushGatewayError("Push Gateway rate-limited the request", cause, retryable = true)

    /** The Push Gateway rejected the request itself (HTTP 4xx other than 429). */
    class RequestRejected(
        val status: Int,
        cause: Throwable?,
    ) : PushGatewayError("Push Gateway rejected the request with status $status", cause, retryable = false)

    /** The Push Gateway answered 2xx but the body did not match the contract. */
    class UnparsableResponse(
        cause: Throwable?,
    ) : PushGatewayError("Push Gateway response could not be parsed", cause, retryable = false)

    /** The connection to the Push Gateway failed (refused, timeout, TLS/pinning rejection). */
    class ConnectionFailed(
        cause: Throwable?,
    ) : PushGatewayError("Push Gateway connection failed", cause, retryable = true)

    /** The target URL is not among the operator-configured Push Gateways. */
    class UntrustedTarget(
        url: String,
    ) : PushGatewayError("Push Gateway URL is not configured/allowed: $url", null, retryable = false)
}
