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
package org.opencrawling.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.discovery.DiscoveryMcpTools;
import org.opencrawling.runtime.discovery.DiscoveryProperties;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.dynamicjob.DynamicJobMcpTools;
import org.opencrawling.runtime.dynamicjob.DynamicJobProperties;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.sidecar.SidecarMcpTools;
import org.opencrawling.runtime.sidecar.SidecarProperties;
import org.opencrawling.runtime.sidecar.SidecarService;
import org.opencrawling.runtime.sidecar.SidecarStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Several agents at once over the real MCP protocol (Streamable HTTP), each with a real signed, delegated token
 * ({@code act}): an orchestrator's sub-agents acting for different users discover, read sidecars, search and start
 * jobs in parallel. Each sees only its user's view whatever identity it claims in tool arguments, every job records
 * the agent and the user, and each tool asks for its own scope.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, AgentSwarmHttpTest.Agentic.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.discovery.enabled=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.jobs.dynamic.connectors=Fake_Repo",
                "opencrawling.sidecars.enabled=true",
                "opencrawling.sidecars.connectors=Lake_S3",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class AgentSwarmHttpTest {

    private static final String AGENT = "research-orchestrator";
    private static final String ALL_SCOPES = "openid oc:jobs:write oc:jobs:run oc:sidecars:read";
    /** Each crawl: where it started, which agent started it, for whom. */
    static final List<String> CRAWLS = Collections.synchronizedList(new ArrayList<>());

    /** An in-memory bucket of sidecars. */
    static final class Bucket implements SidecarStore {
        final TreeMap<String, String> objects = new TreeMap<>();

        @Override
        public Page list(String prefix, String startAfter, int maxKeys) {
            List<String> keys = (startAfter == null ? objects.navigableKeySet() : objects.tailMap(startAfter, false).navigableKeySet())
                    .stream().filter(key -> key.startsWith(prefix)).limit(maxKeys).toList();
            return new Page(keys, false);
        }

        @Override
        public byte[] read(String key, int maxBytes) {
            String value = objects.get(key);
            return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }
    }

    static String sidecar(String id, String identity, String type) {
        return "{\"id\":\"" + id + "\",\"action\":\"UPSERT\",\"uri\":\"s3://lake/" + id + "\",\"contentRef\":{\"filename\":\""
                + id + ".pdf\",\"mimeType\":\"application/pdf\",\"contentLength\":10},\"metadata\":{\"title\":[\"" + id
                + "\"]},\"security\":{\"inheritanceEnabled\":false,\"permissions\":[{\"identity\":\"" + identity
                + "\",\"identityType\":\"" + type + "\",\"displayName\":\"x\",\"access\":\"read\"}]}}";
    }

    @Configuration
    @EnableConfigurationProperties({DiscoveryProperties.class, DynamicJobProperties.class, SidecarProperties.class})
    @Import({DiscoveryMcpTools.class, DynamicJobMcpTools.class, SidecarMcpTools.class})
    static class Agentic {
        static final ConnectorDTO FAKE = new ConnectorDTO("Fake_Repo", "A fake repository", "repository",
                FakeDiscoverableConnector.class.getName(), 1, Map.of());

        @Bean
        DiscoveryService discoveryService(DiscoveryProperties properties) {
            return new DiscoveryService(() -> List.of(FAKE), dto -> new FakeDiscoverableConnector(), properties);
        }

        @Bean
        DynamicJobService dynamicJobService(DynamicJobProperties properties) {
            DynamicJobService.CrawlRunner runner = (connector, start, jobId, jobName, executionId, stop, provenance, chunking) -> {
                CRAWLS.add(start + " by " + provenance.triggeredBy() + " for " + provenance.onBehalfOf());
                return true;
            };
            return new DynamicJobService(() -> List.of(FAKE), dto -> new FakeDiscoverableConnector(),
                    new JobRunRegistry(0), runner, executionId -> 1L, properties, Clock.systemUTC());
        }

        @Bean
        SidecarService sidecarService(SidecarProperties properties) {
            Bucket bucket = new Bucket();
            bucket.objects.put("migration/v1/finance-q3.pdf.ois.json", sidecar("finance-q3", "group:finance", "GROUP"));
            bucket.objects.put("migration/v1/alice-cv.pdf.ois.json", sidecar("alice-cv", "user:alice", "USER"));
            bucket.objects.put("migration/v1/hr-policy.pdf.ois.json", sidecar("hr-policy", "group:hr", "GROUP"));
            bucket.objects.put("migration/v1/handbook.pdf.ois.json", sidecar("handbook", "public", "PUBLIC"));
            ConnectorDTO lake = new ConnectorDTO("Lake_S3", "Migration lake", "output",
                    "org.opencrawling.s3.S3OutputConnector", 1, Map.of());
            return new SidecarService(properties, () -> List.of(lake), connector -> bucket,
                    DocumentAccessGate.metadataDefault(), Clock.systemUTC());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    IdentityTestApplication.RecordingBackend searches;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + IdentityTestApplication.PUBLIC_KEY_FILE.toAbsolutePath());
    }

    /** A token the orchestrator got by token exchange: the user's identity, the agent as actor. */
    private static String delegated(String user, String scope, String... groups) {
        return IdentityTestApplication.validToken(Map.of("preferred_username", user, "groups", List.of(groups),
                "scope", scope, "act", Map.of("sub", AGENT)));
    }

    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.isError()).as("tool error: %s", result.content()).isNotEqualTo(Boolean.TRUE);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    @Test
    void anAgentSeesEveryAgentToolOverTheProtocol() {
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, delegated("alice", ALL_SCOPES, "finance"))) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains(
                    "secureVectorSearch", "getDocumentContent", "listAccessibleSources",
                    "listRepositoryConnectors", "exploreRepositoryTree", "queryRepositoryDryRun",
                    "triggerDynamicJob", "getDynamicJobStatus", "listDynamicJobs", "stopDynamicJob",
                    "ingestDocument", "removeDeletedDocument",
                    "listSidecarStores", "readOisSidecars");
        }
    }

    @Test
    void subAgentsWorkingInParallelEachSeeOnlyTheirUsersView() throws Exception {
        int rounds = 12;
        searches.seen.clear();
        Callable<List<String>> forAlice = () -> session(delegated("alice", ALL_SCOPES, "finance"), "bob", "hr", rounds);
        Callable<List<String>> forBob = () -> session(delegated("bob", ALL_SCOPES, "hr"), "alice", "finance", rounds);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<String>>> alice = List.of(pool.submit(forAlice), pool.submit(forAlice));
            List<Future<List<String>>> bob = List.of(pool.submit(forBob), pool.submit(forBob));
            for (Future<List<String>> session : alice) {
                assertThat(session.get(60, TimeUnit.SECONDS)).allSatisfy(answer -> assertThat(answer)
                        .contains("finance-report", "alice-notes", "finance-q3", "alice-cv")
                        .doesNotContain("hr-policy"));
            }
            for (Future<List<String>> session : bob) {
                assertThat(session.get(60, TimeUnit.SECONDS)).allSatisfy(answer -> assertThat(answer)
                        .contains("public-guide", "hr-policy", "handbook")
                        .doesNotContain("finance-report", "alice-notes", "finance-q3", "alice-cv", "finance-but-not-bob"));
            }
        } finally {
            pool.shutdownNow();
        }
        // Every search reached the backend as the token's user, never as the identity named in the arguments.
        assertThat(searches.seen).hasSize(4 * rounds);
        assertThat(searches.seen).filteredOn(seen -> seen.principal().equals("alice"))
                .hasSize(2 * rounds).allSatisfy(seen -> assertThat(seen.groups()).containsExactly("finance"));
        assertThat(searches.seen).filteredOn(seen -> seen.principal().equals("bob"))
                .hasSize(2 * rounds).allSatisfy(seen -> assertThat(seen.groups()).containsExactly("hr"));
    }

    /** One sub-agent's session: each round explores, reads sidecars and searches, claiming to be someone else. */
    private List<String> session(String token, String forgedUser, String forgedGroup, int rounds) {
        List<String> answers = new ArrayList<>();
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, token)) {
            client.initialize();
            Map<String, Object> forged = Map.of("userPrincipal", forgedUser, "userRoles", forgedGroup);
            for (int round = 0; round < rounds; round++) {
                Map<String, Object> explore = new java.util.HashMap<>(forged);
                explore.put("connector", "Fake_Repo");
                Map<String, Object> sidecars = new java.util.HashMap<>(forged);
                sidecars.put("connector", "Lake_S3");
                Map<String, Object> search = new java.util.HashMap<>(forged);
                search.put("query", "quarterly figures");
                answers.add(text(call(client, "exploreRepositoryTree", explore))
                        + text(call(client, "readOisSidecars", sidecars)));
                text(call(client, "secureVectorSearch", search));
            }
        }
        return answers;
    }

    @Test
    void aDelegatedJobRecordsTheAgentAndTheUserAndStaysTheirs() {
        String jobId;
        try (McpSyncClient alice = IdentityTestApplication.mcpClient(port, delegated("alice", ALL_SCOPES, "finance"))) {
            alice.initialize();
            String started = text(call(alice, "triggerDynamicJob", Map.of("connector", "Fake_Repo", "start", "finance-folder")));
            int at = started.indexOf("\"jobId\":\"") + 9;
            jobId = started.substring(at, started.indexOf('"', at));
            awaitCrawl("finance-folder by " + AGENT + " for alice");
            assertThat(text(call(alice, "getDynamicJobStatus", Map.of("jobId", jobId)))).contains("\"owner\":\"alice\"");
        }
        try (McpSyncClient bob = IdentityTestApplication.mcpClient(port, delegated("bob", ALL_SCOPES, "hr"))) {
            bob.initialize();
            // The same orchestrator, acting for bob, cannot see alice's job or start on her folder.
            assertThat(call(bob, "getDynamicJobStatus", Map.of("jobId", jobId)).isError()).isTrue();
            assertThat(call(bob, "triggerDynamicJob", Map.of("connector", "Fake_Repo", "start", "finance-folder")).isError()).isTrue();
            assertThat(text(call(bob, "listDynamicJobs", Map.of()))).doesNotContain(jobId);
        }
    }

    @Test
    void eachToolAsksForItsOwnScope() {
        try (McpSyncClient reader = IdentityTestApplication.mcpClient(port, delegated("alice", "openid oc:sidecars:read", "finance"))) {
            reader.initialize();
            assertThat(text(call(reader, "readOisSidecars", Map.of("connector", "Lake_S3")))).contains("finance-q3");
            assertThat(call(reader, "triggerDynamicJob", Map.of("connector", "Fake_Repo")).isError()).isTrue();
            assertThat(call(reader, "ingestDocument", Map.of("connector", "Fake_Repo", "document", "finance-report")).isError()).isTrue();
        }
        try (McpSyncClient crawler = IdentityTestApplication.mcpClient(port, delegated("alice", "openid oc:jobs:write", "finance"))) {
            crawler.initialize();
            assertThat(call(crawler, "readOisSidecars", Map.of("connector", "Lake_S3")).isError()).isTrue();
            assertThat(text(call(crawler, "exploreRepositoryTree", Map.of("connector", "Fake_Repo")))).contains("finance-report");
        }
    }

    @Test
    void aTokenFromAnotherIssuerKeyOpensNoSession() {
        String forged = IdentityTestApplication.token(IdentityTestApplication.OTHER_KEY,
                Map.of("preferred_username", "alice", "groups", List.of("finance"), "scope", ALL_SCOPES),
                IdentityTestApplication.AUDIENCE, java.time.Instant.now().plusSeconds(300));
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, forged)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
    }

    private static void awaitCrawl(String crawl) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!CRAWLS.contains(crawl) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CRAWLS).contains(crawl);
    }
}
