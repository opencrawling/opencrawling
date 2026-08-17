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
package org.opencrawling.migrator.mcf.mcf.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixtures here are hand-encoded per ManifoldCF's actual {@code Configuration#toJSON()} rules
 * (see {@link org.opencrawling.migrator.mcf.mcf.parse.McfJsonNodes}), not simplified —
 * the repository connection and job bodies below use the real "{@code _children_}"/"{@code
 * _type_}" alternate encoding that ManifoldCF emits whenever sibling fields differ in type, which
 * is almost always for anything but a flat parameter list.
 */
class ManifoldCFClientTest {

    private static HttpServer server;
    private static ManifoldCFClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        respond(server, "/repositoryconnections", 200, """
            {"repositoryconnection": [
                {"_children_": [
                    {"_type_": "isnew", "_value_": "false"},
                    {"_type_": "name", "_value_": "SharepointDrive"},
                    {"_type_": "class_name", "_value_": "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector"},
                    {"_type_": "max_connections", "_value_": "10"},
                    {"_type_": "description", "_value_": "Company File Share"},
                    {"_type_": "configuration"}
                ]},
                {"_children_": [
                    {"_type_": "isnew", "_value_": "false"},
                    {"_type_": "name", "_value_": "Alfresco HR Data"},
                    {"_type_": "class_name", "_value_": "org.apache.manifoldcf.crawler.connectors.alfrescowebscript.AlfrescoConnector"},
                    {"_type_": "max_connections", "_value_": "10"},
                    {"_type_": "description", "_value_": "HR Documents"},
                    {"_type_": "configuration", "_children_": [
                        {"_type_": "_PARAMETER_", "_value_": "http", "_attribute_name": "protocol"},
                        {"_type_": "_PARAMETER_", "_value_": "repo2.localhost", "_attribute_name": "hostname"},
                        {"_type_": "_PARAMETER_", "_value_": "sekrit123", "_attribute_name": "password"}
                    ]},
                    {"_type_": "acl_authority", "_value_": "AD Authority"},
                    {"_type_": "throttle", "_children_": [
                        {"_type_": "match", "_value_": "*"},
                        {"_type_": "match_description", "_value_": "global"},
                        {"_type_": "rate", "_value_": "10.0"}
                    ]}
                ]}
            ]}
            """);

        respond(server, "/jobs", 200, """
            {"job": {"_children_": [
                {"_type_": "id", "_value_": "1755000000000"},
                {"_type_": "description", "_value_": "SharePoint drive to Vespa"},
                {"_type_": "repository_connection", "_value_": "SharepointDrive"},
                {"_type_": "document_specification", "startpoint": {"_attribute_path": "/mnt/drive-a/files"}},
                {"_type_": "pipelinestage", "_children_": [
                    {"_type_": "stage_id", "_value_": "0"},
                    {"_type_": "stage_isoutput", "_value_": "true"},
                    {"_type_": "stage_connectionname", "_value_": "Vespa Federated Index"}
                ]},
                {"_type_": "start_mode", "_value_": "windowbegin"},
                {"_type_": "run_mode", "_value_": "scan once"}
            ]}}
            """);

        server.start();
        client = new ManifoldCFClient("http://localhost:" + port, null, null, 5);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listRepositoryConnections_parsesBothEncodings() {
        List<McfConnection> connections = client.listRepositoryConnections();
        assertThat(connections).hasSize(2);

        McfConnection sharepoint = connections.get(0);
        assertThat(sharepoint.name()).isEqualTo("SharepointDrive");
        assertThat(sharepoint.className()).isEqualTo("org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector");
        assertThat(sharepoint.configuration()).isEmpty();
        assertThat(sharepoint.aclAuthority()).isNull();
        assertThat(sharepoint.throttleMatches()).isEmpty();

        McfConnection alfresco = connections.get(1);
        assertThat(alfresco.name()).isEqualTo("Alfresco HR Data");
        assertThat(alfresco.configuration()).containsEntry("protocol", "http").containsEntry("hostname", "repo2.localhost")
            .containsEntry("password", "sekrit123");
        assertThat(alfresco.aclAuthority()).isEqualTo("AD Authority");
        assertThat(alfresco.throttleMatches()).containsExactly("*");
    }

    @Test
    void listJobs_parsesPipelineStagesAndDocumentSpecification() {
        List<McfJob> jobs = client.listJobs();
        assertThat(jobs).hasSize(1);

        McfJob job = jobs.get(0);
        assertThat(job.id()).isEqualTo("1755000000000");
        assertThat(job.description()).isEqualTo("SharePoint drive to Vespa");
        assertThat(job.repositoryConnectionName()).isEqualTo("SharepointDrive");
        assertThat(job.pipelineStages()).hasSize(1);
        assertThat(job.outputStages()).hasSize(1);
        assertThat(job.outputStages().get(0).connectionName()).isEqualTo("Vespa Federated Index");
        assertThat(job.transformationStages()).isEmpty();
        assertThat(job.documentSpecification()).isNotNull();
    }

    @Test
    void apiErrorNode_throwsWithMessage() throws IOException {
        withDedicatedServer("/repositoryconnections", 200, """
            {"error": "Connection 'Nonexistent' does not exist"}
            """, dedicatedClient ->
            assertThatThrownBy(dedicatedClient::listRepositoryConnections)
                .isInstanceOf(ManifoldCFApiException.class)
                .hasMessageContaining("Connection 'Nonexistent' does not exist"));
    }

    @Test
    void nonSuccessHttpStatus_throws() throws IOException {
        withDedicatedServer("/repositoryconnections", 500, "boom", dedicatedClient ->
            assertThatThrownBy(dedicatedClient::listRepositoryConnections)
                .isInstanceOf(ManifoldCFApiException.class)
                .hasMessageContaining("500"));
    }

    @Test
    void malformedJson_throws() throws IOException {
        withDedicatedServer("/repositoryconnections", 200, "<html>nope</html>", dedicatedClient ->
            assertThatThrownBy(dedicatedClient::listRepositoryConnections)
                .isInstanceOf(ManifoldCFApiException.class));
    }

    @Test
    void basicAuthHeader_sentWhenCredentialsProvided() throws IOException {
        HttpServer authServer = HttpServer.create(new InetSocketAddress(0), 0);
        int authPort = authServer.getAddress().getPort();
        Map<String, String> capturedHeaders = new ConcurrentHashMap<>();
        authServer.createContext("/repositoryconnections", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                capturedHeaders.put("Authorization", auth);
            }
            sendStatus(exchange, 200, "{}");
        });
        authServer.start();
        try {
            ManifoldCFClient authedClient = new ManifoldCFClient("http://localhost:" + authPort, "admin", "admin", 5);
            authedClient.listRepositoryConnections();
            assertThat(capturedHeaders).containsEntry("Authorization",
                "Basic " + java.util.Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8)));
        } finally {
            authServer.stop(0);
        }
    }

    private interface ClientAssertion {
        void run(ManifoldCFClient client);
    }

    private static void withDedicatedServer(String path, int status, String body, ClientAssertion assertion) throws IOException {
        HttpServer dedicated = HttpServer.create(new InetSocketAddress(0), 0);
        int dedicatedPort = dedicated.getAddress().getPort();
        respond(dedicated, path, status, body);
        dedicated.start();
        try {
            assertion.run(new ManifoldCFClient("http://localhost:" + dedicatedPort, null, null, 5));
        } finally {
            dedicated.stop(0);
        }
    }

    private static void respond(HttpServer targetServer, String path, int status, String body) {
        targetServer.createContext(path, exchange -> sendStatus(exchange, status, body));
    }

    private static void sendStatus(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
