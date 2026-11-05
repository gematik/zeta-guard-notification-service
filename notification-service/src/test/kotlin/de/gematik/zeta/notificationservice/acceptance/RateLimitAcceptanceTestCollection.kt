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
package de.gematik.zeta.notificationservice.acceptance

import de.gematik.zeta.notificationservice.testsupport.TestClient.getChannels
import de.gematik.zeta.notificationservice.testsupport.TestClient.postNotification
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

abstract class RateLimitAcceptanceTestCollection {
    // A_29987

    @Test
    @DisplayName(
        "A_29987: GET /users/{userId}/channels over its rate limit is answered 429 RATE_LIMITED with Retry-After",
    )
    fun channelsOverTheRateLimitAreAnswered429() {
        getChannels(UNKNOWN_USER, "id_type=kvnr").statusCode(not(429))

        getChannels(UNKNOWN_USER, "id_type=kvnr").shouldBeRateLimited()
    }

    @Test
    @DisplayName("A_29987: POST /notifications over its rate limit is answered 429 RATE_LIMITED with Retry-After")
    fun submitOverTheRateLimitIsAnswered429() {
        postNotification(UNKNOWN_USER).statusCode(not(429))

        postNotification(UNKNOWN_USER).shouldBeRateLimited()
    }

    private fun ValidatableResponse.shouldBeRateLimited() {
        statusCode(429)
            .body("errorCode", equalTo("RATE_LIMITED"))
            .header("Retry-After", String::toInt, allOf(greaterThan(0), lessThanOrEqualTo(SECONDS_PER_HOUR)))
    }

    private companion object {
        const val UNKNOWN_USER = "X110417001"
        const val SECONDS_PER_HOUR = 3600
    }
}
