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
package org.opencrawling.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.OpenApiEndpointSummary;
import org.opencrawling.sdk.models.OpenApiStatusSummary;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiClientTest {

    private static HttpServer server;
    private static int port;
    private static OpenCrawlingClient client;

    private static final String SAMPLE_OPENAPI_SPEC = """
            {
              "openapi": "3.1.0",
              "info": {
                "title": "OpenCrawling Runtime REST API",
                "version": "1.0.0",
                "license": {
                  "name": "Apache License 2.0",
                  "url": "https://www.apache.org/licenses/LICENSE-2.0"
                }
              },
              "tags": [
                { "name": "Pipeline & Jobs", "description": "Job orchestration" },
                { "name": "OIS Direct Ingestion", "description": "Direct OIS push" }
              ],
              "paths": {
                "/api/jobs": {
                  "get": {
                    "tags": ["Pipeline & Jobs"],
                    "operationId": "listJobs",
                    "summary": "List all configured jobs",
                    "responses": {
                      "200": { "description": "Jobs retrieved" }
                    }
                  },
                  "post": {
                    "tags": ["Pipeline & Jobs"],
                    "operationId": "createJob",
                    "summary": "Create a new crawling job",
                    "responses": {
                      "200": { "description": "Job created" }
                    }
                  }
                },
                "/api/v1/ingest/ois/{jobId}": {
                  "post": {
                    "tags": ["OIS Direct Ingestion"],
                    "operationId": "ingestOisDocument",
                    "summary": "Direct Ingestion of OIS Document",
                    "security": [
                      { "OisTokenAuth": [] }
                    ],
                    "responses": {
                      "200": { "description": "Accepted" },
                      "401": { "description": "Unauthorized" }
                    }
                  }
                }
              },
              "components": {
                "securitySchemes": {
                  "OisTokenAuth": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "Token"
                  }
                },
                "schemas": {
                  "JobDTO": {
                    "type": "object",
                    "description": "Configuration and status for a crawling job",
                    "required": ["id", "name"],
                    "properties": {
                      "id": { "type": "string", "description": "Job identifier" },
                      "name": { "type": "string", "description": "Job display name" }
                    }
                  },
                  "ConnectorDTO": {
                    "type": "object",
                    "description": "Connector configuration descriptor",
                    "properties": {
                      "name": { "type": "string" },
                      "type": { "type": "string" }
                    }
                  }
                }
              }
            }
            """;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/v3/api-docs", exchange -> {
            byte[] bytes = SAMPLE_OPENAPI_SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/swagger-ui.html", exchange -> {
            byte[] bytes = "<html><body>Swagger UI</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        client = OpenCrawlingClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testFetchSpecificationAndRaw() {
        JsonNode spec = client.openApi().fetchSpecification();
        assertThat(spec).isNotNull();
        assertThat(spec.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(spec.path("info").path("title").asText()).isEqualTo("OpenCrawling Runtime REST API");

        String raw = client.openApi().fetchRawSpecification();
        assertThat(raw).contains("\"openapi\": \"3.1.0\"");
    }

    @Test
    void testCheckStatus() {
        OpenApiStatusSummary status = client.openApi().checkStatus();
        assertThat(status.title()).isEqualTo("OpenCrawling Runtime REST API");
        assertThat(status.apiVersion()).isEqualTo("1.0.0");
        assertThat(status.openApiVersion()).isEqualTo("3.1.0");
        assertThat(status.licenseName()).isEqualTo("Apache License 2.0");
        assertThat(status.specReachable()).isTrue();
        assertThat(status.swaggerUiReachable()).isTrue();
        assertThat(status.pathCount()).isEqualTo(2);
        assertThat(status.operationCount()).isEqualTo(3);
        assertThat(status.schemaCount()).isEqualTo(2);
        assertThat(status.securitySchemes()).containsExactly("OisTokenAuth (HTTP bearer)");
        assertThat(status.tags()).containsExactly("Pipeline & Jobs", "OIS Direct Ingestion");
    }

    @Test
    void testListEndpointsAndFilters() {
        List<OpenApiEndpointSummary> all = client.openApi().listEndpoints();
        assertThat(all).hasSize(3);

        List<OpenApiEndpointSummary> oisOnly = client.openApi().listEndpoints("OIS Direct Ingestion", null);
        assertThat(oisOnly).hasSize(1);
        assertThat(oisOnly.get(0).path()).isEqualTo("/api/v1/ingest/ois/{jobId}");
        assertThat(oisOnly.get(0).method()).isEqualTo("POST");
        assertThat(oisOnly.get(0).security()).containsExactly("OisTokenAuth");

        List<OpenApiEndpointSummary> getOnly = client.openApi().listEndpoints(null, "GET");
        assertThat(getOnly).hasSize(1);
        assertThat(getOnly.get(0).operationId()).isEqualTo("listJobs");
    }

    @Test
    void testComponentSchemas() {
        List<String> names = client.openApi().listComponentSchemas();
        assertThat(names).containsExactly("ConnectorDTO", "JobDTO");

        Optional<JsonNode> jobSchema = client.openApi().getComponentSchema("JobDTO");
        assertThat(jobSchema).isPresent();
        assertThat(jobSchema.get().path("description").asText()).contains("crawling job");

        Optional<JsonNode> caseInsensitive = client.openApi().getComponentSchema("jobdto");
        assertThat(caseInsensitive).isPresent();

        assertThat(client.openApi().getComponentSchema("NonExistent")).isEmpty();
    }
}
