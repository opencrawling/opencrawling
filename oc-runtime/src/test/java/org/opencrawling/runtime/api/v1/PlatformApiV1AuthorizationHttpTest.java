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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opencrawling.runtime.security.IdentityTestApplication.PUBLIC_KEY_FILE;
import static org.opencrawling.runtime.security.IdentityTestApplication.validToken;

/** The Platform API with tokens required and management authorization on: the same rules as the legacy API. */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=jwt",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.security.management.enabled=true",
                "opencrawling.security.management.job-creator-groups=hr-team",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class PlatformApiV1AuthorizationHttpTest {

    @LocalServerPort
    int port;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String tag = "authz-" + UUID.randomUUID().toString().substring(0, 8);
    private String jobId;

    private static final String ADMIN = bearer(validToken(Map.of("preferred_username", "ops", "groups", List.of("opencrawling-admin"))));
    private static final String HR = bearer(validToken(Map.of("preferred_username", "hr.lead", "groups", List.of("hr-team"))));
    private static final String FINANCE = bearer(validToken(Map.of("preferred_username", "finance.lead", "groups", List.of("finance-team"))));

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
    }

    @BeforeEach
    void client() {
        http = new PlatformApiTestSupport.Http(port);
    }

    @AfterEach
    void cleanUp() {
        if (jobId != null) {
            as(ADMIN, "DELETE", "/api/v1/jobs/" + jobId, null);
        }
    }

    private HttpResponse<String> as(String token, String method, String path, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Authorization";
        all[1] = token;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return http.send(method, path, body, all);
    }

    private String hrJob() {
        HttpResponse<String> created = as(HR, "POST", "/api/v1/jobs", "{\"name\":\"" + tag + "\",\"repositoryConnector\":\"FileSystem_Local\","
                + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\",\"access\":{\"owner\":\"someone.else\",\"viewerGroups\":[\"auditors\"]}}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        jobId = json.readTree(created.body()).get("id").asString();
        assertThat(created.body()).as("the creator owns it, whatever the request says").contains("\"owner\":\"hr.lead\"");
        return jobId;
    }

    @Test
    void withoutATokenNothingAnswersButTheProblemHasARequestId() {
        HttpResponse<String> refused = http.send("GET", "/api/v1/jobs", null);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(refused.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(refused.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        assertThat(refused.body()).contains("#problem-unauthorized", "\"requestId\"");
    }

    @Test
    void jobsFollowTheirAccess() {
        String id = hrJob();
        assertThat(as(HR, "GET", "/api/v1/jobs/" + id, null).body()).contains("\"permissions\":[\"READ\",\"EXECUTE\",\"MANAGE\"]");
        assertThat(as(HR, "GET", "/api/v1/jobs?q=" + tag, null).body()).contains("\"totalItems\":1");
        assertThat(as(FINANCE, "GET", "/api/v1/jobs?q=" + tag, null).body()).contains("\"totalItems\":0");

        HttpResponse<String> hidden = as(FINANCE, "GET", "/api/v1/jobs/" + id, null);
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).contains("#problem-not-found");
        assertThat(as(FINANCE, "POST", "/api/v1/jobs/" + id + ":start", null).statusCode()).isEqualTo(404);
        assertThat(as(FINANCE, "DELETE", "/api/v1/jobs/" + id, null).statusCode()).isEqualTo(404);
        assertThat(as(FINANCE, "PATCH", "/api/v1/jobs/" + id, "{\"name\":\"x\"}", "Content-Type", "application/merge-patch+json").statusCode())
                .isEqualTo(404);

        HttpResponse<String> notCreator = as(FINANCE, "POST", "/api/v1/jobs", "{\"name\":\"f\",\"repositoryConnector\":\"FileSystem_Local\","
                + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\"}");
        assertThat(notCreator.statusCode()).isEqualTo(403);
        assertThat(notCreator.body()).contains("#problem-forbidden");

        assertThat(as(ADMIN, "GET", "/api/v1/jobs/" + id, null).statusCode()).isEqualTo(200);
        assertThat(as(ADMIN, "GET", "/api/v1/me", null).body()).contains("\"admin\":true");
        assertThat(as(HR, "GET", "/api/v1/me", null).body()).contains("\"principal\":\"hr.lead\"", "\"canCreateJobs\":true");
    }

    @Test
    void idempotencyKeysAreScopedToTheCaller() {
        String body = "{\"name\":\"" + tag + "\",\"repositoryConnector\":\"FileSystem_Local\",\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\"}";
        HttpResponse<String> first = as(HR, "POST", "/api/v1/jobs", body, "Idempotency-Key", "same-key-" + tag);
        jobId = json.readTree(first.body()).get("id").asString();
        HttpResponse<String> admin = as(ADMIN, "POST", "/api/v1/jobs", body, "Idempotency-Key", "same-key-" + tag);
        assertThat(admin.statusCode()).isEqualTo(201);
        assertThat(admin.headers().firstValue("Idempotent-Replayed")).as("another caller's key is not replayed").isEmpty();
        as(ADMIN, "DELETE", "/api/v1/jobs/" + json.readTree(admin.body()).get("id").asString(), null);
    }

    @Test
    void adminOnlyRoutesRefuseEveryoneElse() {
        String connector = "{\"name\":\"Authz_" + tag.replace("-", "_") + "\",\"type\":\"repository\",\"className\":\"x.Y\"}";
        List<String[]> adminOnly = List.of(
                new String[] {"POST", "/api/v1/connectors", connector},
                new String[] {"PUT", "/api/v1/connectors/FileSystem_Local", connector},
                new String[] {"DELETE", "/api/v1/connectors/FileSystem_Local", null},
                new String[] {"POST", "/api/v1/connectors/FileSystem_Local:check", null},
                new String[] {"POST", "/api/v1/connectors:check", connector},
                new String[] {"GET", "/api/v1/settings/system", null},
                new String[] {"PUT", "/api/v1/settings/transport", "{}"},
                new String[] {"POST", "/api/v1/settings/transport:test-grpc", "{}"},
                new String[] {"GET", "/api/v1/system/logs", null},
                new String[] {"GET", "/api/v1/observability/metrics", null},
                new String[] {"POST", "/api/v1/executions:query", "{\"sql\":\"select 1\"}"},
                new String[] {"POST", "/api/v1/migrations/manifoldcf:plan", "{}"},
                new String[] {"GET", "/api/v1/integrations/vespa/health?endpoint=http://v", null},
                new String[] {"POST", "/api/v1/integrations/vespa:query", "{}"});
        for (String[] route : adminOnly) {
            for (String token : List.of(HR, FINANCE)) {
                HttpResponse<String> refused = as(token, route[0], route[1], route[2]);
                assertThat(refused.statusCode()).as(route[0] + " " + route[1]).isEqualTo(403);
                assertThat(refused.body()).as(route[0] + " " + route[1]).contains("#problem-forbidden");
            }
        }
        assertThat(as(ADMIN, "GET", "/api/v1/settings/system", null).statusCode()).isEqualTo(200);
        assertThat(as(ADMIN, "GET", "/api/v1/system/logs", null).statusCode()).isEqualTo(200);
    }

    @Test
    void jobCreatorsListConnectorsWithoutConfiguration() {
        HttpResponse<String> listed = as(HR, "GET", "/api/v1/connectors?type=transformation", null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("OpenAI_Embedding_Prod").doesNotContain("sk-placeholder").contains("\"configuration\":null");
        assertThat(as(HR, "GET", "/api/v1/connectors/OpenAI_Embedding_Prod", null).body()).contains("\"configuration\":null");
        assertThat(as(FINANCE, "GET", "/api/v1/connectors", null).statusCode()).isEqualTo(403);

        String admin = as(ADMIN, "GET", "/api/v1/connectors/OpenAI_Embedding_Prod", null).body();
        assertThat(admin).contains("\"apiKey\":\"********\"").doesNotContain("sk-placeholder");
        assertThat(as(HR, "POST", "/api/v1/narrativization:generate-template", "{}").statusCode()).isNotEqualTo(403);
        assertThat(as(FINANCE, "POST", "/api/v1/narrativization:generate-template", "{}").statusCode()).isEqualTo(403);
    }

    @Test
    void schedulesEnforceJobVisibilityAndManagePermission() {
        HttpResponse<String> created = as(ADMIN, "POST", "/api/v1/jobs",
                "{\"name\":\"sched-auth-" + tag + "\",\"repositoryConnector\":\"FileSystem_Local\","
                        + "\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp\","
                        + "\"access\":{\"owner\":\"ops\",\"viewerGroups\":[\"hr-team\"]},"
                        + "\"schedule\":{\"cron\":\"0 2 * * *\",\"timezone\":\"UTC\",\"enabled\":true}}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        jobId = json.readTree(created.body()).get("id").asString();

        // Viewer (HR) can read the schedule, dispatches, list, overview and validate-cron, but cannot mutate it
        assertThat(as(HR, "GET", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(200);
        assertThat(as(HR, "GET", "/api/v1/jobs/" + jobId + "/schedule/dispatches", null).statusCode()).isEqualTo(200);
        assertThat(as(HR, "GET", "/api/v1/schedules?q=sched-auth-" + tag, null).body()).contains("\"totalItems\":1");
        assertThat(as(HR, "POST", "/api/v1/schedules:validate-cron", "{\"cron\":\"0 2 * * *\"}").statusCode()).isEqualTo(200);

        assertThat(as(HR, "PUT", "/api/v1/jobs/" + jobId + "/schedule",
                "{\"cron\":\"0 3 * * *\",\"enabled\":true}").statusCode()).isEqualTo(403);
        assertThat(as(HR, "POST", "/api/v1/jobs/" + jobId + "/schedule:pause", null).statusCode()).isEqualTo(403);
        assertThat(as(HR, "POST", "/api/v1/jobs/" + jobId + "/schedule:resume", null).statusCode()).isEqualTo(403);
        assertThat(as(HR, "DELETE", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(403);

        // Unauthorized team (FINANCE) gets 404 on job-scoped schedule routes and sees 0 items in /schedules
        assertThat(as(FINANCE, "GET", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(404);
        assertThat(as(FINANCE, "PUT", "/api/v1/jobs/" + jobId + "/schedule",
                "{\"cron\":\"0 3 * * *\",\"enabled\":true}").statusCode()).isEqualTo(404);
        assertThat(as(FINANCE, "GET", "/api/v1/schedules?q=sched-auth-" + tag, null).body()).contains("\"totalItems\":0");

        // Owner/Admin can pause & remove
        assertThat(as(ADMIN, "POST", "/api/v1/jobs/" + jobId + "/schedule:pause", null).statusCode()).isEqualTo(200);
        assertThat(as(ADMIN, "DELETE", "/api/v1/jobs/" + jobId + "/schedule", null).statusCode()).isEqualTo(204);
    }
}
