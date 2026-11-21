/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleDriveClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private KeyPair rsaKeyPair;
    private String serviceAccountJson;

    @BeforeEach
    void setUp() throws Exception {
        rsaKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String pkcs8Base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(rsaKeyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + pkcs8Base64 + "\n-----END PRIVATE KEY-----\n";
        serviceAccountJson = MAPPER.writeValueAsString(Map.of(
                "type", "service_account",
                "client_email", "crawler@opencrawling.iam.gserviceaccount.com",
                "private_key_id", "kid-test-1",
                "private_key", pem
        ));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void serviceAccountJwtSignsRs256WithoutDelegationAndExchangesToken(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> capturedAssertion = new AtomicReference<>();
        AtomicReference<String> capturedAuthHeader = new AtomicReference<>();

        server.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                String[] kv = pair.split("=", 2);
                if ("assertion".equals(kv[0])) {
                    capturedAssertion.set(URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
            respondJson(ex, 200, "{\"access_token\":\"sa-access-token\",\"expires_in\":3600}");
        });
        server.createContext("/drive/v3/about", ex -> {
            capturedAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            respondJson(ex, 200, "{\"user\":{\"emailAddress\":\"crawler@opencrawling.iam.gserviceaccount.com\"}}");
        });
        server.start();

        Path keyFile = tempDir.resolve("sa.json");
        Files.writeString(keyFile, serviceAccountJson);

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", baseUrl + "/drive/v3",
                "tokenUrl", baseUrl + "/token",
                "authType", "service_account",
                "serviceAccountJson", keyFile.toString()
        ));

        try (GoogleDriveClient client = new GoogleDriveClient(props)) {
            JsonNode about = client.getAbout();
            assertThat(about.path("user").path("emailAddress").asText())
                    .isEqualTo("crawler@opencrawling.iam.gserviceaccount.com");
        }

        assertThat(capturedAuthHeader.get()).isEqualTo("Bearer sa-access-token");
        String jwt = capturedAssertion.get();
        assertThat(jwt).isNotNull();
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("kid").asText()).isEqualTo("kid-test-1");
        assertThat(claims.path("iss").asText()).isEqualTo("crawler@opencrawling.iam.gserviceaccount.com");
        assertThat(claims.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/drive.readonly");
        assertThat(claims.path("aud").asText()).isEqualTo(baseUrl + "/token");
        // Domain-Wide Delegation is optional and omitted when delegatedUser is empty
        assertThat(claims.has("sub")).isFalse();

        // Cryptographically verify the RS256 signature against our generated RSA public key
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(rsaKeyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void serviceAccountIncludesSubClaimWhenDelegatedUserIsConfigured() throws Exception {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", baseUrl + "/drive/v3",
                "tokenUrl", baseUrl + "/token",
                "authType", "service_account",
                "serviceAccountJson", serviceAccountJson,
                "delegatedUser", "admin@example.com"
        ));
        try (GoogleDriveClient client = new GoogleDriveClient(props)) {
            var creds = client.loadServiceAccount();
            String jwt = client.buildSignedJwtAssertion(creds, baseUrl + "/token");
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
            assertThat(claims.path("sub").asText()).isEqualTo("admin@example.com");
        }
    }

    @Test
    void retriesOn429AndDrive403RateLimitAndRefreshesExpiredTokenOn401() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger aboutCalls = new AtomicInteger();

        server.createContext("/token", ex -> {
            int n = tokenCalls.incrementAndGet();
            respondJson(ex, 200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":3600}");
        });
        server.createContext("/drive/v3/about", ex -> {
            int n = aboutCalls.incrementAndGet();
            if (n == 1) {
                ex.getResponseHeaders().add("Retry-After", "0");
                respondJson(ex, 429, "{\"error\":{\"code\":429,\"message\":\"Rate limit\"}}");
            } else if (n == 2) {
                respondJson(ex, 403, "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}],\"message\":\"User rate limit\"}}");
            } else if (n == 3) {
                respondJson(ex, 401, "{\"error\":{\"code\":401,\"message\":\"Expired token\"}}");
            } else {
                respondJson(ex, 200, "{\"user\":{\"emailAddress\":\"ok@example.com\"}}");
            }
        });
        server.start();

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", baseUrl + "/drive/v3",
                "tokenUrl", baseUrl + "/token",
                "authType", "oauth2",
                "clientId", "cid",
                "clientSecret", "csec",
                "refreshToken", "rtok",
                "maxRetries", "4",
                "initialBackoffMs", "5"
        ));

        try (GoogleDriveClient client = new GoogleDriveClient(props)) {
            JsonNode about = client.getAbout();
            assertThat(about.path("user").path("emailAddress").asText()).isEqualTo("ok@example.com");
        }
        assertThat(tokenCalls.get()).isEqualTo(2); // initial + refreshed on 401
        assertThat(aboutCalls.get()).isEqualTo(4); // 429 -> 403 rate limit -> 401 -> 200
    }

    @Test
    void permanent403PermissionErrorIsNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/drive/v3/files/secret/permissions", ex -> {
            calls.incrementAndGet();
            respondJson(ex, 403,
                    "{\"error\":{\"errors\":[{\"reason\":\"insufficientFilePermissions\"}],\"message\":\"Forbidden\"}}");
        });
        server.start();

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", baseUrl + "/drive/v3",
                "authType", "bearer",
                "accessToken", "tok",
                "maxRetries", "3",
                "initialBackoffMs", "5"
        ));

        try (GoogleDriveClient client = new GoogleDriveClient(props)) {
            assertThatThrownBy(() -> client.listPermissions("secret"))
                    .isInstanceOf(GoogleDriveApiException.class)
                    .satisfies(ex -> {
                        GoogleDriveApiException api = (GoogleDriveApiException) ex;
                        assertThat(api.status()).isEqualTo(403);
                        assertThat(api.isRateLimited()).isFalse();
                    });
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void downloadEnforcesMaxBytesAndCleansUpTempFile(@TempDir Path tempDir) throws Exception {
        byte[] payload = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        server.createContext("/drive/v3/files/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, payload.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", baseUrl + "/drive/v3",
                "authType", "bearer",
                "accessToken", "tok"
        ));

        Path target = tempDir.resolve("download.bin");
        try (GoogleDriveClient client = new GoogleDriveClient(props)) {
            long written = client.downloadFile("big", target, 8L);
            assertThat(written).isEqualTo(-1L);
            assertThat(Files.exists(target)).isFalse();

            long full = client.downloadFile("big", target, 64L);
            assertThat(full).isEqualTo(payload.length);
            assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        }
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
