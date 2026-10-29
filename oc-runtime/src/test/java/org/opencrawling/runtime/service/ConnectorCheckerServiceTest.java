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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
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

    @Test
    void testCheckSharePointConnectorNotConfigured() {
        ConnectorDTO connector = new ConnectorDTO("SP_Test", "Test SharePoint", "repository",
                "org.opencrawling.sharepoint.SharePointRepositoryConnector", 10, Map.of("siteUrl", "https://contoso.sharepoint.com"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("tenant-id"));
    }

    @Test
    void testCheckSharePointConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("SP_Test", "Test SharePoint", "repository",
                "org.opencrawling.sharepoint.SharePointRepositoryConnector", 10, Map.of("tenantId", "t", "clientId", "c",
                        "clientSecret", "s3cret", "siteUrl", "https://contoso.sharepoint.com/sites/x",
                        "authorityUrl", "http://127.0.0.1:59999", "graphUrl", "http://127.0.0.1:59999/v1.0", "maxRetries", "0"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to SharePoint"));
        assertFalse(result.message().contains("s3cret"));
    }

    @Test
    void testCheckSharePointConnectorSuccess() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(key);
            String body = switch (key) {
                case "POST /t/oauth2/v2.0/token" -> "{\"access_token\":\"tok\",\"expires_in\":3599}";
                case "GET /v1.0/sites/contoso.sharepoint.com:/sites/engineering" ->
                        "{\"id\":\"contoso.sharepoint.com,a,b\",\"displayName\":\"Engineering\"}";
                case "GET /v1.0/sites/contoso.sharepoint.com,a,b/drives" ->
                        "{\"value\":[{\"id\":\"b!1\",\"name\":\"Documents\",\"driveType\":\"documentLibrary\"}]}";
                default -> null;
            };
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        graph.start();
        try {
            String base = "http://127.0.0.1:" + graph.getAddress().getPort();
            Map<String, String> config = Map.of("tenantId", "t", "clientId", "c", "clientSecret", "s3cret",
                    "siteUrl", "https://contoso.sharepoint.com/sites/engineering", "authorityUrl", base, "graphUrl", base + "/v1.0");
            ConnectionCheckResult result = checker.check(new ConnectorDTO("SP_Test", "Test SharePoint", "repository",
                    "org.opencrawling.sharepoint.SharePointRepositoryConnector", 10, config));
            assertTrue(result.success(), result.message());
            assertTrue(result.message().contains("'Engineering' is accessible with 1 document libraries [Documents]"));
            assertTrue(requests.contains("POST /t/oauth2/v2.0/token"));

            Map<String, String> missing = new java.util.HashMap<>(config);
            missing.put("driveName", "Contracts");
            ConnectionCheckResult wrongLibrary = checker.check(new ConnectorDTO("SP_Test", "Test SharePoint", "repository",
                    "org.opencrawling.sharepoint.SharePointRepositoryConnector", 10, missing));
            assertFalse(wrongLibrary.success());
            assertTrue(wrongLibrary.message().contains("[Contracts]"));
        } finally {
            graph.stop(0);
        }
    }

    @Test
    void testCheckMFilesConnectorNotConfigured() {
        ConnectorDTO connector = new ConnectorDTO("MFiles_Test", "Test M-Files", "repository",
                "org.opencrawling.mfiles.MFilesRepositoryConnector", 10, Map.of("url", "http://127.0.0.1:59999/REST"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("vault-guid"));
    }

    @Test
    void testCheckMFilesConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("MFiles_Test", "Test M-Files", "repository",
                "org.opencrawling.mfiles.MFilesRepositoryConnector", 10, Map.of("url", "http://127.0.0.1:59999/REST",
                        "vaultGuid", "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}", "username", "crawler", "password", "secret"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to M-Files"));
        assertFalse(result.message().contains("secret"));
    }

    @Test
    void testCheckMFilesConnectorSuccess() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer mfws = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mfws.createContext("/REST/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(key);
            String body = switch (key) {
                case "POST /REST/server/authenticationtokens" -> "{\"Value\":\"tok\"}";
                case "GET /REST/session/vault" -> "{\"Name\":\"Contracts\",\"GUID\":\"{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}\"}";
                case "GET /REST/structure/objecttypes" -> "[{\"ID\":0,\"Name\":\"Document\"}]";
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(key.startsWith("DELETE") ? 204 : 404, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        mfws.start();
        try {
            ConnectorDTO connector = new ConnectorDTO("MFiles_Test", "Test M-Files", "repository",
                    "org.opencrawling.mfiles.MFilesRepositoryConnector", 10, Map.of(
                            "url", "http://127.0.0.1:" + mfws.getAddress().getPort() + "/REST",
                            "vaultGuid", "C0B3B52E-6512-4BD8-8F2B-2A8E9A349201", "username", "crawler", "password", "secret"));

            ConnectionCheckResult result = checker.check(connector);
            assertTrue(result.success(), result.message());
            assertTrue(result.message().contains("vault 'Contracts' is accessible (1 object types)"));
            assertTrue(requests.contains("DELETE /REST/session"), "the check ends its session");
        } finally {
            mfws.stop(0);
        }
    }

    @Test
    void testCheckFileNetConnectorNotConfigured() {
        ConnectorDTO connector = new ConnectorDTO("FileNet_Test", "Test FileNet", "repository",
                "org.opencrawling.filenet.FileNetRepositoryConnector", 10,
                Map.of("url", "http://127.0.0.1:59999/content-services-graphql/graphql", "username", "p8crawler", "password", "x"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("object-store"));
    }

    @Test
    void testCheckFileNetConnectorFailure() {
        ConnectorDTO connector = new ConnectorDTO("FileNet_Test", "Test FileNet", "repository",
                "org.opencrawling.filenet.FileNetRepositoryConnector", 10, Map.of(
                        "url", "http://127.0.0.1:59999/content-services-graphql/graphql", "objectStore", "OS1",
                        "username", "p8crawler", "password", "secret"));

        ConnectionCheckResult result = checker.check(connector);
        assertFalse(result.success());
        assertTrue(result.message().contains("Failed to connect to FileNet"));
        assertFalse(result.message().contains("secret"));
    }

    @Test
    void testCheckFileNetConnectorSuccess() throws Exception {
        List<String> xsrf = new CopyOnWriteArrayList<>();
        HttpServer csgql = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        csgql.createContext("/content-services-graphql/graphql", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("ECM-CS-XSRF-Token");
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            xsrf.add(header + "|" + cookie);
            boolean ok = header != null && ("ECM-CS-XSRF-Token=" + header).equals(cookie)
                    && exchange.getRequestHeaders().getFirst("Authorization") != null;
            byte[] bytes = ("{\"data\":{\"_apiInfo\":{\"productVersion\":\"5.6.0\"},"
                    + "\"objectStore\":{\"id\":\"{A59F88D9-759E-56FF-BCBD-5289671DA1D0}\",\"symbolicName\":\"OS1\"}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 403, ok ? bytes.length : -1);
            if (ok) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        csgql.start();
        try {
            ConnectorDTO connector = new ConnectorDTO("FileNet_Test", "Test FileNet", "repository",
                    "org.opencrawling.filenet.FileNetRepositoryConnector", 10, Map.of(
                            "url", "http://127.0.0.1:" + csgql.getAddress().getPort() + "/content-services-graphql/graphql",
                            "objectStore", "OS1", "username", "p8crawler", "password", "secret"));

            ConnectionCheckResult result = checker.check(connector);
            assertTrue(result.success(), result.message());
            assertTrue(result.message().contains("object store 'OS1' is accessible"));
            assertTrue(result.message().contains("5.6.0"));
            assertEquals(1, xsrf.size());
        } finally {
            csgql.stop(0);
        }
    }
}
