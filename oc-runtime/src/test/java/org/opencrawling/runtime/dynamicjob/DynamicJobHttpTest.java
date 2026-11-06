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
import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamic jobs over real HTTP in {@code jwt} mode with {@code protect-api}: the REST endpoints and the MCP tools
 * take the caller and its scopes from the bearer token, and a job's status is visible to its user only.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, DynamicJobHttpTest.Jobs.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.jobs.dynamic.connectors=Fake_Repo",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class DynamicJobHttpTest {

    /** Where each crawl started and by which trigger. */
    static final List<String> CRAWLS = Collections.synchronizedList(new ArrayList<>());
    /** The chunking each crawl ran with, by start and user. */
    static final Map<String, ChunkingSpec> CHUNKINGS = new java.util.concurrent.ConcurrentHashMap<>();

    @Configuration
    @EnableConfigurationProperties(DynamicJobProperties.class)
    @Import({DynamicJobController.class, DynamicJobMcpTools.class})
    static class Jobs {
        @Bean
        DynamicJobService dynamicJobService(DynamicJobProperties properties) {
            ConnectorDTO fake = new ConnectorDTO("Fake_Repo", "A fake repository", "repository",
                    FakeDiscoverableConnector.class.getName(), 1, Map.of());
            DynamicJobService.CrawlRunner runner = (connector, start, jobId, jobName, executionId, stop, provenance, chunking) -> {
                if (chunking != null) {
                    CHUNKINGS.put(start + " by " + provenance.triggeredBy(), chunking);
                }
                CRAWLS.add(start + " via " + provenance.triggerSource() + " by " + provenance.triggeredBy());
                return true;
            };
            return new DynamicJobService(() -> List.of(fake), dto -> new FakeDiscoverableConnector(),
                    new JobRunRegistry(0), runner, executionId -> 3L, properties, Clock.systemUTC());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    DynamicJobService service;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + IdentityTestApplication.PUBLIC_KEY_FILE.toAbsolutePath());
    }

    private static String tokenFor(String user, String scope, String... groups) {
        return IdentityTestApplication.validToken(Map.of("preferred_username", user, "groups", List.of(groups),
                "scope", scope));
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The crawl runs on its own thread; wait for it to have started. */
    private static void awaitCrawl(String crawl) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!CRAWLS.contains(crawl) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CRAWLS).contains(crawl);
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"");
        assertThat(at).as(name + " in " + json).isNotNegative();
        int start = at + name.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    @Test
    void aTokenWithTheScopeStartsAJobItsUserCanFollow() throws Exception {
        String alice = tokenFor("alice", "openid oc:jobs:write", "finance");

        HttpResponse<String> started = send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"start\":\"finance-folder\",\"name\":\"Q3\"}", alice,
                "Idempotency-Key", "q3-once", "X-Correlation-Id", "req-7");

        assertThat(started.statusCode()).isEqualTo(202);
        String jobId = field(started.body(), "jobId");
        assertThat(started.headers().firstValue("Location")).contains("/api/jobs/dynamic/" + jobId);
        awaitCrawl("finance-folder via " + ExecutionProvenance.SOURCE_DYNAMIC_API + " by alice");

        HttpResponse<String> again = send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"start\":\"finance-folder\",\"name\":\"Q3\"}", alice, "Idempotency-Key", "q3-once");
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(field(again.body(), "jobId")).isEqualTo(jobId);
        assertThat(again.body()).contains("\"replayed\":true");

        HttpResponse<String> status = send("GET", "/api/jobs/dynamic/" + jobId, null, alice);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"owner\":\"alice\"", "\"connector\":\"Fake_Repo\"").doesNotContain("password");
        assertThat(send("GET", "/api/jobs/dynamic/" + jobId, null, tokenFor("bob", "oc:jobs:write")).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/api/jobs/dynamic", null, tokenFor("bob", "oc:jobs:write")).body()).isEqualTo("[]");
    }

    @Test
    void aFolderTheCallerCannotReadIsRefusedLikeAMissingOne() throws Exception {
        HttpResponse<String> response = send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"start\":\"finance-folder\"}", tokenFor("carol", "oc:jobs:write"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("No fake folder 'finance-folder' in this connector's scope.");
    }

    @Test
    void validateOnlyStartsNothing() throws Exception {
        int before = CRAWLS.size();

        HttpResponse<String> response = send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"validateOnly\":true}", tokenFor("carol", "oc:jobs:write"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"valid\":true", "\"id\":\"root\"");
        assertThat(CRAWLS).hasSize(before);
    }

    @Test
    void noTokenOrNoScopeIsRefused() throws Exception {
        assertThat(send("POST", "/api/jobs/dynamic", "{\"connector\":\"Fake_Repo\"}", null).statusCode()).isEqualTo(401);
        HttpResponse<String> noScope = send("POST", "/api/jobs/dynamic", "{\"connector\":\"Fake_Repo\"}", tokenFor("alice", "openid"));
        assertThat(noScope.statusCode()).isEqualTo(403);
        assertThat(noScope.body()).contains("oc:jobs:write");
    }

    @Test
    void oneDocumentIsIngestedOrRemovedOverRest() throws Exception {
        String workflow = tokenFor("alice", "oc:jobs:run", "finance");

        HttpResponse<String> ingested = send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\"}", workflow);
        assertThat(ingested.statusCode()).isEqualTo(202);
        String jobId = field(ingested.body(), "jobId");
        assertThat(ingested.headers().firstValue("Location")).contains("/api/jobs/dynamic/" + jobId);
        awaitCrawl("finance-report via " + ExecutionProvenance.SOURCE_DYNAMIC_API + " by alice");
        assertThat(send("GET", "/api/jobs/dynamic/" + jobId, null, workflow).body()).contains("\"kind\":\"DOCUMENT\"");

        HttpResponse<String> removed = send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"deleted-old\",\"action\":\"DELETE\"}", workflow);
        HttpResponse<String> hidden = send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"alice-notes\",\"action\":\"DELETE\"}",
                tokenFor("carol", "oc:jobs:run"));
        assertThat(removed.statusCode()).isEqualTo(202);
        assertThat(hidden.statusCode()).isEqualTo(202);
        assertThat(removed.body().replace("deleted-old", "X")).isEqualTo(hidden.body().replace("alice-notes", "X"));
        awaitCrawl("fake://deleted-old via " + ExecutionProvenance.SOURCE_DYNAMIC_API + " by alice");

        assertThat(send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\",\"action\":\"DELETE\"}", workflow)
                .statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\"}", tokenFor("carol", "oc:jobs:run"))
                .statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\"}", tokenFor("alice", "oc:jobs:write", "finance"))
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\"}", null).statusCode()).isEqualTo(401);
    }

    @Test
    void aJobsChunkingIsTakenOverRestAndMcp() throws Exception {
        String erin = tokenFor("erin", "oc:jobs:write oc:jobs:run", "finance");

        HttpResponse<String> folder = send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"start\":\"finance-folder\",\"chunking\":{\"chunkSize\":400,\"keepSeparator\":false}}", erin);
        assertThat(folder.statusCode()).isEqualTo(202);
        awaitCrawl("finance-folder via " + ExecutionProvenance.SOURCE_DYNAMIC_API + " by erin");
        assertThat(CHUNKINGS.get("finance-folder by erin")).isEqualTo(new ChunkingSpec(400, null, null, false));
        assertThat(send("GET", "/api/jobs/dynamic/" + field(folder.body(), "jobId"), null, erin).body())
                .contains("\"chunking\":{\"chunkSize\":400");

        HttpResponse<String> document = send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"finance-report\",\"chunking\":{\"minChunkLengthToEmbed\":20}}", erin);
        assertThat(document.statusCode()).isEqualTo(202);
        awaitCrawl("finance-report via " + ExecutionProvenance.SOURCE_DYNAMIC_API + " by erin");
        assertThat(CHUNKINGS.get("finance-report by erin")).isEqualTo(new ChunkingSpec(null, null, 20, null));

        assertThat(send("POST", "/api/jobs/dynamic",
                "{\"connector\":\"Fake_Repo\",\"chunking\":{\"chunkSize\":5}}", erin).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/jobs/dynamic/documents",
                "{\"connector\":\"Fake_Repo\",\"document\":\"deleted-x\",\"action\":\"DELETE\",\"chunking\":{\"chunkSize\":300}}", erin)
                .statusCode()).isEqualTo(400);

        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("frank", "oc:jobs:write", "finance"))) {
            client.initialize();
            McpSchema.CallToolResult started = client.callTool(new McpSchema.CallToolRequest("triggerDynamicJob",
                    Map.of("connector", "Fake_Repo", "start", "finance-folder", "chunkSize", 300, "minChunkSizeChars", 100)));
            assertThat(started.isError()).isNotEqualTo(Boolean.TRUE);
            awaitCrawl("finance-folder via " + ExecutionProvenance.SOURCE_MCP + " by frank");
            assertThat(CHUNKINGS.get("finance-folder by frank")).isEqualTo(new ChunkingSpec(300, 100, null, null));
        }
    }

    @Test
    void theMcpToolsUseTheTokensCaller() {
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("alice", "oc:jobs:write", "finance"))) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .contains("triggerDynamicJob", "getDynamicJobStatus", "listDynamicJobs", "stopDynamicJob",
                            "ingestDocument", "removeDeletedDocument");

            McpSchema.CallToolResult started = client.callTool(new McpSchema.CallToolRequest("triggerDynamicJob",
                    Map.of("connector", "Fake_Repo", "start", "finance-folder")));

            assertThat(started.isError()).isNotEqualTo(Boolean.TRUE);
            String jobId = field(((McpSchema.TextContent) started.content().get(0)).text(), "jobId");
            awaitCrawl("finance-folder via " + ExecutionProvenance.SOURCE_MCP + " by alice");
            McpSchema.CallToolResult status = client.callTool(new McpSchema.CallToolRequest("getDynamicJobStatus",
                    Map.of("jobId", jobId)));
            assertThat(((McpSchema.TextContent) status.content().get(0)).text()).contains("\"owner\":\"alice\"");
        }
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("dave", "oc:jobs:run", "finance"))) {
            client.initialize();
            McpSchema.CallToolResult ingested = client.callTool(new McpSchema.CallToolRequest("ingestDocument",
                    Map.of("connector", "Fake_Repo", "document", "finance-report")));
            assertThat(ingested.isError()).isNotEqualTo(Boolean.TRUE);
            awaitCrawl("finance-report via " + ExecutionProvenance.SOURCE_MCP + " by dave");
            McpSchema.CallToolResult removed = client.callTool(new McpSchema.CallToolRequest("removeDeletedDocument",
                    Map.of("connector", "Fake_Repo", "document", "deleted-mcp")));
            assertThat(((McpSchema.TextContent) removed.content().get(0)).text()).contains(DynamicJobService.DELETION_NOTE);
            awaitCrawl("fake://deleted-mcp via " + ExecutionProvenance.SOURCE_MCP + " by dave");
        }
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("carol", "oc:jobs:write"))) {
            client.initialize();
            McpSchema.CallToolResult refused = client.callTool(new McpSchema.CallToolRequest("triggerDynamicJob",
                    Map.of("connector", "Fake_Repo", "start", "finance-folder")));
            assertThat(refused.isError()).isTrue();
        }
    }
}
