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
package de.gematik.zeta.notificationservice.adapter.inbound.error

import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException
import de.gematik.zeta.notificationservice.domain.error.HistoryDisabledException
import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import de.gematik.zeta.notificationservice.domain.error.InvalidUserIdException
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import de.gematik.zeta.notificationservice.domain.error.NotificationBacklogException
import de.gematik.zeta.notificationservice.domain.error.NotificationNotFoundException
import de.gematik.zeta.notificationservice.domain.error.NotificationServiceException
import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PusherOfOtherUserException
import de.gematik.zeta.notificationservice.domain.error.UserNotFoundException
import de.gematik.zeta.notificationservice.domain.error.redacted
import io.smallrye.faulttolerance.api.RateLimitException
import jakarta.ws.rs.WebApplicationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

class RestExceptionMappers {
    private val logger = Logger.getLogger(RestExceptionMappers::class.java)

    @OptIn(ExperimentalSerializationApi::class)
    @ServerExceptionMapper
    fun toResponse(e: Throwable): RestResponse<ErrorResponse> =
        when (e) {
            is NotificationServiceException -> {
                domainErrorResponse(e)
            }

            // A field the schema declares as required was absent from the body. `missingFields` carries the
            // @SerialName values, i.e. the JSON names the client sent, not our Kotlin property names.
            is MissingFieldException -> {
                val detail = "Missing parameters: " + e.missingFields.joinToString(", ")
                errorResponse(BAD_REQUEST, "M_MISSING_PARAM", detail)
            }

            // Deliberately drops the exception message. It quotes the offending part of the request body, which
            // can hold the pushkey (the device token) and `iss` (the initial shared secret), and errorDetail is
            // returned to the client and logged (A_29978).
            is SerializationException -> {
                errorResponse(BAD_REQUEST, "M_INVALID_PARAM", "The request body could not be parsed")
            }

            // Safe to surface: the body-derived cases are intercepted above, so what is left are argument
            // violations raised by our own code, whose messages name fields rather than quote values.
            is IllegalArgumentException -> {
                errorResponse(BAD_REQUEST, "INVALID_PARAM", e.message)
            }

            is WebApplicationException if e.response.status in CLIENT_ERROR_RANGE -> {
                errorResponse(e.response.status, "INVALID_PARAM", e.message)
            }

            is StaleRecordException -> {
                errorResponse(SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Concurrent modification, please retry")
            }

            // Without the HSM no payload can be sealed or opened (A_29958), so the service is
            // unavailable rather than broken. The message names the failed RPC and its gRPC status
            // only, never key material.
            is HsmProxyException -> {
                logger.error("HSM proxy unavailable while processing request", e)
                errorResponse(SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Key management is unavailable")
            }

            is RateLimitException -> {
                val retryAfterSeconds = Math.ceilDiv(e.retryAfterMillis, 1000).coerceAtLeast(1)
                errorResponse(TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests", retryAfterSeconds)
            }

            else -> {
                logger.error("Unhandled exception while processing request", e.redacted())
                errorResponse(INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null)
            }
        }

    private fun domainErrorResponse(e: NotificationServiceException): RestResponse<ErrorResponse> =
        when (e) {
            is UserNotFoundException -> errorResponse(NOT_FOUND, "USER_NOT_FOUND", e.message)
            is PusherNotFoundException -> errorResponse(NOT_FOUND, "PUSHER_NOT_FOUND", e.message)
            is PusherOfOtherUserException -> errorResponse(FORBIDDEN, "M_FORBIDDEN", e.message)
            is NotificationNotFoundException -> errorResponse(NOT_FOUND, "NOTIFICATION_NOT_FOUND", e.message)
            is HistoryDisabledException -> errorResponse(NOT_FOUND, "HISTORY_DISABLED", e.message)
            is ChannelNotAllowedException -> errorResponse(FORBIDDEN, "CHANNEL_NOT_ALLOWED", e.message)
            is InvalidChannelException -> errorResponse(BAD_REQUEST, "M_INVALID_PARAM", e.message)
            is InvalidUserIdException -> errorResponse(BAD_REQUEST, "M_INVALID_PARAM", e.message)
            is InvalidPusherException -> errorResponse(BAD_REQUEST, "M_INVALID_PARAM", e.message)
            is MissingPusherFieldsException -> errorResponse(BAD_REQUEST, "M_MISSING_PARAM", e.message)
            is NotificationBacklogException -> errorResponse(SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", e.message)
            is PayloadTooLargeException -> errorResponse(PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", e.message)
        }

    private fun errorResponse(
        statusCode: Int,
        errorCode: String,
        errorDetail: String?,
        retryAfterSeconds: Long? = SERVICE_UNAVAILABLE_RETRY_AFTER_SECONDS.takeIf { statusCode == SERVICE_UNAVAILABLE },
    ): RestResponse<ErrorResponse> =
        RestResponse.ResponseBuilder
            .create<ErrorResponse>(statusCode)
            .entity(ErrorResponse(errorCode, errorDetail))
            .apply { retryAfterSeconds?.let { header(RETRY_AFTER, it) } }
            .build()

    private companion object {
        const val RETRY_AFTER = "Retry-After"
        const val SERVICE_UNAVAILABLE_RETRY_AFTER_SECONDS = 5L
        val TOO_MANY_REQUESTS = RestResponse.Status.TOO_MANY_REQUESTS.statusCode
        val BAD_REQUEST = RestResponse.Status.BAD_REQUEST.statusCode
        val FORBIDDEN = RestResponse.Status.FORBIDDEN.statusCode
        val NOT_FOUND = RestResponse.Status.NOT_FOUND.statusCode
        val INTERNAL_SERVER_ERROR = RestResponse.Status.INTERNAL_SERVER_ERROR.statusCode
        val SERVICE_UNAVAILABLE = RestResponse.Status.SERVICE_UNAVAILABLE.statusCode
        val PAYLOAD_TOO_LARGE = RestResponse.Status.PAYLOAD_TOO_LARGE.statusCode
        val CLIENT_ERROR_RANGE = 400..499
    }
}
