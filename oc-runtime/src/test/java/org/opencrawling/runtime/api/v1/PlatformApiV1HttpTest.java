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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Platform API over HTTP, with management authorization off (every caller may do everything). */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE"})
class PlatformApiV1HttpTest {

    @LocalServerPort
    int port;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String tag = "v1-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> createdJobs = new ArrayList<>();
    private final List<String> createdConnectors = new ArrayList<>();

    @BeforeEach
    void client() {
        http = new PlatformApiTestSupport.Http(port);
    }

    @AfterEach
    void cleanUp() {
        createdJobs.forEach(id -> http.send("DELETE", "/api/v1/jobs/" + id, null));
        createdConnectors.forEach(name -> http.send("DELETE", "/api/v1/connectors/" + name + "?force=true", null));
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String jobJson(String name) {
        return "{\"name\":\"" + name + "\",\"repositoryConnector\":\"FileSystem_Local\",\"outputConnector\":\"PGVector_Output\","
                + "\"path\":\"/tmp/" + name + "\",\"pipelineMode\":\"rag\"}";
    }

    private JsonNode createJob(String name) {
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", jobJson(name));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode job = body(created);
        createdJobs.add(job.get("id").asString());
        return job;
    }

    private static void assertProblem(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"type\":\"" + ApiV1.PROBLEM_TYPE_BASE + type + "\"")
                .contains("\"status\":" + status).contains("\"requestId\"");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void theIndexLinksEveryCollectionAndTheVersionAndHealthAnswer() {
        HttpResponse<String> index = http.send("GET", "/api/v1", null);
        assertThat(index.statusCode()).isEqualTo(200);
        JsonNode links = body(index).get("links");
        assertThat(links.get("jobs").asString()).isEqualTo("/api/v1/jobs");
        assertThat(links.get("executions").asString()).isEqualTo("/api/v1/executions");
        assertThat(body(index).get("documentation").get("openapi").asString()).isEqualTo("/v3/api-docs/v1");
        assertThat(body(index).get("features").get("dynamicJobs").asBoolean()).isFalse();

        assertThat(http.send("GET", "/api/v1/", null).statusCode()).isEqualTo(200);
        assertThat(body(http.send("GET", "/api/v1/version", null)).get("apiVersion").asString()).isEqualTo(ApiV1.VERSION);
        HttpResponse<String> health = http.send("GET", "/api/v1/health", null);
        assertThat(health.statusCode()).isIn(200, 503);
        assertThat(body(health).has("status")).isTrue();
        assertThat(body(health).get("components").has("system")).isFalse();
    }

    @Test
    void aJobIsCreatedReadAndConditionallyRead() {
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", jobJson(tag));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode job = body(created);
        String id = job.get("id").asString();
        createdJobs.add(id);
        assertThat(created.headers().firstValue("Location")).hasValue("/api/v1/jobs/" + id);
        String etag = created.headers().firstValue("ETag").orElseThrow();
        assertThat(job.get("state").get("status").asString()).isEqualTo("Ready");
        assertThat(job.get("state").get("lastRun").isNull()).isTrue();
        assertThat(job.get("permissions").toString()).contains("READ", "EXECUTE", "MANAGE");

        HttpResponse<String> read = http.send("GET", "/api/v1/jobs/" + id, null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("ETag")).hasValue(etag);
        HttpResponse<String> notModified = http.send("GET", "/api/v1/jobs/" + id, null, "If-None-Match", etag);
        assertThat(notModified.statusCode()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
    }

    @Test
    void invalidJobsAreRefusedWithFieldErrors() {
        HttpResponse<String> missing = http.send("POST", "/api/v1/jobs", "{\"repositoryConnector\":\"FileSystem_Local\"}");
        assertProblem(missing, 400, "validation-failed");
        assertThat(missing.body()).contains("\"field\":\"name\"", "\"field\":\"outputConnector\"", "\"field\":\"path\"");

        HttpResponse<String> unknown = http.send("POST", "/api/v1/jobs", "{\"name\":\"x\",\"repositoryConnector\":\"Nope_Here\","
                + "\"outputConnector\":\"FileSystem_Local\",\"path\":\"/tmp\"}");
        assertProblem(unknown, 400, "validation-failed");
        assertThat(unknown.body()).contains("no connector named 'Nope_Here'").contains("'FileSystem_Local' is a repository connector, not output");

        HttpResponse<String> mode = http.send("POST", "/api/v1/jobs", jobJson(tag).replace("\"rag\"", "\"warp-speed\""));
        assertProblem(mode, 400, "validation-failed");
        assertThat(mode.body()).contains("\"field\":\"pipelineMode\"");
    }

    @Test
    void jobsAreFilteredSortedAndPagedWithACursor() {
        for (String suffix : List.of("c", "a", "b")) {
            createJob(tag + "-" + suffix);
        }
        HttpResponse<String> first = http.send("GET", "/api/v1/jobs?q=" + tag + "&sort=-name&limit=2", null);
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode page = body(first);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("name").asString()).isEqualTo(tag + "-c");
        assertThat(page.get("page").get("totalItems").asInt()).isEqualTo(3);
        String cursor = page.get("page").get("nextCursor").asString();
        assertThat(first.headers().firstValue("Link")).hasValueSatisfying(link ->
                assertThat(link).contains("cursor=" + cursor).endsWith("rel=\"next\""));

        JsonNode second = body(http.send("GET", "/api/v1/jobs?q=" + tag + "&sort=-name&limit=2&cursor=" + cursor, null));
        assertThat(second.get("items").get(0).get("name").asString()).isEqualTo(tag + "-a");
        assertThat(second.get("page").get("nextCursor").isNull()).isTrue();

        assertProblem(http.send("GET", "/api/v1/jobs?q=other&sort=-name&limit=2&cursor=" + cursor, null), 400, "validation-failed");
        assertProblem(http.send("GET", "/api/v1/jobs?cursor=not-a-cursor", null), 400, "validation-failed");
        assertProblem(http.send("GET", "/api/v1/jobs?sort=colour", null), 400, "validation-failed");
        assertProblem(http.send("GET", "/api/v1/jobs?limit=0", null), 400, "validation-failed");
    }

    @Test
    void replacingAndPatchingRespectIfMatch() {
        JsonNode job = createJob(tag);
        String id = job.get("id").asString();
        String etag = http.send("GET", "/api/v1/jobs/" + id, null).headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> replaced = http.send("PUT", "/api/v1/jobs/" + id, jobJson(tag + "-renamed"), "If-Match", etag);
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        assertThat(body(replaced).get("name").asString()).isEqualTo(tag + "-renamed");
        String newEtag = replaced.headers().firstValue("ETag").orElseThrow();
        assertThat(newEtag).isNotEqualTo(etag);

        HttpResponse<String> stale = http.send("PUT", "/api/v1/jobs/" + id, jobJson(tag), "If-Match", etag);
        assertProblem(stale, 412, "precondition-failed");
        assertThat(stale.body()).contains("\"currentEtag\"");

        HttpResponse<String> patched = http.send("PATCH", "/api/v1/jobs/" + id, "{\"name\":\"" + tag + "-patched\",\"pipelineMode\":null}",
                "Content-Type", "application/merge-patch+json", "If-Match", newEtag);
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(body(patched).get("name").asString()).isEqualTo(tag + "-patched");
        assertThat(body(patched).get("path").asString()).as("untouched by the patch").isEqualTo("/tmp/" + tag + "-renamed");
        assertThat(body(patched).get("pipelineMode").isNull()).as("null removes the override").isTrue();

        HttpResponse<String> badPatch = http.send("PATCH", "/api/v1/jobs/" + id, "{\"name\":null}",
                "Content-Type", "application/merge-patch+json");
        assertProblem(badPatch, 400, "validation-failed");
        assertThat(badPatch.body()).contains("\"field\":\"name\"");
    }

    @Test
    void aDeletedJobIsGone() {
        String id = createJob(tag).get("id").asString();
        HttpResponse<String> deleted = http.send("DELETE", "/api/v1/jobs/" + id, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        createdJobs.remove(id);
        HttpResponse<String> gone = http.send("GET", "/api/v1/jobs/" + id, null);
        assertProblem(gone, 404, "not-found");
        assertThat(gone.body()).contains("\"instance\":\"/api/v1/jobs/" + id + "\"");
    }

    @Test
    void startingAJobAnswersWithItsRunAndAnIdempotencyKeyPreventsASecondRun() {
        String id = createJob(tag).get("id").asString();
        String key = "start-" + tag;
        HttpResponse<String> started = http.send("POST", "/api/v1/jobs/" + id + ":start", null, "Idempotency-Key", key,
                "X-Correlation-Id", "corr-1");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String executionId = body(started).get("executionId").asString();
        assertThat(executionId).startsWith("exec-");
        assertThat(started.headers().firstValue("Location")).hasValue("/api/v1/job-runs/" + executionId);

        HttpResponse<String> retried = http.send("POST", "/api/v1/jobs/" + id + ":start", null, "Idempotency-Key", key);
        assertThat(retried.statusCode()).isEqualTo(202);
        assertThat(body(retried).get("executionId").asString()).isEqualTo(executionId);
        assertThat(retried.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(retried.headers().firstValue("Location")).hasValue("/api/v1/job-runs/" + executionId);

        HttpResponse<String> stopped = http.send("POST", "/api/v1/jobs/" + id + ":stop", null);
        assertThat(stopped.statusCode()).isIn(200, 202);
        assertThat(body(stopped).get("id").asString()).isEqualTo(id);

        assertProblem(http.send("POST", "/api/v1/jobs/" + id + ":start", null, "Idempotency-Key", "bad key!"), 400, "validation-failed");
        assertProblem(http.send("POST", "/api/v1/jobs/missing-" + tag + ":start", null), 404, "not-found");
    }

    @Test
    void anIdempotencyKeyCannotBeReusedForADifferentRequest() {
        String key = "create-" + tag;
        HttpResponse<String> first = http.send("POST", "/api/v1/jobs", jobJson(tag), "Idempotency-Key", key);
        assertThat(first.statusCode()).isEqualTo(201);
        createdJobs.add(body(first).get("id").asString());
        HttpResponse<String> replay = http.send("POST", "/api/v1/jobs", jobJson(tag), "Idempotency-Key", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(body(replay).get("id").asString()).isEqualTo(body(first).get("id").asString());
        assertThat(http.send("GET", "/api/v1/jobs?q=" + tag, null).body()).contains("\"totalItems\":1");

        assertProblem(http.send("POST", "/api/v1/jobs", jobJson(tag + "-other"), "Idempotency-Key", key), 422, "idempotency-key-reused");
    }

    @Test
    void pausingMarksTheJobAndRunsInProgressAreListed() {
        String id = createJob(tag).get("id").asString();
        HttpResponse<String> paused = http.send("POST", "/api/v1/jobs/" + id + ":pause", null);
        assertThat(paused.statusCode()).isEqualTo(200);
        assertThat(body(paused).get("state").get("status").asString()).isEqualTo("Paused");

        assertThat(http.send("GET", "/api/v1/job-runs", null).statusCode()).isEqualTo(200);
        HttpResponse<String> noRun = http.send("GET", "/api/v1/job-runs/exec-nothing", null);
        assertProblem(noRun, 404, "not-found");
        assertThat(noRun.body()).contains("\"execution\":\"/api/v1/executions/exec-nothing\"");
    }

    @Test
    void executionsAreListedNewestFirstAndTheirLedgerIsPaged() {
        JsonNode list = body(http.send("GET", "/api/v1/executions", null));
        assertThat(list.get("items").get(0).get("executionId").asString()).isEqualTo(PlatformApiTestSupport.EXECUTION_ID);
        assertThat(body(http.send("GET", "/api/v1/executions?status=FAILED", null)).get("page").get("totalItems").asInt()).isEqualTo(1);
        assertThat(body(http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID, null)).get("jobId").asString())
                .isEqualTo("job-a");
        assertProblem(http.send("GET", "/api/v1/executions/exec-unknown", null), 404, "not-found");

        HttpResponse<String> ledger = http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID + "/documents?limit=50", null);
        assertThat(ledger.statusCode()).as(ledger.body()).isEqualTo(200);
        assertThat(body(ledger).get("page").get("totalItems").asInt()).isEqualTo(120);
        String next = body(ledger).get("page").get("nextCursor").asString();
        HttpResponse<String> third = http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID
                + "/documents?limit=50&cursor=" + body(http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID
                + "/documents?limit=50&cursor=" + next, null)).get("page").get("nextCursor").asString(), null);
        assertThat(body(third).get("page").get("nextCursor").isNull()).isTrue();

        assertProblem(http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID + "/export?format=csv", null),
                404, "export-not-available");
        assertProblem(http.send("GET", "/api/v1/executions/" + PlatformApiTestSupport.EXECUTION_ID + "/export?format=exe", null),
                400, "validation-failed");
        assertProblem(http.send("POST", "/api/v1/executions:query", "{\"sql\":\"\"}"), 400, "validation-failed");
    }

    @Test
    void connectorsAreManagedWithTheirSecretsMasked() {
        String name = "Test_" + tag.replace("-", "_");
        String connector = "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\","
                + "\"configuration\":{\"url\":\"http://repo\",\"password\":\"s3cret\",\"apiKey\":\"k-123\",\"idpTokenUrl\":\"http://idp\"}}";
        HttpResponse<String> created = http.send("POST", "/api/v1/connectors", connector);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        createdConnectors.add(name);
        assertThat(created.body()).contains("\"password\":\"********\"", "\"apiKey\":\"********\"", "\"idpTokenUrl\":\"http://idp\"")
                .doesNotContain("s3cret", "k-123");
        assertProblem(http.send("POST", "/api/v1/connectors", connector), 409, "connector-exists");

        HttpResponse<String> read = http.send("GET", "/api/v1/connectors/" + name, null);
        String masked = read.body();
        assertThat(masked).doesNotContain("s3cret");
        String etag = read.headers().firstValue("ETag").orElseThrow();

        // Sending back what was read keeps the secrets; the legacy API (admins) shows the stored values.
        String masks = connector.replace("s3cret", "********").replace("k-123", "********");
        HttpResponse<String> replaced = http.send("PUT", "/api/v1/connectors/" + name,
                masks.replace("\"idpTokenUrl\":\"http://idp\"", "\"idpTokenUrl\":\"http://idp\",\"timeoutSeconds\":\"30\""), "If-Match", etag);
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        String stored = http.send("GET", "/api/connectors/repository", null).body();
        assertThat(stored).contains("\"password\":\"s3cret\"", "\"apiKey\":\"k-123\"", "\"timeoutSeconds\":\"30\"");

        // Pointing the connector elsewhere while keeping the stored secrets would send them there: refused.
        HttpResponse<String> moved = http.send("PUT", "/api/v1/connectors/" + name, masks.replace("http://repo", "http://elsewhere"));
        assertProblem(moved, 400, "validation-failed");
        assertThat(moved.body()).contains("configuration.password", "configuration.apiKey", "address changed (url)");
        assertThat(http.send("GET", "/api/connectors/repository", null).body()).as("nothing changed").contains("http://repo\"")
                .doesNotContain("elsewhere");
        HttpResponse<String> reentered = http.send("PUT", "/api/v1/connectors/" + name,
                connector.replace("http://repo", "http://repo2").replace("s3cret", "n3w"));
        assertThat(reentered.statusCode()).as("with the secrets entered again").isEqualTo(200);
        String rotatedEtag = reentered.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> rotated = http.send("PUT", "/api/v1/connectors/" + name,
                connector.replace("http://repo", "http://repo2").replace("s3cret", "n3wer"));
        assertThat(rotated.body()).as("the view is the same").isEqualTo(reentered.body());
        assertThat(rotated.headers().firstValue("ETag").orElseThrow()).as("but the secret changed, so the version did")
                .isNotEqualTo(rotatedEtag);
        assertProblem(http.send("PUT", "/api/v1/connectors/" + name, masks.replace("http://repo", "http://repo2"), "If-Match", rotatedEtag),
                412, "precondition-failed");

        // Secrets the key endings alone would miss, and credentials inside a URL.
        String aws = "{\"name\":\"" + name + "_s3\",\"type\":\"output\",\"className\":\"org.example.S3\",\"configuration\":"
                + "{\"s3SecretAccessKey\":\"AKIA-secret\",\"s3Endpoint\":\"https://minio:9000\","
                + "\"url\":\"jdbc:postgresql://admin:dbpass@db:5432/x\",\"customHeaders\":\"Authorization: Bearer abc\"}}";
        HttpResponse<String> awsCreated = http.send("POST", "/api/v1/connectors", aws);
        assertThat(awsCreated.statusCode()).as(awsCreated.body()).isEqualTo(201);
        createdConnectors.add(name + "_s3");
        assertThat(awsCreated.body()).doesNotContain("AKIA-secret", "dbpass", "Bearer abc")
                .contains("\"s3SecretAccessKey\":\"********\"", "jdbc:postgresql://admin:********@db:5432/x");
        HttpResponse<String> awsRead = http.send("GET", "/api/v1/connectors/" + name + "_s3", null);
        HttpResponse<String> awsBack = http.send("PUT", "/api/v1/connectors/" + name + "_s3", awsRead.body(),
                "If-Match", awsRead.headers().firstValue("ETag").orElseThrow());
        assertThat(awsBack.statusCode()).as("what was read can be sent back: " + awsBack.body()).isEqualTo(200);
        assertThat(http.send("GET", "/api/connectors/output", null).body())
                .contains("AKIA-secret", "jdbc:postgresql://admin:dbpass@db:5432/x", "Bearer abc");

        assertProblem(http.send("PUT", "/api/v1/connectors/" + name, connector.replace(name, name + "x")), 400, "validation-failed");
        assertProblem(http.send("POST", "/api/v1/connectors", connector.replace(name, name + "_new")
                .replace("s3cret", "********")), 400, "validation-failed");

        assertThat(body(http.send("GET", "/api/v1/connectors?type=output&limit=500", null)).get("items").toString())
                .contains("PGVector_Output").doesNotContain("FileSystem_Local");
        assertThat(body(http.send("POST", "/api/v1/connectors/" + name + ":check", null)).get("success").asBoolean()).isTrue();
        assertThat(body(http.send("POST", "/api/v1/connectors:check", connector)).get("message").asString()).isEqualTo("Reachable");
    }

    @Test
    void aConnectorInUseIsOnlyDeletedWithForce() {
        String name = "Test_" + tag.replace("-", "_");
        http.send("POST", "/api/v1/connectors", "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\"}");
        createdConnectors.add(name);
        String id = createJob(tag).get("id").asString();
        http.send("PATCH", "/api/v1/jobs/" + id, "{\"repositoryConnector\":\"" + name + "\"}", "Content-Type", "application/merge-patch+json");

        HttpResponse<String> refused = http.send("DELETE", "/api/v1/connectors/" + name, null);
        assertProblem(refused, 409, "connector-in-use");
        assertThat(refused.body()).contains("\"jobs\":[\"" + id + "\"]");
        assertThat(http.send("DELETE", "/api/v1/connectors/" + name + "?force=true", null).statusCode()).isEqualTo(204);
        createdConnectors.remove(name);
    }

    @Test
    void settingsHaveETagsAndRefuseStaleChanges() {
        HttpResponse<String> read = http.send("GET", "/api/v1/settings/system", null);
        assertThat(read.statusCode()).isEqualTo(200);
        String etag = read.headers().firstValue("ETag").orElseThrow();
        assertThat(http.send("GET", "/api/v1/settings/system", null, "If-None-Match", etag).statusCode()).isEqualTo(304);
        assertProblem(http.send("PUT", "/api/v1/settings/system", read.body(), "If-Match", "\"stale\""), 412, "precondition-failed");
        HttpResponse<String> saved = http.send("PUT", "/api/v1/settings/system", read.body(), "If-Match", etag);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    }

    @Test
    void errorsBeforeAndOutsideControllersAreProblemsToo() {
        assertProblem(http.send("GET", "/api/v1/nothing-here", null), 404, "no-such-endpoint");
        assertProblem(http.send("GET", "/api/v1/dynamic-jobs", null), 404, "no-such-endpoint"); // feature off
        HttpResponse<String> method = http.send("DELETE", "/api/v1/jobs", null);
        assertProblem(method, 405, "method-not-allowed");
        assertThat(method.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("GET", "POST"));
        assertProblem(http.send("POST", "/api/v1/jobs", "{not json"), 400, "malformed-request");
        assertProblem(http.send("POST", "/api/v1/jobs", "name=x", "Content-Type", "text/plain"), 415, "unsupported-media-type");

        HttpResponse<String> requestId = http.send("GET", "/api/v1/jobs/none-" + tag, null, "X-Request-Id", "my-trace-42");
        assertThat(requestId.headers().firstValue("X-Request-Id")).hasValue("my-trace-42");
        assertThat(requestId.body()).contains("\"requestId\":\"my-trace-42\"");
    }

    @Test
    void theLegacyEndpointsAnswerAsBefore() {
        HttpResponse<String> legacy = http.send("GET", "/api/jobs/none-" + tag, null);
        assertThat(legacy.statusCode()).isEqualTo(404);
        assertThat(legacy.headers().firstValue("Content-Type").orElse("")).doesNotStartWith("application/problem+json");
        assertThat(legacy.headers().firstValue("X-Request-Id")).isEmpty();
        assertThat(http.send("POST", "/api/v1/ingest/ois/job-1", "{}").headers().firstValue("X-Request-Id")).isEmpty();
        // An error raised before a legacy controller runs keeps Spring's default answer, not a problem detail.
        HttpResponse<String> malformed = http.send("POST", "/api/jobs", "{not json");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.headers().firstValue("Content-Type").orElse("")).doesNotStartWith("application/problem+json");
        assertThat(malformed.body()).doesNotContain(ApiV1.PROBLEM_TYPE_BASE);
    }

    @Test
    void theOpenApiDocumentDescribesThePlatformApiOnly() {
        HttpResponse<String> response = http.send("GET", "/v3/api-docs/v1", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode doc = body(response);
        assertThat(doc.get("info").get("title").asString()).isEqualTo("OpenCrawling Platform API");
        assertThat(doc.get("components").get("schemas").has("Problem")).isTrue();
        List<String> operationIds = new ArrayList<>();
        doc.get("paths").properties().forEach(path -> {
            assertThat(path.getKey()).startsWith("/api/v1").doesNotStartWith("/api/v1/ingest").doesNotStartWith("/api/v1/admin");
            path.getValue().properties().forEach(operation -> {
                assertThat(operation.getValue().has("operationId")).as(path.getKey()).isTrue();
                operationIds.add(operation.getValue().get("operationId").asString());
                assertThat(operation.getValue().get("responses").has("500")).as(path.getKey()).isTrue();
            });
        });
        assertThat(operationIds).doesNotHaveDuplicates();
        assertThat(PlatformApiTestSupport.operations(doc)).as("every operation at its documented method and path, and no others")
                .containsExactlyInAnyOrderEntriesOf(PlatformApiTestSupport.CORE_OPERATIONS);

        assertThat(http.send("GET", "/v3/api-docs/all", null).body()).contains("\"/api/jobs\"", "\"/api/v1/jobs\"");
    }

    @Test
    void connectorNamesMayHaveInnerSpacesAsMigrationsCreate() {
        String name = "Test Space " + tag;
        HttpResponse<String> created = http.send("POST", "/api/v1/connectors",
                "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        createdConnectors.add(name.replace(" ", "%20"));
        HttpResponse<String> replaced = http.send("PUT", "/api/v1/connectors/" + name.replace(" ", "%20"),
                "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo2\"}");
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        assertProblem(http.send("POST", "/api/v1/connectors", "{\"name\":\" leading\",\"type\":\"repository\",\"className\":\"x\"}"),
                400, "validation-failed");
        assertProblem(http.send("POST", "/api/v1/connectors", "{\"name\":\"a:b\",\"type\":\"repository\",\"className\":\"x\"}"),
                400, "validation-failed");
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.opencrawling.runtime.orchestrator.JobOrchestrator orchestrator;

    @Test
    void aRunInProgressIsReadAndStoppedThroughItsOwnResource() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.BooleanSupplier stop = invocation.getArgument(9);
            for (int i = 0; i < 200 && !stop.getAsBoolean() && release.getCount() > 0; i++) {
                Thread.sleep(25);
            }
            return true;
        }).when(orchestrator).runJob(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            String id = createJob(tag).get("id").asString();
            String executionId = body(http.send("POST", "/api/v1/jobs/" + id + ":start", null)).get("executionId").asString();

            HttpResponse<String> run = http.send("GET", "/api/v1/job-runs/" + executionId, null);
            assertThat(run.statusCode()).as(run.body()).isEqualTo(200);
            assertThat(body(run).get("jobId").asString()).isEqualTo(id);
            assertThat(body(http.send("GET", "/api/v1/job-runs?jobId=" + id, null)).get("page").get("totalItems").asInt()).isEqualTo(1);
            assertProblem(http.send("POST", "/api/v1/jobs/" + id + ":start", null), 409, "job-already-running");

            HttpResponse<String> stopped = http.send("POST", "/api/v1/job-runs/" + executionId + ":stop", null);
            assertThat(stopped.statusCode()).isEqualTo(202);
            assertThat(body(stopped).get("stopRequested").asBoolean()).isTrue();
            for (int i = 0; i < 100 && http.send("GET", "/api/v1/job-runs/" + executionId, null).statusCode() == 200; i++) {
                Thread.sleep(50);
            }
            assertThat(http.send("GET", "/api/v1/job-runs/" + executionId, null).statusCode()).as("the run ended").isEqualTo(404);
            assertThat(body(http.send("GET", "/api/v1/jobs/" + id, null)).get("state").get("stage").asString()).isEqualTo("Stopped");
        } finally {
            release.countDown();
            org.mockito.Mockito.reset(orchestrator);
        }
    }

    @Test
    void aJobsExecutionsAreListedForAJobTheCallerMaySee() {
        String id = createJob(tag).get("id").asString();
        HttpResponse<String> executions = http.send("GET", "/api/v1/jobs/" + id + "/executions", null);
        assertThat(executions.statusCode()).isEqualTo(200);
        assertThat(body(executions).get("page").get("totalItems").asInt()).isZero();
        assertProblem(http.send("GET", "/api/v1/jobs/none-" + tag + "/executions", null), 404, "not-found");
    }

    @Test
    void legacyEndpointsAreMarkedDeprecatedWithTheirSuccessor() {
        HttpResponse<String> legacy = http.send("GET", "/api/jobs", null);
        assertThat(legacy.statusCode()).as("it still works").isEqualTo(200);
        assertThat(legacy.headers().firstValue("Deprecation")).hasValue("@1794700800");
        assertThat(legacy.headers().allValues("Link")).contains("</api/v1/jobs>; rel=\"successor-version\"",
                "<" + LegacyApi.DOCUMENTATION + ">; rel=\"deprecation\"; type=\"text/html\"");

        HttpResponse<String> settings = http.send("GET", "/api/system/settings", null);
        assertThat(settings.headers().allValues("Link")).contains("</api/v1/settings/system>; rel=\"successor-version\"");

        assertThat(http.send("GET", "/api/v1/jobs", null).headers().firstValue("Deprecation")).isEmpty();
        assertThat(http.send("GET", "/api/auth/config", null).headers().firstValue("Deprecation")).isEmpty();

        JsonNode all = body(http.send("GET", "/v3/api-docs/all", null));
        JsonNode legacyList = all.get("paths").get("/api/jobs").get("get");
        assertThat(legacyList.get("deprecated").asBoolean()).isTrue();
        assertThat(legacyList.get("description").asString()).startsWith("Deprecated: use the Platform API, /api/v1/jobs.");
        assertThat(all.get("paths").get("/api/v1/jobs").get("get").has("deprecated")).isFalse();
        JsonNode config = all.get("paths").get("/api/auth/config");
        if (config != null) {
            assertThat(config.get("get").has("deprecated")).isFalse();
        }
    }

    /** Holds every run until {@code release} (or a stop), so tests can act while a run is in progress. */
    private java.util.concurrent.CountDownLatch blockRuns() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.BooleanSupplier stop = invocation.getArgument(9);
            for (int i = 0; i < 400 && !stop.getAsBoolean() && release.getCount() > 0; i++) {
                Thread.sleep(25);
            }
            return true;
        }).when(orchestrator).runJob(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return release;
    }

    private void awaitRunEnd(String executionId) throws InterruptedException {
        for (int i = 0; i < 200 && http.send("GET", "/api/v1/job-runs/" + executionId, null).statusCode() == 200; i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void aConditionalReadSeesARunsStateChangeAndAStopReachesOnlyItsOwnRun() throws Exception {
        java.util.concurrent.CountDownLatch release = blockRuns();
        try {
            String id = createJob(tag).get("id").asString();
            String before = http.send("GET", "/api/v1/jobs/" + id, null).headers().firstValue("ETag").orElseThrow();

            String first = body(http.send("POST", "/api/v1/jobs/" + id + ":start", null)).get("executionId").asString();
            HttpResponse<String> running = http.send("GET", "/api/v1/jobs/" + id, null, "If-None-Match", before);
            assertThat(running.statusCode()).as("the state changed, so the old version is not current: no 304").isEqualTo(200);
            assertThat(body(running).get("state").get("status").asString()).isEqualTo("Running");
            assertProblem(http.send("PATCH", "/api/v1/jobs/" + id, "{\"name\":\"" + tag + "-x\"}",
                    "Content-Type", "application/merge-patch+json", "If-Match", before), 412, "precondition-failed");

            assertThat(http.send("POST", "/api/v1/job-runs/" + first + ":stop", null).statusCode()).isEqualTo(202);
            awaitRunEnd(first);
            String second = body(http.send("POST", "/api/v1/jobs/" + id + ":start", null)).get("executionId").asString();
            assertProblem(http.send("POST", "/api/v1/job-runs/" + first + ":stop", null), 404, "not-found");
            HttpResponse<String> stillRunning = http.send("GET", "/api/v1/job-runs/" + second, null);
            assertThat(stillRunning.statusCode()).as("a stop of the ended run leaves the new run alone").isEqualTo(200);
            assertThat(body(stillRunning).get("stopRequested").asBoolean()).isFalse();
            http.send("POST", "/api/v1/job-runs/" + second + ":stop", null);
            awaitRunEnd(second);
        } finally {
            release.countDown();
            org.mockito.Mockito.reset(orchestrator);
        }
    }

    /** Sends the same request from many threads at once and counts the answers by status. */
    private Map<Integer, Integer> race(int threads, java.util.function.IntFunction<HttpResponse<String>> request) throws Exception {
        Map<Integer, Integer> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                done.add(pool.submit(() -> {
                    go.await();
                    statuses.merge(request.apply(n).statusCode(), 1, Integer::sum);
                    return null;
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> f : done) {
                f.get();
            }
        }
        return statuses;
    }

    @Test
    void ofManyChangesMadeFromTheSameVersionExactlyOneWins() throws Exception {
        String id = createJob(tag).get("id").asString();
        String jobEtag = http.send("GET", "/api/v1/jobs/" + id, null).headers().firstValue("ETag").orElseThrow();
        assertThat(race(12, n -> http.send("PATCH", "/api/v1/jobs/" + id, "{\"path\":\"/tmp/race-" + n + "\"}",
                "Content-Type", "application/merge-patch+json", "If-Match", jobEtag)))
                .as("jobs").containsEntry(200, 1).containsEntry(412, 11);

        String name = "Race_" + tag.replace("-", "_");
        String connector = "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\"}";
        createdConnectors.add(name);
        assertThat(race(12, n -> http.send("POST", "/api/v1/connectors", connector)))
                .as("creates of one name").containsEntry(201, 1).containsEntry(409, 11);
        String connectorEtag = http.send("GET", "/api/v1/connectors/" + name, null).headers().firstValue("ETag").orElseThrow();
        assertThat(race(12, n -> http.send("PUT", "/api/v1/connectors/" + name,
                connector.replace("}", ",\"description\":\"race " + n + "\"}"), "If-Match", connectorEtag)))
                .as("connectors").containsEntry(200, 1).containsEntry(412, 11);

        HttpResponse<String> settings = http.send("GET", "/api/v1/settings/system", null);
        String settingsEtag = settings.headers().firstValue("ETag").orElseThrow();
        int chunkSize = body(settings).get("chunkSize").asInt();
        try {
            assertThat(race(12, n -> http.send("PUT", "/api/v1/settings/system",
                    settings.body().replace("\"chunkSize\":" + chunkSize, "\"chunkSize\":" + (100 + n)), "If-Match", settingsEtag)))
                    .as("settings").containsEntry(200, 1).containsEntry(412, 11);
        } finally {
            http.send("PUT", "/api/v1/settings/system", settings.body());
        }
    }

    @Test
    void invalidSettingsAreRefusedWithTheirFieldsAndChangeNothing() {
        HttpResponse<String> read = http.send("GET", "/api/v1/settings/system", null);
        String etag = read.headers().firstValue("ETag").orElseThrow();
        String changed = read.body().replaceFirst("\"ollamaModel\":\"[^\"]*\"", "\"ollamaModel\":\"changed-model\"")
                .replaceFirst("\"tikaJvmHeap\":(null|\"[^\"]*\")", "\"tikaJvmHeap\":\"512m\"");

        HttpResponse<String> refused = http.send("PUT", "/api/v1/settings/system", changed);
        assertProblem(refused, 400, "validation-failed");
        assertThat(refused.body()).contains("\"field\":\"tikaJvmHeap\"", "-Xmx512m");
        assertThat(http.send("POST", "/api/system/settings", changed).statusCode()).as("the legacy endpoint too").isEqualTo(400);

        HttpResponse<String> after = http.send("GET", "/api/v1/settings/system", null);
        assertThat(after.headers().firstValue("ETag")).as("nothing was applied").hasValue(etag);
        assertThat(after.body()).doesNotContain("changed-model");
    }

    @Test
    void everyAnswerCarriesTheCacheAndSecurityHeaders() {
        for (HttpResponse<String> answer : List.of(
                http.send("GET", "/api/v1/jobs", null),
                http.send("GET", "/api/v1/jobs/none-" + tag, null),
                http.send("POST", "/api/v1/jobs", "{}"))) {
            assertThat(answer.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(answer.headers().firstValue("Content-Security-Policy")).hasValue("default-src 'none'; frame-ancestors 'none'");
            assertThat(answer.headers().allValues("Vary")).anySatisfy(vary -> assertThat(vary).contains("Authorization"));
        }
        assertThat(http.send("GET", "/api/v1/jobs", null).headers().firstValue("Cache-Control")).hasValue("private, no-cache");
        assertThat(http.send("POST", "/api/v1/jobs", "{}").headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void anOversizedBodyIsRefusedWhetherItAnnouncesItsLengthOrNot() throws Exception {
        String big = "{\"name\":\"" + "x".repeat(1_100_000) + "\"}";
        HttpResponse<String> announced = http.send("POST", "/api/v1/jobs", big);
        assertProblem(announced, 413, "body-too-large");
        assertThat(body(announced).get("maxBytes").asLong()).isEqualTo(1_048_576L);

        java.net.http.HttpRequest chunked = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/jobs"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(big.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .build();
        HttpResponse<String> streamed = java.net.http.HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.ofString());
        assertProblem(streamed, 413, "body-too-large");

        HttpResponse<String> keyed = http.send("POST", "/api/v1/jobs", big, "Idempotency-Key", "big-" + tag);
        assertProblem(keyed, 413, "body-too-large");
    }

    @Test
    void anIdempotencyKeyMayBeAStructuredFieldStringButNotOnAnUpload() {
        HttpResponse<String> quoted = http.send("POST", "/api/v1/jobs", jobJson(tag), "Idempotency-Key", "\"sf-" + tag + "\"");
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        createdJobs.add(body(quoted).get("id").asString());
        HttpResponse<String> bare = http.send("POST", "/api/v1/jobs", jobJson(tag), "Idempotency-Key", "sf-" + tag);
        assertThat(bare.headers().firstValue("Idempotent-Replayed")).as("the same key, quoted or not").hasValue("true");
        assertThat(body(bare).get("id").asString()).isEqualTo(body(quoted).get("id").asString());

        HttpResponse<String> upload = http.send("POST", "/api/v1/integrations/vespa:deploy-custom", "--b--\r\n",
                "Content-Type", "multipart/form-data; boundary=b", "Idempotency-Key", "up-" + tag);
        assertProblem(upload, 400, "validation-failed");
        assertThat(upload.body()).contains("not supported for uploads");
    }

    @Test
    void theApiDescriptionShowsTheHeadersAndLimitsEveryOperationShares() {
        JsonNode paths = body(http.send("GET", "/v3/api-docs/v1", null)).get("paths");
        JsonNode create = paths.get("/api/v1/jobs").get("post");
        assertThat(create.get("parameters").toString()).contains("\"name\":\"Idempotency-Key\"", "\"name\":\"X-Request-Id\"");
        assertThat(create.get("responses").has("413")).isTrue();
        assertThat(create.get("responses").has("415")).isTrue();
        assertThat(create.get("responses").get("201").get("headers").has("Idempotent-Replayed")).isTrue();
        assertThat(create.get("responses").get("409").get("headers").has("Retry-After")).isTrue();
        JsonNode list = paths.get("/api/v1/jobs").get("get");
        assertThat(list.get("responses").get("200").get("headers").has("Link")).isTrue();
        assertThat(list.get("responses").get("200").get("headers").has("X-Request-Id")).isTrue();
        assertThat(list.get("parameters").toString()).doesNotContain("Idempotency-Key");
        JsonNode upload = paths.get("/api/v1/integrations/vespa:deploy-custom").get("post");
        assertThat(upload.get("parameters").toString()).as("uploads take no key").doesNotContain("Idempotency-Key");
    }

    @Test
    void theIndexNamesEachLinkOnce() {
        JsonNode links = body(http.send("GET", "/api/v1", null)).get("links");
        assertThat(links.get("systemSettings").asString()).isEqualTo("/api/v1/settings/system");
        assertThat(links.get("transportSettings").asString()).isEqualTo("/api/v1/settings/transport");
        assertThat(links.get("jobRuns").asString()).isEqualTo("/api/v1/job-runs");
        assertThat(links.toString()).as("only resources that can be read").doesNotContain(":plan");
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.opencrawling.runtime.api.JobController jobController;

    /**
     * Every change of the job list takes the controller's lock, the one a conditional change (If-Match) holds from its
     * check to its write. Shown deterministically: while the test holds that lock, each writer must wait.
     */
    @Test
    void everyJobWriterWaitsForTheLockThatConditionalChangesHold() throws Exception {
        String id = createJob(tag).get("id").asString();
        Map<String, Runnable> writers = new java.util.LinkedHashMap<>();
        writers.put("pushed document (recordPushExecution)", () -> jobController.recordPushExecution(id, 1, true));
        writers.put("stop (updateJobStatus)", () -> jobController.stopJob(id));
        writers.put("pause (updateJobStatus)", () -> jobController.pauseJob(id));
        writers.put("save", () -> jobController.save(jobController.findJobById(id)));
        writers.put("delete", () -> jobController.deleteJob("none-" + tag));
        for (Map.Entry<String, Runnable> writer : writers.entrySet()) {
            Thread thread;
            synchronized (jobController) {
                thread = Thread.ofVirtual().start(writer.getValue());
                Thread.sleep(150);
                assertThat(thread.isAlive()).as(writer.getKey() + " waits while the lock is held").isTrue();
            }
            thread.join(5_000);
            assertThat(thread.isAlive()).as(writer.getKey() + " runs once the lock is free").isFalse();
        }
    }
}
