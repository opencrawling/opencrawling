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
package org.opencrawling.runtime.dynamicjob;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Approvals over real HTTP: agents' jobs wait, other admins approve or reject them over REST, and MCP cannot. */
@SpringBootTest(classes = {IdentityTestApplication.class, DynamicJobApprovalHttpTest.Jobs.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.jobs.dynamic.require-approval=true",
                "opencrawling.jobs.dynamic.connectors=Fake_Repo",
                "opencrawling.jobs.dynamic.admin-groups=oc-admins",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class DynamicJobApprovalHttpTest {

    static final List<String> CRAWLS = Collections.synchronizedList(new ArrayList<>());

    @Configuration
    @EnableConfigurationProperties(DynamicJobProperties.class)
    @Import({DynamicJobController.class, DynamicJobMcpTools.class})
    static class Jobs {
        @Bean
        DynamicJobService dynamicJobService(DynamicJobProperties properties) {
            DynamicJobService.CrawlRunner runner = (connector, start, jobId, jobName, executionId, stop, provenance) -> {
                CRAWLS.add(jobId + " from " + start + " by " + provenance.triggeredBy());
                return true;
            };
            return new DynamicJobService(() -> List.of(DynamicJobServiceTest.FAKE), dto -> new FakeDiscoverableConnector(),
                    new JobRunRegistry(0), runner, executionId -> 1L, properties, Clock.systemUTC());
        }
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + IdentityTestApplication.PUBLIC_KEY_FILE.toAbsolutePath());
    }

    private static String tokenFor(String user, String... groups) {
        return IdentityTestApplication.validToken(Map.of("preferred_username", user, "groups", List.of(groups),
                "scope", "oc:jobs:write"));
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String jobId(String json) {
        int start = json.indexOf("\"jobId\":\"") + 9;
        return json.substring(start, json.indexOf('"', start));
    }

    private static void awaitCrawl(String prefix) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (CRAWLS.stream().noneMatch(crawl -> crawl.startsWith(prefix)) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CRAWLS).anyMatch(crawl -> crawl.startsWith(prefix));
    }

    @Test
    void anAgentsJobWaitsUntilAnotherAdminApprovesIt() throws Exception {
        HttpResponse<String> requested = post("/api/jobs/dynamic", "{\"connector\":\"Fake_Repo\"}", tokenFor("alice"));
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(requested.body()).contains("\"state\":\"PENDING_APPROVAL\"");
        String jobId = jobId(requested.body());

        assertThat(post("/api/jobs/dynamic/" + jobId + "/approve", "", tokenFor("bob")).statusCode()).isEqualTo(403);
        assertThat(post("/api/jobs/dynamic/" + jobId + "/approve", "", tokenFor("alice", "oc-admins")).statusCode())
                .as("not the requester").isEqualTo(403);
        assertThat(CRAWLS).noneMatch(crawl -> crawl.startsWith(jobId));

        HttpResponse<String> approved = post("/api/jobs/dynamic/" + jobId + "/approve", "", tokenFor("olga", "oc-admins"));
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("\"approvedBy\":\"olga\"");
        awaitCrawl(jobId + " from root by alice");
        assertThat(post("/api/jobs/dynamic/" + jobId + "/approve", "", tokenFor("pete", "oc-admins")).statusCode()).isEqualTo(409);
    }

    @Test
    void anAdminCanRejectAJobWithAReason() throws Exception {
        String jobId = jobId(post("/api/jobs/dynamic", "{\"connector\":\"Fake_Repo\"}", tokenFor("dave")).body());

        HttpResponse<String> rejected = post("/api/jobs/dynamic/" + jobId + "/reject", "{\"reason\":\"Not now\"}",
                tokenFor("olga", "oc-admins"));

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.body()).contains("\"state\":\"REJECTED\"", "Rejected by olga: Not now");
        assertThat(CRAWLS).noneMatch(crawl -> crawl.startsWith(jobId));
    }

    @Test
    void agentsGetNoMcpToolToApproveJobs() {
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("olga", "oc-admins"))) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .contains("triggerDynamicJob")
                    .noneMatch(name -> name.toLowerCase().contains("approve") || name.toLowerCase().contains("reject"));
        }
    }
}
