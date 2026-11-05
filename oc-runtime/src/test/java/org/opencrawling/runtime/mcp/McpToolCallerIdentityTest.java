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
package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.mcp.backend.VespaMcpSearchBackend;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.IdentityProperties;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.opencrawling.runtime.security.VerifiedCallerRequiredException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every MCP retrieval tool takes its caller from the {@link CallerResolver}: in {@code jwt} mode a forged
 * {@code userPrincipal}/{@code userRoles} never reaches a backend, and a call without a token reaches none.
 */
class McpToolCallerIdentityTest {

    private static final List<String> TOKEN_GROUPS = List.of("hr");

    private McpVectorSearchBackend backend;
    private McpVectorServer server;
    private VespaMcpSearchBackend vespaBackend;
    private VespaMcpVectorServer vespaServer;

    @BeforeEach
    void setUp() {
        backend = mock(McpVectorSearchBackend.class);
        server = new McpVectorServer(backend);
        vespaBackend = mock(VespaMcpSearchBackend.class);
        vespaServer = new VespaMcpVectorServer(vespaBackend);
        CallerResolver verified = CallerResolver.verified(new JwtCallerMapper(new IdentityProperties().getJwt()));
        server.setCallerResolver(verified);
        vespaServer.setCallerResolver(verified);
        clearInvocations(backend, vespaBackend); // the constructors log the backend type
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsBob() {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", "bob-id", "preferred_username", "bob", "realm_access", Map.of("roles", TOKEN_GROUPS)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void searchToolsUseTheTokenIdentity() {
        authenticateAsBob();

        server.secureVectorSearch("q", "admin", "finance,executives", 3, 0.0, null);
        server.openarch_search("q", "admin", "finance", 3, 0.0);

        verify(backend, times(2)).secureVectorSearch("q", "bob", TOKEN_GROUPS, 3, 0.0, null);
    }

    @Test
    void documentAndListingToolsUseTheTokenIdentity() {
        authenticateAsBob();

        server.getDocumentContent("file:///a", "admin", "finance", 1024);
        server.openarch_fetch_document("doc-1", "admin", "finance");
        server.listAccessibleSources("admin", "finance", null);

        verify(backend).getDocumentContent("file:///a", "bob", TOKEN_GROUPS, 1024);
        verify(backend).getDocumentContent("openarch://default/doc-1", "bob", TOKEN_GROUPS, null);
        verify(backend).listAccessibleSources("bob", TOKEN_GROUPS, null);
    }

    @Test
    void toolsRefuseACallWithoutATokenAndNeverReachTheBackend() {
        assertThatThrownBy(() -> server.secureVectorSearch("q", "alice", "finance", 3, 0.0, null))
                .isInstanceOf(VerifiedCallerRequiredException.class);
        assertThatThrownBy(() -> server.getDocumentContent("file:///a", "alice", "finance", null))
                .isInstanceOf(VerifiedCallerRequiredException.class);
        assertThatThrownBy(() -> server.listAccessibleSources("alice", "finance", null))
                .isInstanceOf(VerifiedCallerRequiredException.class);

        verifyNoInteractions(backend);
    }

    @Test
    void vespaToolsUseTheTokenIdentity() throws Exception {
        authenticateAsBob();
        when(vespaBackend.resolveDocumentType(any())).thenReturn("doc");
        when(vespaBackend.executeSearch(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(new VespaMcpSearchBackend.RawSearchResult(List.of(), 0, false, null));
        when(vespaBackend.getDocumentContent(anyString(), any(), any(), any()))
                .thenReturn(new McpVectorServer.DocumentDetailsResult("1", "file:///a", "text", Map.of(), "hr"));

        vespaServer.vespaSecureVectorSearch("q", "admin", "finance", 3, null, null);
        vespaServer.vespaGetDocumentContent("file:///a", "admin", "finance", null);
        vespaServer.vespaListAccessibleSources("admin", "finance", null);

        verify(vespaBackend).executeSearch(eq("doc"), eq("q"), eq("hybrid"), eq(3), eq("bob"), eq(TOKEN_GROUPS));
        verify(vespaBackend).readableHits(eq("bob"), eq(TOKEN_GROUPS), any());
        verify(vespaBackend).getDocumentContent("file:///a", "bob", TOKEN_GROUPS, null);
        verify(vespaBackend).listAccessibleSources("bob", TOKEN_GROUPS, null);
    }

    @Test
    void vespaToolsRefuseACallWithoutAToken() {
        assertThatThrownBy(() -> vespaServer.vespaSecureVectorSearch("q", "alice", "finance", 3, null, null))
                .isInstanceOf(VerifiedCallerRequiredException.class);
        assertThatThrownBy(() -> vespaServer.vespaGetDocumentContent("file:///a", "alice", "finance", null))
                .isInstanceOf(VerifiedCallerRequiredException.class);
        assertThatThrownBy(() -> vespaServer.vespaListAccessibleSources("alice", "finance", null))
                .isInstanceOf(VerifiedCallerRequiredException.class);

        verifyNoInteractions(vespaBackend);
    }

    @Test
    void withoutAResolverTheAssertedArgumentsAreUsedAsBefore() {
        McpVectorServer legacy = new McpVectorServer(backend);

        legacy.secureVectorSearch("q", "alice", " finance , legal ", 3, 0.0, null);

        verify(backend).secureVectorSearch("q", "alice", List.of("finance", "legal"), 3, 0.0, null);
    }
}
