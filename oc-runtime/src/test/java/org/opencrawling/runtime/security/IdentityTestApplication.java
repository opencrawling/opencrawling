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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal web application for the HTTP-level identity tests: the real MCP server (Streamable HTTP), the real
 * security configuration and resource server, a backend that records the identity each tool call reaches it with,
 * and two stand-in REST endpoints. No database, Kafka or vector store.
 *
 * <p>{@code @TestComponent} keeps this class and its {@link Endpoints} out of component scanning: the runtime's
 * {@code @SpringBootApplication} scans {@code org.opencrawling}, so without it every {@code @SpringBootTest} of the
 * full application (the JUnit ITs) would also get the recording backend and the stand-in endpoints. Tests that want
 * this application name it in {@code @SpringBootTest(classes = ...)}.
 */
@TestComponent
@SpringBootConfiguration
@ImportAutoConfiguration({
        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
        org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
        org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
        org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration.class,
        org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration.class,
        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
        org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class,
        org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.class,
        org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class
})
@Import({IdentitySecurityConfiguration.class, McpVectorServer.class, IdentityTestApplication.Endpoints.class})
public class IdentityTestApplication {

    public static final String AUDIENCE = "opencrawling";
    static final KeyPair TRUSTED_KEY = newKeyPair();
    static final KeyPair OTHER_KEY = newKeyPair();
    public static final Path PUBLIC_KEY_FILE = writePublicKey(TRUSTED_KEY);

    /** The identity a tool call reached the backend with. */
    public record Seen(String principal, List<String> groups) {}

    @Bean
    RecordingBackend recordingBackend() {
        return new RecordingBackend();
    }

    public static class RecordingBackend implements McpVectorSearchBackend {
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        @Override
        public String backendType() {
            return "recording";
        }

        @Override
        public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(String query, String userPrincipal,
                List<String> userRoles, int maxResults, double minScore, Integer dimensions) {
            seen.add(new Seen(userPrincipal, new ArrayList<>(userRoles)));
            return List.of();
        }

        @Override
        public McpVectorServer.DocumentDetailsResult getDocumentContent(String documentUri, String userPrincipal,
                List<String> userRoles, Integer dimensions) {
            seen.add(new Seen(userPrincipal, new ArrayList<>(userRoles)));
            return new McpVectorServer.DocumentDetailsResult("1", documentUri, "text", Map.of(), "public");
        }

        @Override
        public List<Map<String, Object>> listAccessibleSources(String userPrincipal, List<String> userRoles,
                Integer dimensions) {
            seen.add(new Seen(userPrincipal, new ArrayList<>(userRoles)));
            return List.of();
        }
    }

    /**
     * Stand-ins for a REST endpoint and the OIS ingest endpoint, which has its own bearer token. Imported above, and
     * out of component scanning like the application itself, since it maps the real OIS ingest path.
     */
    @TestComponent
    @RestController
    public static class Endpoints {
        @GetMapping("/api/ping")
        public String getPing() {
            return "pong";
        }

        @PostMapping("/api/ping")
        public String postPing() {
            return "pong";
        }

        @PostMapping("/api/v1/ingest/ois/{jobId}")
        public String ois() {
            return "ingested";
        }
    }

    static String token(KeyPair key, Map<String, Object> claims, String audience, Instant expiresAt) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer("https://idp.test/realms/corp")
                    .audience(audience)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiresAt));
            claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), builder.build());
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String validToken(Map<String, Object> claims) {
        return token(TRUSTED_KEY, claims, AUDIENCE, Instant.now().plusSeconds(300));
    }

    public static McpSyncClient mcpClient(int port, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(request)
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** A bare MCP initialize request, to see the HTTP status the endpoint answers with. */
    static HttpResponse<String> postInitialize(int port, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                        "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(int port, String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, "POST".equals(method)
                        ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKey(KeyPair key) {
        try {
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Path file = Files.createTempFile("oc-identity-test", ".pem");
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
