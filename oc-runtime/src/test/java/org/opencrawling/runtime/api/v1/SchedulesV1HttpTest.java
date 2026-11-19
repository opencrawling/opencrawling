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
import org.opencrawling.runtime.scheduler.DispatchOutcome;
import org.opencrawling.runtime.scheduler.ScheduleDispatchRecord;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end HTTP tests for Platform API v1 native scheduling endpoints (#197). */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE"})
class SchedulesV1HttpTest {

    @LocalServerPort
    int port;

    @Autowired
    private ScheduleStore scheduleStore;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String tag = "sched-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> createdJobs = new ArrayList<>();

    @BeforeEach
    void client() {
        http = new PlatformApiTestSupport.Http(port);
    }

    @AfterEach
    void cleanUp() {
        createdJobs.forEach(id -> http.send("DELETE", "/api/v1/jobs/" + id, null));
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String createJob(String name, String scheduleJson) {
        String payload = "{\"name\":\"" + name + "\",\"repositoryConnector\":\"FileSystem_Local\","
                + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp/" + name + "\","
                + "\"pipelineMode\":\"rag\""
                + (scheduleJson != null ? ",\"schedule\":" + scheduleJson : "") + "}";
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", payload);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = body(created).get("id").asString();
        createdJobs.add(id);
        return id;
    }

    @Test
    void indexAndValidationEndpointExposeScheduleContract() {
        JsonNode index = body(http.send("GET", "/api/v1", null));
        assertThat(index.get("links").get("schedules").asString()).isEqualTo("/api/v1/schedules");

        HttpResponse<String> valid = http.send("POST", "/api/v1/schedules:validate-cron",
                "{\"cron\":\"30 2 1 * *\",\"timezone\":\"Europe/Rome\",\"count\":3}");
        assertThat(valid.statusCode()).isEqualTo(200);
        JsonNode validBody = body(valid);
        assertThat(validBody.get("valid").asBoolean()).isTrue();
        assertThat(validBody.get("normalizedCron").asString()).isEqualTo("0 30 2 1 * *");
        assertThat(validBody.get("timezone").asString()).isEqualTo("Europe/Rome");
        assertThat(validBody.get("nextFireTimes")).hasSize(3);

        HttpResponse<String> invalid = http.send("POST", "/api/v1/schedules:validate-cron",
                "{\"cron\":\"*/10 * * * * *\",\"timezone\":\"UTC\",\"count\":3}");
        assertThat(invalid.statusCode()).isEqualTo(200);
        JsonNode invalidBody = body(invalid);
        assertThat(invalidBody.get("valid").asBoolean()).isFalse();
        assertThat(invalidBody.get("error").asString()).contains("below the minimum allowed interval");
    }

    @Test
    void createWithInvalidScheduleReturnsStructured400Problem() {
        HttpResponse<String> bad = http.send("POST", "/api/v1/jobs",
                "{\"name\":\"bad-" + tag + "\",\"repositoryConnector\":\"FileSystem_Local\","
                        + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\","
                        + "\"schedule\":{\"cron\":\"not a cron\",\"timezone\":\"UTC\",\"enabled\":true}}");
        assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
        assertThat(bad.body()).contains("#problem-validation-failed").contains("\"errors\"");
    }

    @Test
    void lifecycleEtagsPauseResumeListOverviewAndDispatches() {
        String jobId = createJob(tag, null);
        assertThat(http.send("GET", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(404);

        HttpResponse<String> set = http.send("PUT", "/api/v1/jobs/" + jobId + "/schedule",
                "{\"cron\":\"0 2 * * *\",\"timezone\":\"Europe/Rome\",\"enabled\":true,"
                        + "\"overlapPolicy\":\"COALESCE\",\"misfirePolicy\":\"FIRE_ONCE\","
                        + "\"jitterSeconds\":30,\"maxConsecutiveFailures\":3}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        String etag = set.headers().firstValue("ETag").orElseThrow();
        JsonNode setJson = body(set);
        assertThat(setJson.get("schedule").get("cron").asString()).isEqualTo("0 2 * * *");
        assertThat(setJson.get("state").get("enabled").asBoolean()).isTrue();
        assertThat(setJson.get("nextFireTimes")).hasSize(5);

        HttpResponse<String> get = http.send("GET", "/api/v1/jobs/" + jobId + "/schedule", null);
        assertThat(get.statusCode()).isEqualTo(200);
        String currentEtag = get.headers().firstValue("ETag").orElse(etag);
        assertThat(http.send("GET", "/api/v1/jobs/" + jobId + "/schedule", null, "If-None-Match", currentEtag)
                .statusCode()).isEqualTo(304);

        HttpResponse<String> stale = http.send("PUT", "/api/v1/jobs/" + jobId + "/schedule",
                "{\"cron\":\"0 3 * * *\",\"timezone\":\"UTC\",\"enabled\":true}",
                "If-Match", "\"stale\"");
        assertThat(stale.statusCode()).isEqualTo(412);

        HttpResponse<String> paused = http.send("POST", "/api/v1/jobs/" + jobId + "/schedule:pause",
                null, "Idempotency-Key", "pause-" + tag);
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(200);
        assertThat(body(paused).get("schedule").get("enabled").asBoolean()).isFalse();
        assertThat(body(paused).get("state").get("enabled").asBoolean()).isFalse();

        // Idempotent replay on :pause
        HttpResponse<String> replayed = http.send("POST", "/api/v1/jobs/" + jobId + "/schedule:pause",
                null, "Idempotency-Key", "pause-" + tag);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(replayed.headers().firstValue("Idempotent-Replayed")).hasValue("true");

        HttpResponse<String> resumed = http.send("POST", "/api/v1/jobs/" + jobId + "/schedule:resume", null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(body(resumed).get("schedule").get("enabled").asBoolean()).isTrue();

        // Embedded on JobResource too
        JsonNode jobResource = body(http.send("GET", "/api/v1/jobs/" + jobId, null));
        assertThat(jobResource.get("schedule").get("cron").asString()).isEqualTo("0 2 * * *");
        assertThat(jobResource.get("scheduleState").get("enabled").asBoolean()).isTrue();

        // List & overview include our job
        JsonNode listed = body(http.send("GET", "/api/v1/schedules?enabled=true&q=" + tag, null));
        assertThat(listed.get("items")).hasSize(1);

        JsonNode overview = body(http.send("GET", "/api/v1/schedules/overview?horizonHours=48", null));
        assertThat(overview.get("totalScheduledJobs").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(overview.get("activeSchedules").asInt()).isGreaterThanOrEqualTo(1);

        // Dispatch audit history
        Instant now = Instant.now();
        scheduleStore.recordDispatch(new ScheduleDispatchRecord(
                "disp-" + tag, jobId, now, now, "test-rt", "exec-1", DispatchOutcome.STARTED, null));
        JsonNode dispatches = body(http.send("GET", "/api/v1/jobs/" + jobId + "/schedule/dispatches?outcome=STARTED", null));
        assertThat(dispatches.get("items")).hasSize(1);
        assertThat(dispatches.get("items").get(0).get("executionId").asString()).isEqualTo("exec-1");

        // Delete schedule cleanly with latest ETag
        String latestEtag = http.send("GET", "/api/v1/jobs/" + jobId + "/schedule", null)
                .headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> deleted = http.send("DELETE", "/api/v1/jobs/" + jobId + "/schedule",
                null, "If-Match", latestEtag);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(http.send("GET", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(404);
    }
}
