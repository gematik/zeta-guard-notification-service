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

import de.gematik.zeta.notificationservice.integration.quarkusResources.DatabaseEngine
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithDatabase
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithHsmProxySimulator
import de.gematik.zeta.notificationservice.integration.quarkusResources.WithPushGatewayMock
import io.quarkus.test.junit.QuarkusIntegrationTest

@QuarkusIntegrationTest
@WithDatabase(DatabaseEngine.POSTGRES)
@WithHsmProxySimulator
@WithPushGatewayMock
class AcceptanceIT : AcceptanceTestCollection()

@QuarkusIntegrationTest
@WithDatabase(DatabaseEngine.MARIADB)
@WithHsmProxySimulator
@WithPushGatewayMock
class MariaDbAcceptanceIT : AcceptanceTestCollection()

@QuarkusIntegrationTest
@WithDatabase(DatabaseEngine.MSSQL)
@WithHsmProxySimulator
@WithPushGatewayMock
class MsSqlAcceptanceIT : AcceptanceTestCollection()

@QuarkusIntegrationTest
@WithDatabase(DatabaseEngine.MYSQL)
@WithHsmProxySimulator
@WithPushGatewayMock
class MySqlAcceptanceIT : AcceptanceTestCollection()

@QuarkusIntegrationTest
@WithDatabase(DatabaseEngine.ORACLE)
@WithHsmProxySimulator
@WithPushGatewayMock
class OracleAcceptanceIT : AcceptanceTestCollection()
