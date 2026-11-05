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

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stand-in for an exception of unknown origin that is about to be logged: keeps the type and stack trace
 * of every throwable in the cause chain (and of suppressed ones), but drops all messages.
 *
 * A message is free text chosen by whoever threw, and some quote their input - a kotlinx
 * `SerializationException` quotes the JSON it failed on, which may be an opened record holding a
 * notification payload, a pushkey or an `iss`. Logs, and with them the OTLP export, must hold none of
 * that, so exceptions we did not raise ourselves are logged through [redacted].
 */
class RedactedThrowable private constructor(
    originalType: String,
    cause: Throwable?,
) : Throwable("$originalType: [message redacted]", cause) {
    override fun toString(): String = message.orEmpty()

    companion object {
        internal fun of(original: Throwable): RedactedThrowable =
            redact(original, Collections.newSetFromMap(IdentityHashMap()))

        private fun redact(
            original: Throwable,
            seen: MutableSet<Throwable>,
        ): RedactedThrowable {
            seen += original
            val cause = original.cause?.takeUnless { it in seen }?.let { redact(it, seen) }
            return RedactedThrowable(original.javaClass.name, cause).apply {
                stackTrace = original.stackTrace
                original.suppressed.filterNot { it in seen }.forEach { addSuppressed(redact(it, seen)) }
            }
        }
    }
}

fun Throwable.redacted(): Throwable = this as? RedactedThrowable ?: RedactedThrowable.of(this)
