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
package org.opencrawling.runtime.api.v1;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI parity: every REST call the admin UI makes has a Platform API operation. The calls are read from the UI's
 * sources, so a new UI call without a v1 counterpart fails this test until it gets one (or a documented exclusion).
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE"})
class PlatformApiV1UiParityTest {

    /** The admin UI's calls (method and legacy path, variables as {}) and the v1 operation that does the same. */
    static final Map<String, String> UI_TO_V1 = Map.ofEntries(
            entry("GET /api/jobs", "listJobs"),
            entry("POST /api/jobs", "createJob"),
            entry("GET /api/jobs/{}", "getJob"),
            entry("DELETE /api/jobs/{}", "deleteJob"),
            entry("POST /api/jobs/{}/start", "startJob"),
            entry("POST /api/jobs/{}/stop", "stopJob"),
            entry("POST /api/jobs/{}/pause", "pauseJob"),
            entry("GET /api/jobs/permissions", "listJobs"),           // permissions are on each job
            entry("GET /api/connectors/{}", "listConnectors"),        // ?type=
            entry("POST /api/connectors", "createConnector"),
            entry("DELETE /api/connectors/{}", "deleteConnector"),
            entry("POST /api/connectors/check", "checkConnectorDefinition"),
            entry("GET /api/system/status", "getSystemStatus"),
            entry("GET /api/system/throughput", "getSystemThroughput"),
            entry("GET /api/system/logs", "getSystemLogs"),
            entry("GET /api/system/settings", "getSystemSettings"),
            entry("POST /api/system/settings", "replaceSystemSettings"),
            entry("GET /api/v1/admin/settings/transport", "getTransportSettings"),
            entry("PUT /api/v1/admin/settings/transport", "replaceTransportSettings"),
            entry("POST /api/v1/admin/settings/transport/test-grpc", "testGrpcTransport"),
            entry("GET /api/observability/diagnose/{}", "diagnoseJob"),
            entry("GET /api/observability/traces/{}", "getJobTraces"),
            entry("GET /api/observability/errors/{}", "getJobErrors"),
            entry("GET /api/observability/metrics", "getMetrics"),
            entry("GET /api/vespa/health", "getVespaHealth"),
            entry("GET /api/vespa/document-counts", "getVespaDocumentCounts"),
            entry("POST /api/vespa/query", "queryVespa"),
            entry("POST /api/vespa/deploy/bundled", "deployVespaBundledSchema"),
            entry("POST /api/vespa/deploy/custom", "deployVespaCustomSchema"),
            entry("POST /api/transformation/copilot/generate", "generateNarrativizationTemplate"),
            entry("POST /api/mcf-migration/plan", "planManifoldCfMigration"),
            entry("POST /api/mcf-migration/apply", "applyManifoldCfMigration"),
            entry("GET /api/reports", "listExecutions"),
            entry("GET /api/reports/{}/{}", "getExecution"),
            entry("GET /api/reports/{}/{}/documents", "listExecutionDocuments"),
            entry("GET /api/reports/{}/{}/export", "exportExecution"),
            entry("POST /api/reports/query", "queryExecutions"),
            entry("GET /api/auth/me", "getCurrentCaller"));

    /** UI calls that deliberately have no v1 operation, and why. */
    static final Map<String, String> EXCLUDED = Map.of(
            "GET /api/auth/config", "public sign-in settings the UI reads before it has a token; not a management operation");

    /** {@code api.get<...>('/path...')}, {@code axios.post(`/api/...`)}: method, then the first string argument. */
    private static final Pattern CALL = Pattern.compile(
            "\\b(api|axios)\\.(get|post|put|delete|patch)(?:<[^\\n]*?>)?\\(\\s*(['`])(/[^'`]*)");

    @LocalServerPort
    int port;

    @Test
    void everyCallOfTheAdminUiHasAPlatformApiOperation() throws IOException {
        Map<String, String> calls = uiCalls();
        assertThat(calls).as("the scan finds the UI's calls").hasSizeGreaterThan(30);

        Set<String> unmapped = new TreeSet<>(calls.keySet());
        unmapped.removeAll(UI_TO_V1.keySet());
        unmapped.removeAll(EXCLUDED.keySet());
        assertThat(unmapped).as("UI calls without a v1 operation (add one, or a documented exclusion); found in " + calls)
                .isEmpty();

        Set<String> stale = new TreeSet<>(UI_TO_V1.keySet());
        stale.removeAll(calls.keySet());
        assertThat(stale).as("mappings for calls the UI no longer makes").isEmpty();

        JsonNode doc = JsonMapper.builder().build().readTree(new PlatformApiTestSupport.Http(port)
                .send("GET", "/v3/api-docs/v1", null).body());
        Set<String> operationIds = new HashSet<>();
        doc.get("paths").properties().forEach(path -> path.getValue().properties()
                .forEach(op -> operationIds.add(op.getValue().get("operationId").asString())));
        assertThat(operationIds).as("v1 operations named in the parity table").containsAll(UI_TO_V1.values());
    }

    /** Every API call in the UI's sources (not its tests), as "METHOD /api/path" with variables as {}. */
    static Map<String, String> uiCalls() throws IOException {
        Path src = Path.of("..", "oc-admin-ui", "src");
        Map<String, String> calls = new TreeMap<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(f -> f.toString().matches(".*\\.tsx?$") && !f.toString().contains(".test.")).toList()) {
                Matcher matcher = CALL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String path = normalize(matcher.group(4));
                    if (matcher.group(1).equals("api")) {
                        path = "/api" + path; // the UI's API client has baseURL /api
                    }
                    calls.put(matcher.group(2).toUpperCase() + " " + path, src.relativize(file).toString());
                }
            }
        }
        return calls;
    }

    /** {@code /jobs/${id}/start} becomes {@code /jobs/{}/start}; query strings and optional suffixes are dropped. */
    static String normalize(String raw) {
        String path = raw.replaceAll("\\$\\{(?:encodeURIComponent\\()?[A-Za-z0-9_.]+\\)?}", "{}");
        int open = path.indexOf("${");
        if (open >= 0) {
            path = path.substring(0, open);
        }
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    @Test
    void pathsAreNormalizedForTheTable() {
        assertThat(normalize("/jobs/${id}/start")).isEqualTo("/jobs/{}/start");
        assertThat(normalize("/reports/${encodeURIComponent(jobId)}/${encodeURIComponent(executionId)}/export"))
                .isEqualTo("/reports/{}/{}/export");
        assertThat(normalize("/observability/errors/${jobId}${timeframe ? `?timeframe=")).isEqualTo("/observability/errors/{}");
        assertThat(normalize("/observability/metrics")).isEqualTo("/observability/metrics");
    }
}
