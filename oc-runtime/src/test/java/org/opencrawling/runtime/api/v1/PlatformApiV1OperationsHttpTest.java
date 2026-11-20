/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.api.v1;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.runtime.api.copilot.TemplateCopilotResponse;
import org.opencrawling.runtime.api.copilot.TemplateGenerationCopilot;
import org.opencrawling.runtime.observability.AIOpsDiagnosticService;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.reporting.JobReportService;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.opencrawling.runtime.service.VespaInsightsService;
import org.opencrawling.runtime.transport.InternalTransportManager;
import org.opencrawling.runtime.transport.TestGrpcResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The operations, settings and tools endpoints over HTTP, each with realistic data behind it. */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE"})
class PlatformApiV1OperationsHttpTest {

    @LocalServerPort
    int port;

    @Autowired AIOpsDiagnosticService diagnostics;
    @Autowired ObservabilityMcpTools observability;
    @Autowired InternalTransportManager transport;
    @Autowired VespaInsightsService vespa;
    @Autowired TemplateGenerationCopilot copilot;
    @Autowired JobReportService reports;

    @TempDir Path tmp;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String tag = "ops-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> jobsToDelete = new ArrayList<>();
    private final List<String> connectorsToDelete = new ArrayList<>();
    private HttpServer manifoldcf;

    @BeforeEach
    void client() {
        http = new PlatformApiTestSupport.Http(port);
    }

    @AfterEach
    void cleanUp() {
        jobsToDelete.forEach(id -> http.send("DELETE", "/api/v1/jobs/" + id, null));
        connectorsToDelete.forEach(name -> http.send("DELETE", "/api/v1/connectors/" + name.replace(" ", "%20") + "?force=true", null));
        if (manifoldcf != null) {
            manifoldcf.stop(0);
        }
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String job() {
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", "{\"name\":\"" + tag + "\",\"repositoryConnector\":\"FileSystem_Local\","
                + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\"}");
        String id = body(created).get("id").asString();
        jobsToDelete.add(id);
        return id;
    }

    @Test
    void aJobsDiagnosisTracesAndErrorsComeFromObservability() {
        String id = job();
        when(diagnostics.diagnoseJob(eq(id), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AIOpsDiagnosticService.DiagnosticReport(id, tag, "2026-11-15T10:00:00Z", "WARNING", "Slow embedding",
                        "Ollama queue", Map.of("Embedding", 4200L), List.of("embedding dominates"), List.of("scale the embedder"), List.of()));
        when(observability.fetchJobTraces(id)).thenReturn(new ObservabilityMcpTools.JobTraceResponse(id, 1, 120, "OK",
                List.of(new TelemetryTraceStore.SpanRecord("s1", "t1", id, "Scanning", "crawler", 0, 120, "OK", null, Map.of())), Map.of("Scanning", 120L)));
        when(observability.getErrorLogs(id, "24h")).thenReturn(new ObservabilityMcpTools.ErrorLogsResponse(id, 1,
                List.of(new TelemetryTraceStore.ErrorLogRecord(id, Instant.parse("2026-11-15T09:00:00Z"), "ERROR", "writer", "timeout", null))));

        JsonNode diagnosis = body(http.send("GET", "/api/v1/jobs/" + id + "/diagnosis", null));
        assertThat(diagnosis.get("status").asString()).isEqualTo("WARNING");
        assertThat(diagnosis.get("recommendedActions").get(0).asString()).isEqualTo("scale the embedder");
        assertThat(body(http.send("GET", "/api/v1/jobs/" + id + "/traces", null)).get("spans").get(0).get("stage").asString()).isEqualTo("Scanning");
        JsonNode errors = body(http.send("GET", "/api/v1/jobs/" + id + "/errors?timeframe=24h", null));
        assertThat(errors.get("errorCount").asInt()).isEqualTo(1);
        assertThat(errors.get("errors").get(0).get("message").asString()).isEqualTo("timeout");

        for (String part : List.of("diagnosis", "traces", "errors")) {
            HttpResponse<String> unknown = http.send("GET", "/api/v1/jobs/none-" + tag + "/" + part, null);
            assertThat(unknown.statusCode()).as(part).isEqualTo(404);
        }
    }

    @Test
    void metricsThroughputAndLogsAnswer() {
        when(observability.queryThroughputMetrics("FileSystem_Local"))
                .thenReturn(new ObservabilityMcpTools.ThroughputMetricsResponse("FileSystem_Local", 12.5, 80, 4, List.of()));
        assertThat(body(http.send("GET", "/api/v1/observability/metrics?connectorId=FileSystem_Local", null))
                .get("averageThroughputDocsPerSec").asDouble()).isEqualTo(12.5);
        HttpResponse<String> throughput = http.send("GET", "/api/v1/system/throughput", null);
        assertThat(throughput.statusCode()).isEqualTo(200);
        assertThat(body(throughput).isArray()).isTrue();
        HttpResponse<String> logs = http.send("GET", "/api/v1/system/logs", null);
        assertThat(logs.statusCode()).isEqualTo(200);
        assertThat(body(logs).isArray()).isTrue();
        assertThat(body(http.send("GET", "/api/v1/system/status", null)).has("system")).isTrue();
    }

    @Test
    void transportSettingsAreReadReplacedAndTested() {
        TransportSettingsDTO settings = TransportSettingsDTO.defaultSettings();
        when(transport.getSettings()).thenReturn(settings);
        when(transport.testGrpcConnection("grpc-host", 9095)).thenReturn(new TestGrpcResponseDTO("SUCCESS", true, true, 4, "ok"));

        HttpResponse<String> got = http.send("GET", "/api/v1/settings/transport", null);
        JsonNode read = body(got);
        assertThat(read.get("mode").asString()).as("the settings document itself, as PUT takes it").isEqualTo(settings.mode());
        assertThat(read.get("grpcPort").asInt()).isEqualTo(settings.grpcPort());
        String etag = got.headers().firstValue("ETag").orElseThrow();
        assertThat(http.send("GET", "/api/v1/settings/transport", null, "If-None-Match", etag).statusCode()).isEqualTo(304);

        HttpResponse<String> stale = http.send("PUT", "/api/v1/settings/transport", json.writeValueAsString(settings),
                "If-Match", "\"stale\"");
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(body(stale).get("currentEtag").asString()).isEqualTo(etag);

        HttpResponse<String> replaced = http.send("PUT", "/api/v1/settings/transport", got.body(), "If-Match", "W/" + etag);
        assertThat(replaced.statusCode()).as("a weak copy of the tag, as a compressing proxy makes it").isEqualTo(200);
        assertThat(replaced.headers().firstValue("ETag")).isPresent();
        assertThat(body(replaced).get("grpcPort").asInt()).isEqualTo(settings.grpcPort());
        verify(transport, atLeastOnce()).updateSettings(any());

        JsonNode tested = body(http.send("POST", "/api/v1/settings/transport:test-grpc", "{\"host\":\"grpc-host\",\"port\":9095}"));
        assertThat(tested.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(tested.get("latencyMs").asLong()).isEqualTo(4);
    }

    @Test
    void vespaIsAdministeredThroughTheIntegrationEndpoints() throws Exception {
        when(vespa.checkHealth("http://vespa:8080")).thenReturn(new VespaInsightsService.VespaHealthResult(true, "UP"));
        when(vespa.getDocumentCounts("http://vespa:8080")).thenReturn(List.of(new VespaInsightsService.DocumentTypeCount("opencrawling", "1024", 42, true)));
        when(vespa.runQuery("http://vespa:8080", "opencrawling", "contracts", "default"))
                .thenReturn(new VespaInsightsService.VespaQueryResult(List.of(), 0, false, "no hits"));
        when(vespa.deployBundledSchema("http://vespa:19071")).thenReturn(new VespaInsightsService.VespaDeployResult(true, "deployed", "{}"));
        when(vespa.deployCustomSchema(eq("http://vespa:19071"), any(), any())).thenReturn(new VespaInsightsService.VespaDeployResult(true, "custom", "{}"));

        assertThat(body(http.send("GET", "/api/v1/integrations/vespa/health?endpoint=http://vespa:8080", null)).get("up").asBoolean()).isTrue();
        assertThat(body(http.send("GET", "/api/v1/integrations/vespa/document-counts?endpoint=http://vespa:8080", null)).get(0).get("count").asLong())
                .isEqualTo(42);
        assertThat(body(http.send("POST", "/api/v1/integrations/vespa:query", "{\"endpoint\":\"http://vespa:8080\",\"documentType\":\"opencrawling\","
                + "\"queryText\":\"contracts\",\"rankProfile\":\"default\"}")).get("message").asString()).isEqualTo("no hits");
        assertThat(body(http.send("POST", "/api/v1/integrations/vespa:deploy-bundled", "{\"configEndpoint\":\"http://vespa:19071\"}"))
                .get("message").asString()).isEqualTo("deployed");
        assertThat(http.send("GET", "/api/v1/integrations/vespa/health", null).statusCode()).as("endpoint is required").isEqualTo(400);

        String boundary = "----oc" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"configEndpoint\"\r\n\r\nhttp://vespa:19071\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"app.zip\"\r\nContent-Type: application/zip\r\n\r\nPK\u0003\u0004\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> custom = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/integrations/vespa:deploy-custom"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.ISO_8859_1)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(custom.statusCode()).as(custom.body()).isEqualTo(200);
        assertThat(body(custom).get("message").asString()).isEqualTo("custom");
    }

    @Test
    void narrativizationSuggestsATemplate() {
        when(copilot.generate(any())).thenReturn(new TemplateCopilotResponse("# {{title}}", Map.of("title", "Contract")));
        JsonNode template = body(http.send("POST", "/api/v1/narrativization:generate-template",
                "{\"connectorType\":\"alfresco\",\"fields\":[{\"name\":\"title\",\"type\":\"string\",\"description\":\"Title\"}]}"));
        assertThat(template.get("template").asString()).isEqualTo("# {{title}}");
    }

    @Test
    void anExecutionsReportFileIsDownloadedAndTheAuditLakeQueried() throws Exception {
        File csv = Files.writeString(tmp.resolve("manifest.csv"), "document_id,status\ndoc-1,SUCCESS\n").toFile();
        when(reports.getExportFile(PlatformApiTestSupport.EXECUTION_ID, "csv")).thenReturn(Optional.of(csv));
        when(reports.isAllowedReportFile(csv)).thenReturn(true);
        try {
            HttpResponse<String> file = http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID + "/export?format=csv", null);
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(file.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/csv"));
            assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(cd -> assertThat(cd).contains("manifest.csv"));
            assertThat(file.body()).contains("doc-1,SUCCESS");
        } finally {
            // The mock is shared by every test on this cached context; leave its default for them.
            when(reports.getExportFile(anyString(), anyString())).thenReturn(Optional.empty());
        }

        when(reports.executeDuckDbQuery("SELECT 1 AS one")).thenReturn(new JobReportService.QueryResult(List.of("one"), List.of(List.of(1))));
        JsonNode result = body(http.send("POST", "/api/v1/executions:query", "{\"sql\":\"SELECT 1 AS one\"}"));
        assertThat(result.get("columns").get(0).asString()).isEqualTo("one");
        when(reports.executeDuckDbQuery("SELECT nope FROM missing")).thenThrow(new java.sql.SQLException("Catalog Error: Table missing does not exist"));
        HttpResponse<String> failing = http.send("POST", "/api/v1/executions:query", "{\"sql\":\"SELECT nope FROM missing\"}");
        assertThat(failing.statusCode()).as("the engine's error is the author's to fix").isEqualTo(400);
        assertThat(failing.body()).contains("#problem-invalid-query", "Table missing does not exist");
        when(reports.executeDuckDbQuery("SELECT boom")).thenThrow(new OutOfMemoryError("Java heap space at /opt/app/x"));
        HttpResponse<String> fault = http.send("POST", "/api/v1/executions:query", "{\"sql\":\"SELECT boom\"}");
        assertThat(fault.statusCode()).as("a server fault is not the caller's query").isEqualTo(500);
        assertThat(fault.body()).contains("#problem-internal-error").doesNotContain("heap", "/opt/app");
        HttpResponse<String> writing = http.send("POST", "/api/v1/executions:query", "{\"sql\":\"DELETE FROM ledger\"}");
        assertThat(writing.statusCode()).isEqualTo(400);
        assertThat(writing.body()).contains("#problem-invalid-query", "read-only");
    }

    @Test
    void aManifoldCfMigrationIsPlannedAndAppliedInProcess() throws Exception {
        Path fixtures = Path.of("..", "oc-mcf-migrator", "src", "test", "resources", "fixtures", "mcf");
        manifoldcf = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String resource : List.of("repositoryconnections", "outputconnections", "transformationconnections", "authorityconnections", "jobs")) {
            byte[] fixture = Files.readAllBytes(fixtures.resolve(resource + ".json"));
            manifoldcf.createContext("/" + resource, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, fixture.length);
                exchange.getResponseBody().write(fixture);
                exchange.close();
            });
        }
        manifoldcf.start();
        String mcf = "{\"mcfUrl\":\"http://127.0.0.1:" + manifoldcf.getAddress().getPort() + "\"}";

        HttpResponse<String> plan = http.send("POST", "/api/v1/migrations/manifoldcf:plan", mcf);
        assertThat(plan.statusCode()).as(plan.body()).isEqualTo(200);
        JsonNode planned = body(plan);
        assertThat(planned.get("summary").get("connectionsTotal").asInt()).isPositive();
        assertThat(planned.get("summary").get("jobsTotal").asInt()).isPositive();

        HttpResponse<String> applied = http.send("POST", "/api/v1/migrations/manifoldcf:apply", mcf);
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        JsonNode outcome = body(applied);
        List<String> migratedConnectors = new ArrayList<>();
        outcome.get("connectionResults").properties().forEach(entry -> {
            assertThat(entry.getValue().get("success").asBoolean()).as(entry.getKey() + ": " + entry.getValue()).isTrue();
            migratedConnectors.add(entry.getKey());
        });
        assertThat(migratedConnectors).isNotEmpty();
        connectorsToDelete.addAll(migratedConnectors);
        for (String connector : migratedConnectors) {
            assertThat(http.send("GET", "/api/v1/connectors/" + connector.replace(" ", "%20"), null).statusCode()).as(connector).isEqualTo(200);
        }
        outcome.get("jobResults").properties().forEach(entry -> {
            assertThat(entry.getValue().get("success").asBoolean()).as(entry.getKey() + ": " + entry.getValue()).isTrue();
            JsonNode found = body(http.send("GET", "/api/v1/jobs?q=" + entry.getKey().replace(" ", "%20"), null));
            assertThat(found.get("page").get("totalItems").asInt()).as(entry.getKey()).isPositive();
            found.get("items").forEach(item -> jobsToDelete.add(item.get("id").asString()));
        });

        assertThat(http.send("POST", "/api/v1/migrations/manifoldcf:plan", "{}").body()).contains("#problem-validation-failed", "mcfUrl");
        HttpResponse<String> down = http.send("POST", "/api/v1/migrations/manifoldcf:plan", "{\"mcfUrl\":\"http://127.0.0.1:9\"}");
        assertThat(down.statusCode()).isEqualTo(502);
        assertThat(down.body()).contains("#problem-upstream-error");
    }
}
