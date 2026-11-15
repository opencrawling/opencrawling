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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opencrawling.runtime.security.IdentityTestApplication.*;

/**
 * The default ({@code none}) mode behaves as before Spring Security was on the classpath: no token needed anywhere,
 * no CSRF on POSTs, no added security headers, no generated user, and tools use the asserted identity.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = IdentityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.server.protocol=STREAMABLE")
class NoIdentityHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    RecordingBackend backend;

    @Test
    void toolsUseTheAssertedIdentityWithoutAToken() {
        backend.seen.clear();
        try (McpSyncClient client = mcpClient(port, null)) {
            client.initialize();
            client.callTool(new McpSchema.CallToolRequest("secureVectorSearch",
                    Map.of("query", "budget", "userPrincipal", "alice", "userRoles", "finance, legal")));
        }

        assertThat(backend.seen).containsExactly(new Seen("alice", List.of("finance", "legal")));
    }

    @Test
    void requestsPassWithoutTokensCsrfOrSecurityHeaders() throws Exception {
        HttpResponse<String> get = send(port, "GET", "/api/ping", null);
        HttpResponse<String> post = send(port, "POST", "/api/ping", null);
        HttpResponse<String> foreignToken = send(port, "POST", "/api/ping", "Bearer anything");

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(foreignToken.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("X-Frame-Options")).isEmpty();
        assertThat(get.headers().firstValue("Cache-Control")).isEmpty();
        assertThat(postInitialize(port, null).statusCode()).isEqualTo(200);
    }

    @Test
    void noGeneratedPasswordIsCreated(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain("generated security password");
    }
}
