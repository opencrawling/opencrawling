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
package org.opencrawling.alfresco.output.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AlfrescoRestClientTest {

    private HttpServer server;
    private int port;
    private AlfrescoOutputProperties properties;
    private ObjectMapper objectMapper;
    private AlfrescoRestClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();

        properties = new AlfrescoOutputProperties();
        properties.setUrl("http://localhost:" + port + "/alfresco");
        properties.setUsername("admin");
        properties.setPassword("admin");
        properties.setTargetParentNodeId("-root-");
        properties.setTimeoutSeconds(5);

        objectMapper = new ObjectMapper();
        client = new AlfrescoRestClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testCheckConnection_success() {
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-", exchange -> {
            byte[] response = "{\"entry\":{\"id\":\"-root-\",\"name\":\"Company Home\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        assertThat(client.checkConnection()).isTrue();
    }

    @Test
    void testCheckConnection_failure() {
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-", exchange -> {
            exchange.sendResponseHeaders(401, -1);
        });

        assertThat(client.checkConnection()).isFalse();
    }

    @Test
    void testEnsureFolderPath_createsHierarchy() throws Exception {
        // Step 1: create folder 'finance'
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-/children", exchange -> {
            byte[] response = "{\"entry\":{\"id\":\"folder-finance-id\",\"name\":\"finance\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // Step 2: create folder '2026' under 'folder-finance-id'
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/folder-finance-id/children", exchange -> {
            byte[] response = "{\"entry\":{\"id\":\"folder-2026-id\",\"name\":\"2026\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        String finalNodeId = client.ensureFolderPath("-root-", "finance/2026");
        assertThat(finalNodeId).isEqualTo("folder-2026-id");

        // Subsequent call should return cached ID without invoking server
        String cachedNodeId = client.ensureFolderPath("-root-", "finance/2026");
        assertThat(cachedNodeId).isEqualTo("folder-2026-id");
    }

    @Test
    void testUploadNode_success() throws Exception {
        Path tempFile = Files.createTempFile("test-alfresco-upload", ".txt");
        Files.writeString(tempFile, "Hello Alfresco Migration!");

        AtomicReference<String> receivedAuth = new AtomicReference<>();
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/-root-/children", exchange -> {
            receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"entry\":{\"id\":\"node-file-123\",\"name\":\"sample.txt\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        AlfrescoRestClient.NodeCreationResult result = client.uploadNode(
                "-root-",
                "sample.txt",
                "cm:content",
                Map.of("cm:title", "Sample Document"),
                List.of("cm:versionable"),
                tempFile,
                "text/plain",
                true,
                false
        );

        assertThat(result.nodeId()).isEqualTo("node-file-123");
        assertThat(result.nodeName()).isEqualTo("sample.txt");
        assertThat(result.isUpdated()).isFalse();
        assertThat(receivedAuth.get()).startsWith("Basic ");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void testUpdatePermissions() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();

        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/node-123", exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                receivedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        SecurityConfig sec = new SecurityConfig(true, List.of(
                new PermissionRule("group:engineering", "group", "Engineering", "read"),
                new PermissionRule("user:john", "user", "John", "read"),
                new PermissionRule("group:interns", "group", "Interns", "deny")
        ));

        client.updatePermissions("node-123", sec, null, "Consumer", true);

        assertThat(receivedBody.get()).isNotNull();
        assertThat(receivedBody.get()).contains("GROUP_ENGINEERING");
        assertThat(receivedBody.get()).contains("john");
        assertThat(receivedBody.get()).contains("GROUP_INTERNS");
        assertThat(receivedBody.get()).contains("DENIED");
    }

    @Test
    void testDeleteNode() throws Exception {
        AtomicBoolean deleteCalled = new AtomicBoolean(false);

        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1/nodes/node-to-delete", exchange -> {
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                deleteCalled.set(true);
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });

        boolean deleted = client.deleteNode("node-to-delete", false);
        assertThat(deleted).isTrue();
        assertThat(deleteCalled.get()).isTrue();
    }
}
