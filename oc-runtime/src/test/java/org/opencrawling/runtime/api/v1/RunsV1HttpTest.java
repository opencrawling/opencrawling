/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.dynamicjob.DynamicJobController;
import org.opencrawling.runtime.dynamicjob.DynamicJobProperties;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.dynamicjob.DynamicJobServiceTestAccess;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/runs and its actions over HTTP (#316), with dynamic jobs on and signed-in callers: a dynamic job is
 * listed, read, paused, resumed and stopped through the API of every kind of run, by its owner only, and its history
 * follows it.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class, RunsV1HttpTest.Jobs.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=jwt",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.jobs.dynamic.connectors=Fake_Repo",
                "opencrawling.jobs.dynamic.admin-groups=oc-admins",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class RunsV1HttpTest {

    static volatile CountDownLatch hold = new CountDownLatch(0);

    @Configuration
    @EnableConfigurationProperties(DynamicJobProperties.class)
    @Import({DynamicJobController.class, DynamicJobsV1Controller.class})
    static class Jobs {
        @Bean
        DynamicJobService dynamicJobService(DynamicJobProperties properties, JobRunRegistry registry) {
            DynamicJobService.CrawlRunner runner = (connector, start, jobId, jobName, executionId, stop, provenance, chunking) -> {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return false;
                }
                return true;
            };
            return new DynamicJobService(() -> List.of(DynamicJobServiceTestAccess.FAKE), dto -> new FakeDiscoverableConnector(),
                    registry, runner, executionId -> 1L, properties, Clock.systemUTC());
        }
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + IdentityTestApplication.PUBLIC_KEY_FILE.toAbsolutePath());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void release() {
        hold.countDown();
    }

    private static String token(String user, String... groups) {
        return "Bearer " + IdentityTestApplication.validToken(Map.of("preferred_username", user, "groups", List.of(groups),
                "scope", "oc:jobs:write"));
    }

    private HttpResponse<String> send(String method, String path, String token) {
        return new PlatformApiTestSupport.Http(port).send(method, path, method.equals("POST") ? "{}" : null,
                "Authorization", token);
    }

    private JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private List<String> runIds(HttpResponse<String> response) {
        List<String> ids = new ArrayList<>();
        json(response).get("items").forEach(item -> ids.add(item.get("runId").asText()));
        return ids;
    }

    @Test
    void aDynamicJobIsFollowedPausedAndStoppedThroughTheRunsApiByItsOwnerOnly() {
        hold = new CountDownLatch(1);
        String alice = token("alice");
        HttpResponse<String> started = new PlatformApiTestSupport.Http(port).send("POST", "/api/v1/dynamic-jobs",
                "{\"connector\":\"Fake_Repo\",\"name\":\"Runs API\"}", "Authorization", alice);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String runId = json(started).get("executionId").asText();
        String jobId = json(started).get("jobId").asText();

        HttpResponse<String> listed = send("GET", "/api/v1/runs?kind=DYNAMIC_FOLDER&state=running", alice);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(runIds(listed)).contains(runId);
        JsonNode run = json(send("GET", "/api/v1/runs/" + runId, alice));
        assertThat(run.get("kind").asText()).isEqualTo("DYNAMIC_FOLDER");
        assertThat(run.get("jobId").asText()).isEqualTo(jobId);
        assertThat(run.get("name").asText()).isEqualTo("Runs API");
        assertThat(run.get("startedBy").asText()).isEqualTo("alice");
        assertThat(run.get("runtimeId").asText()).isNotBlank();
        assertThat(run.get("links").get("dynamicJob").asText()).isEqualTo("/api/v1/dynamic-jobs/" + jobId);

        String bob = token("bob");
        assertThat(runIds(send("GET", "/api/v1/runs", bob))).as("another user does not see it").doesNotContain(runId);
        assertThat(send("GET", "/api/v1/runs/" + runId, bob).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/runs/" + runId + ":stop", bob).statusCode()).isEqualTo(404);
        assertThat(runIds(send("GET", "/api/v1/runs", token("olga", "oc-admins")))).as("an admin does").contains(runId);

        HttpResponse<String> paused = send("POST", "/api/v1/runs/" + runId + ":pause", alice);
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(202);
        assertThat(json(paused).get("state").asText()).isEqualTo("PAUSED");
        assertThat(json(send("GET", "/api/v1/dynamic-jobs/" + jobId, alice)).get("state").asText())
                .as("the dynamic job's own API agrees").isEqualTo("PAUSED");
        assertThat(json(send("POST", "/api/v1/runs/" + runId + ":resume", alice)).get("state").asText()).isEqualTo("RUNNING");

        HttpResponse<String> stopped = send("POST", "/api/v1/runs/" + runId + ":stop", alice);
        assertThat(stopped.statusCode()).isEqualTo(202);
        assertThat(json(stopped).get("stopRequested").asBoolean()).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!json(send("GET", "/api/v1/runs/" + runId, alice)).get("state").asText().equals("STOPPED")
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        JsonNode history = json(send("GET", "/api/v1/runs/" + runId + "/history", alice));
        assertThat(history.get("run").get("state").asText()).isEqualTo("STOPPED");
        List<String> events = new ArrayList<>();
        history.get("steps").forEach(step -> events.add(step.get("event").asText()));
        // one run from its request (#316): queued, then started in place
        assertThat(events).containsExactly("QUEUED", "STARTED", "PAUSED", "RESUMED", "STOP_REQUESTED", "STOPPED");

        HttpResponse<String> late = send("POST", "/api/v1/runs/" + runId + ":pause", alice);
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("job-not-running");
    }

    @Test
    void anUnknownKindOrStateIsRefused() {
        HttpResponse<String> response = send("GET", "/api/v1/runs?kind=SCENARIO", token("alice"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("kind");
        assertThat(send("GET", "/api/v1/runs?state=DONE", token("alice")).statusCode()).isEqualTo(400);
    }
}
