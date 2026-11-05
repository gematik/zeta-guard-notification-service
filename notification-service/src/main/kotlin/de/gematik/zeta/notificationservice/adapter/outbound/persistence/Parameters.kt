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
package de.gematik.zeta.notificationservice.adapter.outbound.persistence

import de.gematik.zeta.notificationservice.application.port.outbound.StorageRecord
import java.sql.PreparedStatement
import java.sql.Types

/**
 * The JDBC mechanics [JdbcStorageRecordDAO] is built on, kept apart from the storage contract it
 * implements so that each reads on its own terms.
 *
 * Every statement runs on its own pooled connection in autocommit: the port's unit of work is a
 * single row, and its concurrency control is the compare-and-set verifier rather than a
 * transaction. The one exception is a move between two ids, which has to be atomic.
 */
internal class Parameters(
    private val statement: PreparedStatement,
) {
    private var position = 0

    fun string(value: String) = statement.setString(++position, value)

    fun bytes(value: ByteArray) = statement.setBytes(++position, value)

    fun int(value: Int) = statement.setInt(++position, value)

    fun long(value: Long) = statement.setLong(++position, value)

    fun nullableLong(value: Long?) {
        val index = ++position
        if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
    }

    /**
     * The payload columns, in the order every write statement lists them. The verifier is bound by
     * the caller, never taken from [record] — a caller's own verifier is deliberately ignored.
     */
    fun payload(record: StorageRecord) {
        bytes(record.payload.toByteArray(Charsets.UTF_8))
        string(record.keychainId)
    }
}
