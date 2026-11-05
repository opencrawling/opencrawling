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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.opencrawling.runtime.security.IdentityTestApplication.*;

/**
 * {@code jwt} mode over real HTTP: the MCP endpoint needs a valid token, and tools see the token's identity no
 * matter what the client puts in {@code userPrincipal}/{@code userRoles}. The REST API stays open by default.
 */
@SpringBootTest(classes = IdentityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=jwt",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class JwtIdentityHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    RecordingBackend backend;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
    }

    @BeforeEach
    void reset() {
        backend.seen.clear();
    }

    @Test
    void aToolCallSeesTheTokenIdentityNotTheArguments() {
        String token = validToken(Map.of(
                "sub", "alice-id",
                "preferred_username", "alice",
                "realm_access", Map.of("roles", List.of("finance"))));

        try (McpSyncClient client = mcpClient(port, token)) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("secureVectorSearch",
                    Map.of("query", "budget", "userPrincipal", "admin", "userRoles", "executives,hr")));
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            client.callTool(new McpSchema.CallToolRequest("listAccessibleSources", Map.of()));
        }

        assertThat(backend.seen).containsExactly(
                new Seen("alice", List.of("finance")),
                new Seen("alice", List.of("finance")));
    }

    @Test
    void theMcpEndpointRejectsRequestsWithoutAValidToken() throws Exception {
        Map<String, Object> claims = Map.of("preferred_username", "alice");

        HttpResponse<String> none = postInitialize(port, null);
        HttpResponse<String> wrongKey = postInitialize(port,
                "Bearer " + token(OTHER_KEY, claims, AUDIENCE, Instant.now().plusSeconds(300)));
        HttpResponse<String> expired = postInitialize(port,
                "Bearer " + token(TRUSTED_KEY, claims, AUDIENCE, Instant.now().minusSeconds(600)));
        HttpResponse<String> otherAudience = postInitialize(port,
                "Bearer " + token(TRUSTED_KEY, claims, "some-other-app", Instant.now().plusSeconds(300)));
        HttpResponse<String> notAJwt = postInitialize(port, "Bearer not-a-jwt");

        for (HttpResponse<String> response : List.of(none, wrongKey, expired, otherAudience, notAJwt)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("Bearer"));
        }
        assertThat(backend.seen).isEmpty();
    }

    @Test
    void theMcpClientCannotConnectWithoutAToken() {
        try (McpSyncClient client = mcpClient(port, null)) {
            assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
        assertThat(backend.seen).isEmpty();
    }

    @Test
    void aValidTokenIsAccepted() throws Exception {
        assertThat(postInitialize(port, "Bearer " + validToken(Map.of("preferred_username", "alice"))).statusCode())
                .isEqualTo(200);
    }

    @Test
    void theRestApiStaysOpenWithoutProtectApi() throws Exception {
        assertThat(send(port, "GET", "/api/ping", null).statusCode()).isEqualTo(200);
        assertThat(send(port, "POST", "/api/ping", null).statusCode()).isEqualTo(200);
        // A bearer token meant for another endpoint is not parsed as a JWT outside the MCP paths.
        assertThat(send(port, "POST", "/api/v1/ingest/ois/job-1", "Bearer s3cret").statusCode()).isEqualTo(200);
    }
}
