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

import io.quarkus.test.common.QuarkusTestResource

/**
 * Runs the annotated test against a real database container of the given engine.
 *
 * Quarkus keys its runtime classloader on this annotation's values, so classes asking for different
 * engines are grouped and the previous application — and its container — is shut down before the
 * next one starts. That means one container start per engine rather than per class, and never two
 * engines live at once, which matters because two active datasources would fail the boot by design.
 *
 * Do not add `restrictToAnnotatedClass = true`: it makes that key unique per test class, which turns
 * the grouping into a container start for every single test.
 */
@QuarkusTestResource(DatabaseTestResource::class)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class WithDatabase(
    val engine: DatabaseEngine = DatabaseEngine.POSTGRES,
)
