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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.doxis.client.DoxisClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three CSB logins (password, session ticket, OIDC access token) and the OAuth2 client-credentials token source.
 */
class DoxisAuthTest {

    private static final String BASE = "/restws/publicws/rest/api/v1";
    private static final String JWT = "\"eyJhbGciOiJIUzI1NiJ9.e30.c2ln\"";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private DoxisProperties settings(Map<String, String> extra) {
        Map<String, String> config = new java.util.HashMap<>(Map.of("url", server.url(BASE).toString(), "customerName", "DX4",
                "repositoryId", "DB1", "role", "admins"));
        config.putAll(extra);
        return DoxisProperties.fromConfiguration(config);
    }

    private JsonNode loginBody(DoxisProperties settings, String expectedPath) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(JWT));
        server.enqueue(new MockResponse().setResponseCode(204));
        try (DoxisClient client = DoxisRepositoryConnector.newClient(settings, "OpenCrawling-Test", Duration.ofSeconds(5), 0)) {
            assertEquals("eyJhbGciOiJIUzI1NiJ9.e30.c2ln", client.login());
        }
        RecordedRequest login = server.takeRequest();
        assertEquals(BASE + expectedPath, login.getPath());
        assertEquals(BASE + "/logout", server.takeRequest().getPath());
        return mapper.readTree(login.getBody().readUtf8());
    }

    @Test
    void basicLogsInWithUserNameAndPassword() throws Exception {
        JsonNode body = loginBody(settings(Map.of("username", "crawler", "password", "secret")), "/login");

        assertEquals("crawler", body.path("userName").asText());
        assertEquals("secret", body.path("password").asText());
        assertEquals("admins", body.path("role").asText());
        assertEquals("DX4", body.path("customerName").asText());
    }

    @Test
    void ticketLogsInWithTheSessionTicket() throws Exception {
        JsonNode body = loginBody(settings(Map.of("authType", "ticket", "sessionTicket", "ticket-123")), "/loginBySessionTicket");

        assertEquals("ticket-123", body.path("sessionTicket").asText());
        assertTrue(body.path("createOwnSession").asBoolean());
        assertFalse(body.has("password"));
    }

    @Test
    void oauth2LogsInWithAGivenAccessToken() throws Exception {
        JsonNode body = loginBody(settings(Map.of("authType", "oauth2", "oauth2AccessToken", "eyJ.access")),
                "/loginOIDCWithAccessToken");

        assertEquals("eyJ.access", body.path("accessToken").asText());
        assertEquals("admins", body.path("roleName").asText());
    }

    @Test
    void oauth2FetchesATokenWithTheClientCredentialsGrant() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"eyJ.from-idp\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
        DoxisProperties settings = settings(Map.of("authType", "oauth2", "oauth2TokenUrl", server.url("/oauth/token").toString(),
                "oauth2ClientId", "opencrawling-client", "oauth2ClientSecret", "s e/cret", "oauth2Scope", "doxis.read"));
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(JWT));
        server.enqueue(new MockResponse().setResponseCode(204));

        try (DoxisClient client = DoxisRepositoryConnector.newClient(settings, "OpenCrawling-Test", Duration.ofSeconds(5), 0)) {
            client.login();
        }

        RecordedRequest token = server.takeRequest();
        assertEquals("/oauth/token", token.getPath());
        String form = token.getBody().readUtf8();
        assertTrue(form.contains("grant_type=client_credentials"), form);
        assertTrue(form.contains("client_id=opencrawling-client"), form);
        assertTrue(form.contains("client_secret=s+e%2Fcret"), form);
        assertTrue(form.contains("scope=doxis.read"), form);
        RecordedRequest login = server.takeRequest();
        assertEquals(BASE + "/loginOIDCWithAccessToken", login.getPath());
        assertEquals("eyJ.from-idp", mapper.readTree(login.getBody().readUtf8()).path("accessToken").asText());
    }

    @Test
    void theTokenIsCachedUntilShortlyBeforeItExpires() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"t1\",\"expires_in\":120}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"t2\",\"expires_in\":120}"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-06T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        DoxisOAuth2TokenSource source = new DoxisOAuth2TokenSource(server.url("/token").toString(), "c", "s", null,
                Duration.ofSeconds(5), HttpClient.newHttpClient(), clock);

        assertEquals("t1", source.get());
        now.set(now.get().plusSeconds(30));
        assertEquals("t1", source.get(), "cached");
        now.set(now.get().plusSeconds(40));
        assertEquals("t2", source.get(), "refreshed within 60 s of expiry");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void aFailingTokenEndpointIsReportedWithoutTheSecret() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid_client\"}"));
        DoxisOAuth2TokenSource source = new DoxisOAuth2TokenSource(server.url("/token").toString(), "c", "very-secret", null,
                Duration.ofSeconds(5));

        IllegalStateException e = assertThrows(IllegalStateException.class, source::get);
        assertTrue(e.getMessage().contains("HTTP 401"));
        assertFalse(e.getMessage().contains("very-secret"));
    }
}
