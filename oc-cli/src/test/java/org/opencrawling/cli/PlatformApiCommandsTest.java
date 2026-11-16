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
package org.opencrawling.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI's commands on the Platform API: each command sends the v1 requests it should (method, path, headers and
 * body) and prints what comes back, against a stub runtime.
 */
class PlatformApiCommandsTest {

    /** A request the stub received. */
    private record Seen(String request, Map<String, String> headers, String body) {}

    private record Answer(int status, String body, Map<String, String> headers) {}

    private static final String JOB = "{\"id\":\"42\",\"name\":\"HR contracts\",\"repositoryConnector\":\"FileSystem_Local\","
            + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/data/hr\",\"pipelineMode\":\"rag\","
            + "\"state\":{\"status\":\"Ready\",\"stage\":\"Idle\",\"documents\":7,\"lastRun\":null},\"permissions\":[\"READ\"]}";
    private static final String EXECUTION = "{\"jobId\":\"42\",\"executionId\":\"exec-1\",\"jobName\":\"HR contracts\","
            + "\"pipelineMode\":\"RAG\",\"status\":\"SUCCESS\",\"startTime\":\"2026-11-15T10:00:00Z\",\"durationMs\":60000,"
            + "\"totalDocuments\":10,\"successfulDocuments\":9,\"failedDocuments\":1}";

    private HttpServer server;
    private String url;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : "");
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            seen.add(new Seen(key, headers, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Answer answer = answers.getOrDefault(key, new Answer(404,
                    "{\"type\":\"https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-not-found\",\"title\":\"Not found\",\"status\":404,"
                            + "\"detail\":\"Nothing at " + key + "\"}", Map.of()));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", answer.status() >= 400 ? "application/problem+json" : "application/json");
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        server.stop(0);
    }

    private void answer(String request, int status, String body, String... headers) {
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            map.put(headers[i], headers[i + 1]);
        }
        answers.put(request, new Answer(status, body, map));
    }

    private int oc(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--url");
        all.add(url);
        return new CommandLine(new OpenCrawlingCliCommand()).execute(all.toArray(String[]::new));
    }

    /** What the CLI printed, without colours. */
    private String text() {
        return out.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private List<String> requests() {
        return seen.stream().map(Seen::request).toList();
    }

    private static String page(String... items) {
        return "{\"items\":[" + String.join(",", items) + "],\"page\":{\"limit\":50,\"nextCursor\":null,\"totalItems\":" + items.length + "}}";
    }

    @Test
    void jobsAreListedReadStoppedPausedAndDeleted() {
        answer("GET /api/v1/jobs", 200, page(JOB));
        assertEquals(0, oc("job", "list"));
        assertTrue(text().contains("HR contracts"));
        assertTrue(text().contains("Ready"));

        answer("GET /api/v1/jobs/42", 200, JOB, "ETag", "\"v1\"");
        assertEquals(0, oc("job", "status", "--id", "42"));
        assertTrue(text().contains("Processed Documents: 7"));
        assertEquals(1, oc("job", "status", "--id", "nope"));
        assertTrue(text().contains("Job not found: nope"));

        answer("POST /api/v1/jobs/42:stop", 200, JOB);
        answer("POST /api/v1/jobs/42:pause", 200, JOB);
        answer("DELETE /api/v1/jobs/42", 204, "");
        // GET /api/v1/jobs/42 answers with ETag "v1": the delete names that version.
        assertEquals(0, oc("job", "stop", "--id", "42"));
        assertEquals(0, oc("job", "pause", "--id", "42"));
        assertEquals(0, oc("job", "delete", "--id", "42"));
        assertTrue(requests().containsAll(List.of("POST /api/v1/jobs/42:stop", "POST /api/v1/jobs/42:pause", "DELETE /api/v1/jobs/42")));
        assertEquals("\"v1\"", seen.getLast().headers().get("if-match"), "the delete sends the version it read");
    }

    @Test
    void startingAJobCreatesAndStartsItWithIdempotencyKeysAndWaitsForTheRunToEnd() {
        answer("POST /api/v1/jobs", 201, JOB, "Location", "/api/v1/jobs/42");
        answer("POST /api/v1/jobs/42:start", 202, "{\"executionId\":\"exec-1\",\"jobId\":\"42\"}",
                "Location", "/api/v1/job-runs/exec-1");
        answer("GET /api/v1/jobs/42", 200, JOB.replace("\"Ready\"", "\"Finished\"").replace("\"Idle\"", "\"Completed\""));
        // GET /api/v1/job-runs/exec-1 answers 404: the run has ended.

        assertEquals(0, oc("job", "start", "--name", "HR contracts", "--path", "/data/hr", "--mode", "rag", "--wait"));
        Seen create = seen.get(0);
        assertEquals("POST /api/v1/jobs", create.request());
        assertTrue(create.body().contains("\"outputConnector\":\"PGVector_Output\""), create.body());
        assertTrue(create.body().contains("\"pipelineMode\":\"rag\""), create.body());
        assertTrue(create.headers().get("idempotency-key").endsWith("-create"));
        assertTrue(seen.get(1).headers().get("idempotency-key").endsWith("-start"));
        assertTrue(requests().contains("GET /api/v1/job-runs/exec-1"));
        assertTrue(text().contains("Run exec-1 ended: Finished | Stage: Completed"), text());
    }

    @Test
    void aJobFileIsCreatedAsADefinitionWithoutItsOldStateFields() throws IOException {
        Path file = Files.writeString(tmp.resolve("job.json"), "{\"id\":\"old\",\"name\":\"HR\",\"repositoryConnector\":\"FileSystem_Local\","
                + "\"outputConnector\":\"PGVector_Output\",\"authorityConnector\":\"\",\"path\":\"/data\",\"status\":\"Running\","
                + "\"currentStage\":\"Scanning\",\"documents\":3,\"crawlSpec\":{\"maxDepth\":2}}");
        answer("POST /api/v1/jobs", 201, JOB);
        assertEquals(0, oc("job", "create", "--file", file.toString()));
        String body = seen.get(0).body();
        assertFalse(body.contains("status") || body.contains("currentStage") || body.contains("authorityConnector"), body);
        assertTrue(body.contains("\"crawlSpec\":{\"maxDepth\":2}"), body);
    }

    @Test
    void connectorsAreListedCheckedAndUpserted() throws IOException {
        answer("GET /api/v1/connectors?type=output", 200, page("{\"name\":\"PGVector_Output\",\"type\":\"output\",\"className\":\"x.Pg\",\"maxConnections\":10}"));
        assertEquals(0, oc("connector", "list", "--type", "output"));
        assertTrue(text().contains("PGVector_Output"));

        answer("POST /api/v1/connectors/PGVector_Output:check", 200, "{\"success\":true,\"message\":\"Reachable\"}");
        assertEquals(0, oc("connector", "check", "--name", "PGVector_Output"));
        assertTrue(text().contains("Connection Successful: Reachable"));

        answer("GET /api/v1/connectors/HR%20Share", 200, "{\"name\":\"HR Share\",\"type\":\"repository\",\"className\":\"x.Fs\","
                + "\"configuration\":{\"basePath\":\"/data\",\"password\":\"********\"}}");
        answer("POST /api/v1/connectors:check", 200, "{\"success\":false,\"message\":\"No such folder\"}");
        assertEquals(1, oc("connector", "check", "--name", "HR Share", "--config", "basePath=/other"));
        String checked = seen.getLast().body();
        assertTrue(checked.contains("\"basePath\":\"/other\"") && checked.contains("\"password\":\"********\""), checked);

        Path file = Files.writeString(tmp.resolve("connector.json"),
                "{\"name\":\"HR Share\",\"type\":\"repository\",\"className\":\"x.Fs\",\"configuration\":{\"basePath\":\"/data\"}}");
        answer("POST /api/v1/connectors", 409, "{\"type\":\"https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-connector-exists\",\"title\":\"Conflict\",\"status\":409}");
        answer("PUT /api/v1/connectors/HR%20Share", 200, "{\"name\":\"HR Share\"}");
        answer("GET /api/v1/connectors/HR%20Share", 200, "{\"name\":\"HR Share\"}", "ETag", "\"c7\"");
        assertEquals(0, oc("connector", "create", "--file", file.toString()));
        assertTrue(text().contains("Connector updated successfully"));
        assertEquals("\"c7\"", seen.getLast().headers().get("if-match"), "the replace sends the version it read");
    }

    @Test
    void aConnectorJobsUseIsOnlyDeletedWithForce() {
        answer("GET /api/v1/connectors/HR_Share", 200, "{\"name\":\"HR_Share\"}", "ETag", "\"c3\"");
        answer("DELETE /api/v1/connectors/HR_Share", 409, "{\"type\":\"https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-connector-in-use\","
                + "\"title\":\"Conflict\",\"status\":409,\"jobs\":[\"42\"]}");
        assertEquals(1, oc("connector", "delete", "--name", "HR_Share"));
        assertTrue(text().contains("used by jobs [42]") && text().contains("--force"), text());

        answer("DELETE /api/v1/connectors/HR_Share?force=true", 204, "");
        assertEquals(0, oc("connector", "delete", "--name", "HR_Share", "--force"));
    }

    @Test
    void reportsComeFromExecutionsAndTheLedgerPageIsReachedThroughTheCursors() throws IOException {
        answer("GET /api/v1/executions", 200, page(EXECUTION));
        assertEquals(0, oc("report", "list"));
        assertTrue(text().contains("exec-1"));

        answer("GET /api/v1/executions/exec-1", 200, EXECUTION);
        assertEquals(0, oc("report", "get", "42", "exec-1"));
        assertEquals(1, oc("report", "get", "other-job", "exec-1"));
        assertTrue(text().contains("Report not found for Job other-job"));

        answer("GET /api/v1/executions/exec-1/documents?status=FAILED&limit=1", 200,
                "{\"items\":[{\"documentId\":\"d1\",\"status\":\"FAILED\"}],\"page\":{\"limit\":1,\"nextCursor\":\"c2\",\"totalItems\":2}}");
        answer("GET /api/v1/executions/exec-1/documents?status=FAILED&limit=1&cursor=c2", 200,
                "{\"items\":[{\"documentId\":\"d2\",\"status\":\"FAILED\"}],\"page\":{\"limit\":1,\"nextCursor\":null,\"totalItems\":2}}");
        assertEquals(0, oc("report", "ledger", "42", "exec-1", "--status", "failed", "--page", "1", "--size", "1"));
        assertTrue(text().contains("d2") && text().contains("Page 1 | Total: 2"), text());

        answer("GET /api/v1/executions/exec-1/export?format=csv", 200, "document_id,status\nd1,FAILED\n");
        Path target = tmp.resolve("ledger.csv");
        assertEquals(0, oc("report", "export", "42", "exec-1", "--format", "csv", "--out", target.toString()));
        assertTrue(Files.readString(target).contains("d1,FAILED"));

        answer("POST /api/v1/executions:query", 200, "{\"columns\":[\"n\"],\"rows\":[[1]]}");
        assertEquals(0, oc("report", "query", "--sql", "SELECT 1 AS n"));
        assertTrue(seen.getLast().body().contains("SELECT 1 AS n"));
    }

    @Test
    void systemAiopsAndCopilotUseTheirV1Operations() throws IOException {
        answer("GET /api/v1/system/status", 200, "{\"postgres\":\"UP\",\"redis\":\"UP\",\"ollama\":\"UP\",\"system\":\"HEALTHY\"}");
        answer("GET /api/v1/system/logs", 200, "[\"[INFO] up\"]");
        answer("GET /api/v1/system/throughput", 200, "[{\"name\":\"08:00\",\"docs\":3}]");
        answer("GET /api/v1/settings/system", 200, "{\"chunkSize\":800,\"tikaEngine\":\"PipesForkParser\"}", "ETag", "\"s1\"");
        assertEquals(0, oc("system", "status"));
        assertEquals(0, oc("system", "logs"));
        assertEquals(0, oc("system", "throughput"));
        assertEquals(0, oc("system", "settings"));
        assertTrue(text().contains("PipesForkParser"), "every settings field is shown");

        answer("GET /api/v1/jobs/42/diagnosis", 200, "{\"jobId\":\"42\",\"status\":\"WARNING\",\"summary\":\"Slow\",\"recommendedActions\":[\"scale\"]}");
        answer("GET /api/v1/jobs/42/traces", 200, "{\"jobId\":\"42\",\"totalSpans\":0,\"spans\":[]}");
        answer("GET /api/v1/jobs/42/errors?timeframe=1h", 200, "{\"jobId\":\"42\",\"errorCount\":0,\"errors\":[]}");
        answer("GET /api/v1/observability/metrics?connectorId=FileSystem_Local", 200,
                "{\"connectorId\":\"FileSystem_Local\",\"averageThroughputDocsPerSec\":1.5,\"rawMetrics\":[]}");
        assertEquals(0, oc("aiops", "rca", "--job-id", "42"));
        assertEquals(0, oc("aiops", "spans", "--job-id", "42"));
        assertEquals(0, oc("aiops", "errors", "--job-id", "42", "--timeframe", "1h"));
        assertEquals(0, oc("aiops", "metrics", "--connector-id", "FileSystem_Local"));

        Path schema = Files.writeString(tmp.resolve("schema.json"),
                "{\"connectorType\":\"alfresco\",\"fields\":[{\"name\":\"title\",\"type\":\"string\",\"description\":\"Title\"}]}");
        answer("POST /api/v1/narrativization:generate-template", 200, "{\"template\":\"# {{title}}\",\"mockData\":{}}");
        assertEquals(0, oc("copilot", "narrativize", "--schema", schema.toString()));

        List<String> legacy = requests().stream().filter(r -> !r.contains("/api/v1/")).toList();
        assertEquals(List.of(), legacy, "no legacy endpoint is called");
    }
}
