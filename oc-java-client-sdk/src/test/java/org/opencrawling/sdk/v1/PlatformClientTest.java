/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.sdk.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.v1.model.Health;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.SystemSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The Platform API client's own behaviour, against a stub server: paths, headers, paging and errors. */
class PlatformClientTest {

    /** What the stub answers for "METHOD path?query". */
    private record Answer(int status, String contentType, String body, Map<String, String> headers) {}

    private HttpServer server;
    private final Map<String, Answer> answers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Answers given once each, before {@link #answers}, in order. */
    private final Map<String, java.util.Deque<Answer>> queued = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> requestHeaders = new CopyOnWriteArrayList<>();
    private PlatformClient api;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : "");
            requests.add(key);
            requestHeaders.add(exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> e.getValue().get(0), (a, b) -> a)));
            exchange.getRequestBody().readAllBytes();
            java.util.Deque<Answer> once = queued.get(key);
            Answer next = once != null ? once.poll() : null;
            Answer answer = next != null ? next : answers.getOrDefault(key, new Answer(404, "application/problem+json",
                    "{\"type\":\"https://opencrawling.org/problems/not-found\",\"title\":\"Not found\",\"status\":404}", Map.of()));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", answer.contentType());
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        api = OpenCrawlingClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build().platform();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String request, int status, String body, Map<String, String> headers) {
        answers.put(request, new Answer(status, "application/json", body, headers));
    }

    @Test
    void idsAreEncodedAndTheActionStaysLiteral() {
        answer("POST /api/v1/jobs/a%20b%2Fc:pause", 200, "{\"id\":\"a b/c\"}", Map.of());
        answer("GET /api/v1/connectors/HR%20Share", 200, "{\"name\":\"HR Share\"}", Map.of("ETag", "\"c1\""));

        assertThat(api.jobs().pause("a b/c").id()).isEqualTo("a b/c");
        assertThat(api.connectors().get("HR Share").etag()).isEqualTo("\"c1\"");
    }

    @Test
    void requestOptionsBecomeHeadersAndAreLeftOutWhenNotSet() {
        answer("POST /api/v1/jobs/1:start", 202, "{\"executionId\":\"exec-1\",\"jobId\":\"1\"}",
                Map.of("Location", "/api/v1/job-runs/exec-1"));

        Versioned<?> run = api.jobs().start("1", RequestOptions.idempotent("k-1").withCorrelationId("c-1")
                .withRequestId("r-1"));
        assertThat(run.location()).isEqualTo("/api/v1/job-runs/exec-1");
        assertThat(requestHeaders.get(0)).containsEntry("idempotency-key", "k-1").containsEntry("x-correlation-id", "c-1")
                .containsEntry("x-request-id", "r-1").doesNotContainKey("if-match");
        assertThat(requestHeaders.get(0).get("accept")).contains("application/problem+json");

        api.jobs().start("1", RequestOptions.none());
        assertThat(requestHeaders.get(1)).doesNotContainKeys("x-correlation-id", "x-request-id");
        assertThat(requestHeaders.get(1).get("idempotency-key")).as("a POST without a key gets one, so it can be retried")
                .startsWith("sdk-");
    }

    @Test
    void problemsBecomeExceptionsWithTheirDetailsEvenWhenAProxyAnswersWithoutOne() {
        answers.put("GET /api/v1/jobs/1", new Answer(409, "application/problem+json",
                "{\"type\":\"https://opencrawling.org/problems/job-already-running\",\"title\":\"Conflict\",\"status\":409,"
                        + "\"detail\":\"Job 1 is running\",\"requestId\":\"r-9\",\"executionId\":\"exec-1\","
                        + "\"errors\":[{\"field\":\"name\",\"message\":\"must not be blank\"}]}", Map.of()));
        ApiProblemException problem = catchThrowableOfType(ApiProblemException.class, () -> api.jobs().get("1"));
        assertThat(problem.code()).isEqualTo("job-already-running");
        assertThat(problem.getStatusCode()).isEqualTo(409);
        assertThat(problem.problem().extensions()).containsEntry("executionId", "exec-1");
        assertThat(problem.getMessage()).contains("Job 1 is running", "name must not be blank", "r-9");

        answers.put("GET /api/v1/jobs/2", new Answer(502, "text/html", "<html>Bad gateway</html>",
                Map.of("X-Request-Id", "r-proxy")));
        ApiProblemException gateway = catchThrowableOfType(ApiProblemException.class, () -> api.jobs().get("2"));
        assertThat(gateway.getStatusCode()).isEqualTo(502);
        assertThat(gateway.code()).isNull();
        assertThat(gateway.problem().getDetail()).contains("Bad gateway");
        assertThat(gateway.problem().getRequestId()).as("from the header").isEqualTo("r-proxy");
    }

    @Test
    void anUnhealthyRuntimeAnswersWithItsHealthRatherThanAnError() {
        answer("GET /api/v1/health", 503, "{\"status\":\"DEGRADED\",\"components\":{\"postgres\":\"DOWN\"}}", Map.of());
        Health health = api.health();
        assertThat(health.isHealthy()).isFalse();
        assertThat(health.components()).containsEntry("postgres", "DOWN");
    }

    @Test
    void aConditionalReadIsEmptyWhenNothingChanged() {
        answers.put("GET /api/v1/jobs/1", new Answer(304, "application/json", "", Map.of("ETag", "\"v1\"")));
        assertThat(api.jobs().getIfChanged("1", "\"v1\"")).isEmpty();
        assertThat(requestHeaders.get(0)).containsEntry("if-none-match", "\"v1\"");
    }

    @Test
    void streamsFollowTheCursorsOnlyAsFarAsTheyAreRead() {
        answer("GET /api/v1/jobs?q=hr", 200,
                "{\"items\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"page\":{\"limit\":2,\"nextCursor\":\"c2\",\"totalItems\":5}}", Map.of());
        answer("GET /api/v1/jobs?q=hr&cursor=c2", 200,
                "{\"items\":[{\"id\":\"3\"},{\"id\":\"4\"}],\"page\":{\"limit\":2,\"nextCursor\":\"c3\",\"totalItems\":5}}", Map.of());
        answer("GET /api/v1/jobs?q=hr&cursor=c3", 200,
                "{\"items\":[{\"id\":\"5\"}],\"page\":{\"limit\":2,\"nextCursor\":null,\"totalItems\":5}}", Map.of());

        assertThat(api.jobs().stream(PlatformClient.JobFilter.all().withText("hr")).limit(3).map(Job::id))
                .containsExactly("1", "2", "3");
        assertThat(requests).as("the third page was not read").hasSize(2);
        requests.clear();
        assertThat(api.jobs().stream(PlatformClient.JobFilter.all().withText("hr")).map(Job::id))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(requests).hasSize(3);
    }

    @Test
    void settingsKeepEveryFieldTheRuntimeSentThroughAChange() throws IOException {
        String sent = "{\"chunkSize\":800,\"tikaEngine\":\"PipesForkParser\",\"tikaTimeoutMs\":30000,\"fieldFromTheFuture\":{\"a\":1}}";
        answer("GET /api/v1/settings/system", 200, sent, Map.of("ETag", "\"s1\""));
        Versioned<SystemSettings> settings = api.settings().system();
        SystemSettings changed = settings.value().withChunkSize(900);

        ObjectNode json = new ObjectMapper().valueToTree(changed);
        assertThat(json.get("chunkSize").asInt()).isEqualTo(900);
        assertThat(json.get("tikaEngine").asText()).isEqualTo("PipesForkParser");
        assertThat(json.get("fieldFromTheFuture").get("a").asInt()).isEqualTo(1);
        assertThat(settings.value().chunkSize()).as("the read copy is unchanged").isEqualTo(800);
        assertThat(changed.with("tikaTimeoutMs", null).get("tikaTimeoutMs")).as("null removes a field").isNull();
    }

    @Test
    void emptyIdsAreRefusedBeforeAnyRequest() {
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> api.jobs().get(""))).isNotNull();
        assertThat(requests).isEmpty();
    }

    @Test
    void theProblemCodeComesFromTheTypesAnchorOrItsLastSegment() {
        ApiProblem anchored = new ApiProblem(404, "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-not-found",
                "Not found", null);
        assertThat(anchored.code()).isEqualTo("not-found");
        ApiProblem path = new ApiProblem(404, "https://opencrawling.org/problems/not-found", "Not found", null);
        assertThat(path.code()).isEqualTo("not-found");
        assertThat(new ApiProblem(500, null, "x", null).code()).isNull();
    }

    private void answerOnce(String request, int status, String body, Map<String, String> headers) {
        queued.computeIfAbsent(request, r -> new java.util.concurrent.ConcurrentLinkedDeque<>())
                .add(new Answer(status, "application/json", body, headers));
    }

    @Test
    void safeRequestsAreRetriedAndAPostKeepsItsKeyAcrossAttempts() {
        PlatformClient fast = OpenCrawlingClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .retryPolicy(new RetryPolicy(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5))).build().platform();
        answerOnce("GET /api/v1/jobs/1", 503, "{}", Map.of());
        answer("GET /api/v1/jobs/1", 200, "{\"id\":\"1\"}", Map.of());
        assertThat(fast.jobs().get("1").value().id()).isEqualTo("1");
        assertThat(requests).containsExactly("GET /api/v1/jobs/1", "GET /api/v1/jobs/1");

        requests.clear();
        requestHeaders.clear();
        answerOnce("POST /api/v1/jobs/1:start", 502, "", Map.of());
        answerOnce("POST /api/v1/jobs/1:start", 429, "", Map.of("Retry-After", "0"));
        answer("POST /api/v1/jobs/1:start", 202, "{\"executionId\":\"exec-1\"}", Map.of());
        assertThat(fast.jobs().start("1", RequestOptions.none()).value().executionId()).isEqualTo("exec-1");
        assertThat(requests).hasSize(3);
        assertThat(requestHeaders).extracting(h -> h.get("idempotency-key")).as("one key for all attempts").containsOnly(
                requestHeaders.get(0).get("idempotency-key"));
    }

    @Test
    void whatIsNotSafeToRepeatOrIsAnAnswerIsNotRetried() {
        PlatformClient fast = OpenCrawlingClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .retryPolicy(new RetryPolicy(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5))).build().platform();
        answer("GET /api/v1/health", 503, "{\"status\":\"DEGRADED\"}", Map.of());
        assertThat(fast.health().isHealthy()).isFalse();
        assertThat(requests).as("an unhealthy runtime is an answer").hasSize(1);

        requests.clear();
        answer("PATCH /api/v1/jobs/1", 503, "{\"status\":503}", Map.of());
        assertThat(catchThrowableOfType(ApiProblemException.class,
                () -> fast.jobs().patch("1", Map.of("name", "x"), RequestOptions.none()))).isNotNull();
        assertThat(requests).as("an unconditional patch is sent once").hasSize(1);
    }

    @Test
    void defaultOptionsReachCallsThatTakeNoneAndExportsStreamToTheFile() throws IOException {
        answer("POST /api/v1/jobs/1:stop", 200, "{\"id\":\"1\"}", Map.of());
        api.withDefaults(RequestOptions.none().withCorrelationId("batch-7").withRequestId("r-7")).jobs().stop("1");
        assertThat(requestHeaders.get(0)).containsEntry("x-correlation-id", "batch-7").containsEntry("x-request-id", "r-7");

        answers.put("GET /api/v1/executions/exec-1/export?format=csv", new Answer(200, "text/csv", "id,status\nd1,OK\n", Map.of()));
        java.nio.file.Path target = java.nio.file.Files.createTempFile("export", ".csv");
        try {
            api.executions().exportTo("exec-1", "csv", target);
            assertThat(java.nio.file.Files.readString(target)).isEqualTo("id,status\nd1,OK\n");
            ApiProblemException missing = catchThrowableOfType(ApiProblemException.class,
                    () -> api.executions().exportTo("exec-2", "avro", target));
            assertThat(missing.getStatusCode()).isEqualTo(404);
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }
}
