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
package de.gematik.zeta.notificationservice.application.repository

import de.gematik.zeta.notificationservice.application.config.HistoryFeature
import de.gematik.zeta.notificationservice.domain.model.DeviceMessageHistory
import de.gematik.zeta.notificationservice.domain.model.HistoryMessage
import de.gematik.zeta.notificationservice.domain.model.UserId

class ToggleableDeviceMessageHistoryRepository(
    private val feature: HistoryFeature,
    private val delegate: DeviceMessageHistoryRepository,
) : DeviceMessageHistoryRepository {
    override fun findBy(
        userId: UserId,
        pushKey: String,
    ): DeviceMessageHistory? = if (feature.isEnabled) delegate.findBy(userId, pushKey) else null

    override fun append(
        userId: UserId,
        pushKey: String,
        message: HistoryMessage,
    ) {
        if (feature.isEnabled) {
            delegate.append(userId, pushKey, message)
        }
    }

    override fun delete(
        userId: UserId,
        pushKey: String,
    ) {
        delegate.delete(userId, pushKey)
    }
}
