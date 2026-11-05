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
package de.gematik.zeta.notificationservice.domain.error

sealed class NotificationServiceException(
    message: String,
) : RuntimeException(message)

class UserNotFoundException :
    NotificationServiceException("No user is known to the Notification Service for the given identifier.")

class InvalidUserIdException(
    expected: String = "KVNR or Telematik-ID",
) : NotificationServiceException("The user identifier is not a valid $expected")

class InvalidPusherException(
    message: String,
) : NotificationServiceException(message)

/**
 * Required pusher fields were absent from the request. Mapped to 400 `M_MISSING_PARAM`, distinct from the
 * `M_INVALID_PARAM` used for values that are present but malformed.
 *
 * All missing fields are reported at once, so a client learns everything it has to fix from one response
 * ("Missing parameters: lang, data" as in the contract's example for `POST /pushers/set`).
 *
 * [fields] holds the JSON names, not the Kotlin property names: the message is client-facing.
 */
class MissingPusherFieldsException(
    val fields: List<String>,
) : NotificationServiceException("Missing parameters: ${fields.joinToString(", ")}")

class InvalidChannelException(
    message: String,
) : NotificationServiceException(message)

class ChannelNotAllowedException(
    channelId: String,
) : NotificationServiceException("Channel '$channelId' is not configured on this instance")

/**
 * The authenticated user has no device registered for the requested pushkey. Mapped to 404, which is
 * also the answer for a pushkey belonging to another user: records are reachable only per user, so the
 * two are indistinguishable, and reporting them differently would make the endpoint an oracle for
 * probing arbitrary device tokens (A_29978).
 *
 * The message deliberately omits the pushkey: it is the APNs/FCM device token, and messages are echoed
 * into `errorDetail` and logs.
 */
class PusherNotFoundException : NotificationServiceException("No device is registered for the authenticated user")

/**
 * No notification with the requested id is readable for the authenticated user. Same reasoning as
 * [PusherNotFoundException]: an unknown id and another user's id give the identical answer, and the
 * message omits the id.
 */
class NotificationNotFoundException :
    NotificationServiceException("No notification is stored for the authenticated user")

class HistoryDisabledException : NotificationServiceException("The message history is not enabled on this instance")

class NotificationBacklogException : NotificationServiceException("The notification dispatch queue is at capacity")

class PayloadTooLargeException(
    maxBytes: Int,
) : NotificationServiceException("The notification payload exceeds the maximum allowed size of $maxBytes bytes")
