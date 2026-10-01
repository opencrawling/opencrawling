/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
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
 */
package org.opencrawling.runtime.service;

import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.service.ConnectorCheckerService.ConnectionCheckResult;

class ConnectorCheckerServiceTest {

    private final ConnectorCheckerService checker = new ConnectorCheckerService();

    @Test
    void testCheckFileSystemConnector() {
        ConnectorDTO connector = new ConnectorDTO("FS_Test", "Test FileSystem", "repository",
                "org.opencrawling.crawler.connectors.filesystem.FileConnector", 10, Map.of("path", "."));

        ConnectionCheckResult result = checker.check(connector);
        assertTrue(result.success());
        assertTrue(result.message().contains("Local file system path exists"));
    }

    @Test
    void testCheckQdrantConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("Qdrant_Test", "Test Qdrant", "output",
                "org.opencrawling.qdrant.QdrantOutputConnector", 10, Map.of("qdrantUri", "http://127.0.0.1:59999"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Qdrant"));
    }

    @Test
    void testCheckCamundaConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("Camunda_Test", "Test Camunda", "repository",
                "org.opencrawling.camunda.CamundaRepositoryConnector", 10, Map.of("url", "http://127.0.0.1:59999/engine-rest"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Camunda"));
    }

    @Test
    void testCheckFlowableConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("Flowable_Test", "Test Flowable", "repository",
                "org.opencrawling.flowable.FlowableRepositoryConnector", 10, Map.of("endpoint", "http://127.0.0.1:59999/flowable-rest/service"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Flowable"));
    }

    @Test
    void testCheckLuxirConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("Luxir_Test", "Test Luxir", "output",
                "org.opencrawling.luxir.LuxirOutputConnector", 10, Map.of("luxirEndpoint", "http://127.0.0.1:59999"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Luxir"));
    }

    @Test
    void testCheckDoxisConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("Doxis_Test", "Test Doxis", "output",
                "org.opencrawling.doxis.output.DoxisOutputConnector", 10, Map.of(
                        "doxisBaseUrl", "http://127.0.0.1:59999/restws/publicws/rest/api/v1",
                        "doxisCustomerName", "faststarter",
                        "doxisUsername", "Supervisor",
                        "doxisPassword", "secret"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Doxis"));
    }

    @Test
    void testCheckDoxisConnectorMissingCredentials() {
        ConnectorDTO connector = new ConnectorDTO("Doxis_Test", "Test Doxis", "output",
                "org.opencrawling.doxis.output.DoxisOutputConnector", 10, Map.of("doxisBaseUrl", "http://127.0.0.1:59999"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("username and password"));
    }

    @Test
    void testCheckStormCrawlerConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("StormCrawler_Test", "Test StormCrawler", "repository",
                "org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector", 10, Map.of(
                        "nimbusRestUrl", "http://127.0.0.1:59999",
                        "nimbusHost", "127.0.0.1",
                        "nimbusPort", "59999"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Apache Storm"));
    }

    @Test
    void testCheckSeaTunnelConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("SeaTunnel_Test", "Test SeaTunnel", "output",
                "org.opencrawling.seatunnel.SeaTunnelOutputConnector", 10, Map.of("seaTunnelRestUrl", "http://127.0.0.1:59999"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Apache SeaTunnel"));
    }

    @Test
    void testCheckApsConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("APS_Test", "Test APS", "repository",
                "org.opencrawling.aps.ApsRepositoryConnector", 10, Map.of("url", "http://127.0.0.1:59999/activiti-app/api/enterprise"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to Alfresco Process Services"));
    }

    @Test
    void testCheckCmisConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("CMIS_Test", "Test CMIS", "repository",
                "org.opencrawling.cmis.CmisRepositoryConnector", 10, Map.of("endpointUrl", "http://127.0.0.1:59999/browser"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to CMIS endpoint"));
    }
}
