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

import de.gematik.zeta.notificationservice.adapter.inbound.fdv.generated.model.ChannelsInner
import de.gematik.zeta.notificationservice.application.port.outbound.HsmProxyException
import de.gematik.zeta.notificationservice.application.port.outbound.StaleRecordException
import de.gematik.zeta.notificationservice.domain.error.ChannelNotAllowedException
import de.gematik.zeta.notificationservice.domain.error.InvalidChannelException
import de.gematik.zeta.notificationservice.domain.error.InvalidPusherException
import de.gematik.zeta.notificationservice.domain.error.InvalidUserIdException
import de.gematik.zeta.notificationservice.domain.error.MissingPusherFieldsException
import de.gematik.zeta.notificationservice.domain.error.NotificationBacklogException
import de.gematik.zeta.notificationservice.domain.error.NotificationNotFoundException
import de.gematik.zeta.notificationservice.domain.error.PayloadTooLargeException
import de.gematik.zeta.notificationservice.domain.error.PusherNotFoundException
import de.gematik.zeta.notificationservice.domain.error.UserNotFoundException
import de.gematik.zeta.notificationservice.testsupport.withQuietLogger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.smallrye.faulttolerance.api.RateLimitException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jboss.resteasy.reactive.RestResponse

class RestExceptionMappersTest :
    DescribeSpec({
        val mappers = RestExceptionMappers()

        describe("toResponse") {
            it("maps UserNotFoundException to 404 with USER_NOT_FOUND") {
                val response = mappers.toResponse(UserNotFoundException())

                response.status shouldBe RestResponse.Status.NOT_FOUND.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "USER_NOT_FOUND"
            }

            it("sets no Retry-After header outside a 503") {
                val response = mappers.toResponse(UserNotFoundException())

                response.headers.getFirst("Retry-After").shouldBeNull()
            }

            it("maps PusherNotFoundException to 404 with PUSHER_NOT_FOUND") {
                val response = mappers.toResponse(PusherNotFoundException())

                response.status shouldBe RestResponse.Status.NOT_FOUND.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "PUSHER_NOT_FOUND"
            }

            it("does not leak the pushkey in the PusherNotFoundException detail") {
                val response = mappers.toResponse(PusherNotFoundException())

                (response.entity as ErrorResponse).toString() shouldNotContain "pushkey"
            }

            it("maps NotificationNotFoundException to 404 with NOTIFICATION_NOT_FOUND") {
                val response = mappers.toResponse(NotificationNotFoundException())

                response.status shouldBe RestResponse.Status.NOT_FOUND.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "NOTIFICATION_NOT_FOUND"
            }

            it("maps StaleRecordException to 503 with SERVICE_UNAVAILABLE and Retry-After") {
                val response = mappers.toResponse(StaleRecordException())

                response.status shouldBe RestResponse.Status.SERVICE_UNAVAILABLE.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "SERVICE_UNAVAILABLE"
                response.headers.getFirst("Retry-After") shouldBe 5L
            }

            it("does not leak storage internals in the StaleRecordException detail") {
                val response = mappers.toResponse(StaleRecordException())

                (response.entity as ErrorResponse).errorDetail.orEmpty() shouldNotContain "storage record"
            }

            it("maps NotificationBacklogException to 503 with SERVICE_UNAVAILABLE and Retry-After") {
                val response = mappers.toResponse(NotificationBacklogException())

                response.status shouldBe RestResponse.Status.SERVICE_UNAVAILABLE.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "SERVICE_UNAVAILABLE"
                response.headers.getFirst("Retry-After") shouldBe 5L
            }

            it("maps HsmProxyException to 503 with SERVICE_UNAVAILABLE and Retry-After") {
                val response =
                    withQuietLogger(RestExceptionMappers::class.java) {
                        mappers.toResponse(HsmProxyException("HSM proxy Encrypt failed: UNAVAILABLE"))
                    }

                response.status shouldBe RestResponse.Status.SERVICE_UNAVAILABLE.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "SERVICE_UNAVAILABLE"
                response.headers.getFirst("Retry-After") shouldBe 5L
            }

            it("maps RateLimitException to 429 with RATE_LIMITED and Retry-After rounded up to whole seconds") {
                val response = mappers.toResponse(RateLimitException(1500))

                response.status shouldBe RestResponse.Status.TOO_MANY_REQUESTS.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "RATE_LIMITED"
                response.headers.getFirst("Retry-After") shouldBe 2L
            }

            it("answers a RateLimitException without a known wait with Retry-After 1") {
                val response = mappers.toResponse(RateLimitException())

                response.headers.getFirst("Retry-After") shouldBe 1L
            }

            it("maps PayloadTooLargeException to 413 with PAYLOAD_TOO_LARGE") {
                val response = mappers.toResponse(PayloadTooLargeException(1018))

                response.status shouldBe 413
                (response.entity as ErrorResponse).errorCode shouldBe "PAYLOAD_TOO_LARGE"
                (response.entity as ErrorResponse).errorDetail.orEmpty() shouldContain "1018"
            }

            it("maps ChannelNotAllowedException to 403 with CHANNEL_NOT_ALLOWED") {
                val response = mappers.toResponse(ChannelNotAllowedException("epa.unknown"))

                response.status shouldBe RestResponse.Status.FORBIDDEN.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "CHANNEL_NOT_ALLOWED"
            }

            it("maps InvalidChannelException to 400 with M_INVALID_PARAM") {
                val response = mappers.toResponse(InvalidChannelException("bad format"))

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_INVALID_PARAM"
            }

            it("maps InvalidUserIdException to 400 with M_INVALID_PARAM") {
                val response = mappers.toResponse(InvalidUserIdException())

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_INVALID_PARAM"
            }

            it("maps InvalidPusherException to 400 with M_INVALID_PARAM") {
                val response = mappers.toResponse(InvalidPusherException("data.url is required"))

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_INVALID_PARAM"
            }

            it("maps MissingPusherFieldsException to 400 with M_MISSING_PARAM listing every field") {
                val response = mappers.toResponse(MissingPusherFieldsException(listOf("lang", "data")))

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_MISSING_PARAM"
                (response.entity as ErrorResponse).errorDetail shouldBe "Missing parameters: lang, data"
            }

            it("maps an absent required body field to 400 with M_MISSING_PARAM naming the JSON field") {
                val thrown =
                    shouldThrow<SerializationException> {
                        Json.decodeFromString<ChannelsInner>("""{"id":"channel1"}""")
                    }

                val response = mappers.toResponse(thrown)

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_MISSING_PARAM"
                (response.entity as ErrorResponse).errorDetail shouldBe "Missing parameters: status"
            }

            it("does not leak the generated model's class name when a required field is absent") {
                // kotlinx names the serial descriptor in its message; that is an internal detail.
                val thrown =
                    shouldThrow<SerializationException> {
                        Json.decodeFromString<ChannelsInner>("""{"id":"channel1"}""")
                    }

                val response = mappers.toResponse(thrown)

                (response.entity as ErrorResponse).toString() shouldNotContain "de.gematik.zeta.notificationservice"
            }

            it("maps a malformed body to 400 with M_INVALID_PARAM without echoing the body") {
                // The parser message quotes the offending input, which can hold the pushkey and the
                // initial shared secret. errorDetail is returned to the client and logged (A_29978).
                val secret = "a1b2c3d4e5f60718"
                val thrown =
                    shouldThrow<SerializationException> {
                        Json.decodeFromString<ChannelsInner>("""{"id":"channel1","iss":"$secret" """)
                    }

                val response = mappers.toResponse(thrown)

                response.status shouldBe RestResponse.Status.BAD_REQUEST.statusCode
                (response.entity as ErrorResponse).errorCode shouldBe "M_INVALID_PARAM"
                (response.entity as ErrorResponse).toString() shouldNotContain secret
            }

            it("maps an unexpected exception to 500 with INTERNAL_ERROR and no leaked detail") {
                val response =
                    withQuietLogger(RestExceptionMappers::class.java) {
                        mappers.toResponse(RuntimeException("boom"))
                    }

                response.status shouldBe RestResponse.Status.INTERNAL_SERVER_ERROR.statusCode
                val body = response.entity as ErrorResponse
                body.errorCode shouldBe "INTERNAL_ERROR"
                body.toString() shouldNotContain "boom"
            }
        }
    })
