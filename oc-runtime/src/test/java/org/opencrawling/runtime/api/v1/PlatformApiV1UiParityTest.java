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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI parity: the admin UI is a client of the Platform API. Every REST call it makes is read from its sources and must
 * be a documented {@code /api/v1} operation (method and path), apart from documented exclusions; and every operation
 * the UI used before it moved to v1 must still be used, so nothing the UI could do was lost.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE"})
class PlatformApiV1UiParityTest {

    /**
     * What the UI did through the legacy endpoints before it moved to v1, as the v1 operations that do it. The UI must
     * still call each of them.
     */
    static final Set<String> UI_OPERATIONS = Set.of(
            "listJobs", "createJob", "getJob", "patchJob", "deleteJob", "startJob", "stopJob", "pauseJob", "resumeJob",
            "listConnectors", "createConnector", "replaceConnector", "deleteConnector", "checkConnectorDefinition",
            "getSystemStatus", "getSystemThroughput", "getSystemLogs", "getSystemSettings", "replaceSystemSettings",
            "getTransportSettings", "replaceTransportSettings", "testGrpcTransport",
            "diagnoseJob", "getJobTraces", "getJobErrors", "getMetrics",
            "getVespaHealth", "getVespaDocumentCounts", "queryVespa", "deployVespaBundledSchema", "deployVespaCustomSchema",
            "generateNarrativizationTemplate", "planManifoldCfMigration", "applyManifoldCfMigration",
            "listExecutions", "getExecution", "listExecutionDocuments", "exportExecution", "queryExecutions",
            "getCurrentCaller");

    /** UI calls that deliberately have no v1 operation, and why. */
    static final Map<String, String> EXCLUDED = Map.of(
            "GET /api/auth/config", "public sign-in settings the UI reads before it has a token; not a management operation");

    /**
     * {@code api.get<...>('/path...')}, {@code axios.post(`/api/...`)}, {@code fetchAll<...>('/path')} (a GET that
     * follows the cursors): the client, the method, then the first string argument.
     */
    private static final Pattern CALL = Pattern.compile(
            "\\b(?:(api|axios)\\.(get|post|put|delete|patch)|(fetchAll))(?:<[^\\n]*?>)?\\(\\s*(['`])(/[^'`]*)");

    @LocalServerPort
    int port;

    @Test
    void everyCallOfTheAdminUiIsADocumentedPlatformApiOperation() throws IOException {
        Map<String, String> calls = uiCalls();
        assertThat(calls).as("the scan finds the UI's calls").hasSizeGreaterThan(30);

        JsonNode doc = JsonMapper.builder().build().readTree(new PlatformApiTestSupport.Http(port)
                .send("GET", "/v3/api-docs/v1", null).body());
        Map<String, String> operations = new TreeMap<>(); // "METHOD /api/v1/path/{}" -> operationId
        doc.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
                operations.put(op.getKey().toUpperCase() + " " + path.getKey().replaceAll("\\{[^}]+}", "{}"),
                        op.getValue().get("operationId").asString())));

        Set<String> undocumented = new TreeSet<>();
        Set<String> used = new TreeSet<>();
        calls.forEach((call, file) -> {
            if (EXCLUDED.containsKey(call)) {
                return;
            }
            String operation = operations.get(call);
            if (operation == null) {
                undocumented.add(call + " (" + file + ")");
            } else {
                used.add(operation);
            }
        });
        assertThat(undocumented).as("UI calls that are not documented v1 operations (use /api/v1, or add a documented "
                + "exclusion)").isEmpty();
        assertThat(used).as("everything the UI did before it moved to v1 is still done").containsAll(UI_OPERATIONS);
    }

    /** Every API call in the UI's sources (not its tests), as "METHOD /api/path" with variables as {}. */
    static Map<String, String> uiCalls() throws IOException {
        Path src = Path.of("..", "oc-admin-ui", "src");
        Map<String, String> calls = new TreeMap<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(f -> f.toString().matches(".*\\.tsx?$") && !f.toString().contains(".test.")).toList()) {
                Matcher matcher = CALL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String path = normalize(matcher.group(5));
                    boolean viaClient = !"axios".equals(matcher.group(1));
                    if (viaClient) {
                        path = "/api/v1" + path; // the UI's API client has baseURL /api/v1
                    }
                    String method = matcher.group(3) != null ? "GET" : matcher.group(2).toUpperCase();
                    if (matcher.group(3) != null && path.equals("/api/v1/")) {
                        continue; // fetchAll's own declaration, not a call
                    }
                    calls.put(method + " " + path, src.relativize(file).toString());
                }
            }
        }
        return calls;
    }

    /** {@code /jobs/${seg(id)}:start} becomes {@code /jobs/{}:start}; query strings and optional suffixes are dropped. */
    static String normalize(String raw) {
        String path = raw.replaceAll("\\$\\{(?:seg|encodeURIComponent)?\\(?[A-Za-z0-9_.]+\\)?}", "{}");
        int open = path.indexOf("${");
        if (open >= 0) {
            path = path.substring(0, open);
        }
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    @Test
    void pathsAreNormalizedForTheTable() {
        assertThat(normalize("/jobs/${seg(id)}:start")).isEqualTo("/jobs/{}:start");
        assertThat(normalize("/jobs/${seg(job.id)}")).isEqualTo("/jobs/{}");
        assertThat(normalize("/executions/${encodeURIComponent(executionId)}/export")).isEqualTo("/executions/{}/export");
        assertThat(normalize("/jobs/${jobId}/errors${timeframe ? `?timeframe=")).isEqualTo("/jobs/{}/errors");
        assertThat(normalize("/observability/metrics")).isEqualTo("/observability/metrics");
    }
}
