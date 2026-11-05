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
package org.opencrawling.runtime.discovery;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discovery over real HTTP in {@code jwt} mode with {@code protect-api}: the REST endpoints and the MCP tools both
 * take the caller from the bearer token, ignore asserted identities, and show only what the source ACL lets that
 * caller read.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, DiscoveryHttpTest.Discovery.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.discovery.enabled=true",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class DiscoveryHttpTest {

    @Configuration
    @EnableConfigurationProperties(DiscoveryProperties.class)
    @Import({DiscoveryController.class, DiscoveryMcpTools.class})
    static class Discovery {
        @Bean
        DiscoveryService discoveryService(DiscoveryProperties properties) {
            ConnectorDTO fake = new ConnectorDTO("Fake_Repo", "A fake repository", "repository",
                    FakeDiscoverableConnector.class.getName(), 1, Map.of());
            return new DiscoveryService(() -> List.of(fake), dto -> new FakeDiscoverableConnector(), properties);
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
        return IdentityTestApplication.validToken(Map.of("preferred_username", user, "groups", List.of(groups)));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theTreeShowsWhatTheTokensCallerMayRead() throws Exception {
        HttpResponse<String> response = get("/api/discovery/Fake_Repo/tree", tokenFor("alice", "finance"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("finance-report", "alice-notes", "public-guide")
                .doesNotContain("unknown-acl", "security", "permissions");
    }

    @Test
    void assertedIdentityParametersAreIgnored() throws Exception {
        HttpResponse<String> response = get("/api/discovery/Fake_Repo/tree?userPrincipal=alice&userRoles=finance",
                tokenFor("carol"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("public-guide").doesNotContain("finance-report", "alice-notes");
    }

    @Test
    void requestsWithoutATokenAreRefused() throws Exception {
        assertThat(get("/api/discovery/connectors", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/discovery/Fake_Repo/tree", null).statusCode()).isEqualTo(401);
    }

    @Test
    void connectorsAndDryRunsWorkOverRest() throws Exception {
        String token = tokenFor("alice", "finance");

        assertThat(get("/api/discovery/connectors", token).body()).contains("Fake_Repo").doesNotContain("className");
        HttpResponse<String> dryRun = post("/api/discovery/Fake_Repo/dry-run", "{\"scope\":\"/x\",\"sampleSize\":2}", token);
        assertThat(dryRun.statusCode()).isEqualTo(200);
        assertThat(dryRun.body()).contains("\"matchCount\":5", "\"countAccuracy\":\"EXACT\"");
        assertThat(get("/api/discovery/Nope/tree", token).statusCode()).isEqualTo(404);
        assertThat(get("/api/discovery/Nope/tree", token).body()).contains("No repository connector 'Nope'.");
    }

    @Test
    void theMcpToolsUseTheTokensCallerNotTheArguments() {
        try (McpSyncClient client = IdentityTestApplication.mcpClient(port, tokenFor("carol"))) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .contains("listRepositoryConnectors", "exploreRepositoryTree", "queryRepositoryDryRun");

            McpSchema.CallToolResult tree = client.callTool(new McpSchema.CallToolRequest("exploreRepositoryTree",
                    Map.of("connector", "Fake_Repo", "userPrincipal", "alice", "userRoles", "finance")));

            assertThat(tree.isError()).isNotEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent) tree.content().get(0)).text();
            assertThat(text).contains("public-guide").doesNotContain("finance-report", "alice-notes");
        }
    }
}
