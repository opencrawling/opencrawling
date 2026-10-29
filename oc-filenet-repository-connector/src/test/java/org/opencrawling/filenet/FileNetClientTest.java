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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNetClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private FileNetProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("url", server.url("/content-services-graphql/graphql").toString(),
                "objectStore", "OS1", "username", "p8crawler", "password", "secret"));
        c.putAll(extra);
        return FileNetProperties.fromConfiguration(c);
    }

    private FileNetClient client(FileNetProperties settings, Supplier<String> bearer) {
        return new FileNetClient(settings, Duration.ofSeconds(5), 2, Duration.ofMillis(1),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), MAPPER, bearer);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void postsTheDocumentWithBasicAuthAndTheXsrfDoubleSubmit() throws Exception {
        server.enqueue(json("{\"data\":{\"_apiInfo\":{\"productVersion\":\"5.6.0\"},\"objectStore\":{\"symbolicName\":\"OS1\"}}}"));
        server.enqueue(json("{\"data\":{\"objectStore\":{\"symbolicName\":\"OS1\"}}}"));
        FileNetClient client = client(settings(Map.of()), null);
        JsonNode data = client.execute("connect", Map.of("os", "OS1"));
        client.execute("connect", Map.of("os", "OS1"));
        assertEquals("5.6.0", data.path("_apiInfo").path("productVersion").asText());
        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals("POST", first.getMethod());
        assertEquals("/content-services-graphql/graphql", first.getPath());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("p8crawler:secret".getBytes(StandardCharsets.UTF_8)),
                first.getHeader("Authorization"));
        String xsrf = first.getHeader("ECM-CS-XSRF-Token");
        assertTrue(xsrf != null && xsrf.matches("[0-9a-f-]{36}"), "a client-generated UUID");
        assertEquals("ECM-CS-XSRF-Token=" + xsrf, first.getHeader("Cookie"), "the same token as a cookie");
        assertEquals(xsrf, second.getHeader("ECM-CS-XSRF-Token"), "one token per client");
        JsonNode body = MAPPER.readTree(first.getBody().readUtf8());
        assertTrue(body.path("query").asText().contains("_apiInfo(repositoryIdentifier: $os)"));
        assertEquals("OS1", body.path("variables").path("os").asText());
    }

    @Test
    void documentsCarryTheSharedFragmentOnlyWhenSpread() {
        assertTrue(FileNetClient.document("documents").contains("fragment DocumentFields on Document"));
        assertTrue(FileNetClient.document("more-documents").contains("fragment DocumentFields on Document"));
        assertFalse(FileNetClient.document("rows").contains("fragment DocumentFields"));
    }

    @Test
    void graphQlErrorsInA200BecomeExceptions() {
        server.enqueue(json("{\"data\":{\"document\":null},\"errors\":[{\"message\":\"FNRCE0051E: E_OBJECT_NOT_FOUND: "
                + "The requested item was not found.\"}]}"));
        FileNetApiException e = assertThrows(FileNetApiException.class,
                () -> client(settings(Map.of()), null).execute("connect", Map.of("os", "OS1")));
        assertEquals(200, e.status());
        assertTrue(e.isGraphQl());
        assertTrue(e.isNotFound());
    }

    @Test
    void retriesThrottlingWithRetryAfterAndGivesUpOnAuthFailures() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("{\"data\":{\"objectStore\":{\"symbolicName\":\"OS1\"}}}"));
        client(settings(Map.of()), null).execute("connect", Map.of("os", "OS1"));
        assertEquals(3, server.getRequestCount());

        server.enqueue(new MockResponse().setResponseCode(401));
        FileNetApiException e = assertThrows(FileNetApiException.class,
                () -> client(settings(Map.of()), null).execute("connect", Map.of("os", "OS1")));
        assertEquals(401, e.status());
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void bearerAndLtpaAuthentication() throws Exception {
        server.enqueue(json("{\"data\":{}}"));
        server.enqueue(json("{\"data\":{}}"));
        client(settings(Map.of("authType", "bearer", "oauth2AccessToken", "tok")), () -> "tok").execute("connect", Map.of("os", "OS1"));
        client(settings(Map.of("authType", "ltpa", "ltpaToken", "ltpa-value")), null).execute("connect", Map.of("os", "OS1"));
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"));
        RecordedRequest ltpa = server.takeRequest();
        assertNull(ltpa.getHeader("Authorization"));
        assertTrue(ltpa.getHeader("Cookie").contains("LtpaToken2=ltpa-value"));
        assertTrue(ltpa.getHeader("Cookie").contains("ECM-CS-XSRF-Token="));
    }

    @Test
    void anOAuth2TokenIsRenewedOnceAfter401() throws Exception {
        MockWebServer idp = new MockWebServer();
        idp.start();
        try {
            for (int i = 0; i < 2; i++) {
                idp.enqueue(json("{\"access_token\":\"t" + i + "\",\"expires_in\":3600}"));
            }
            FileNetOAuth2TokenSource tokens = new FileNetOAuth2TokenSource(idp.url("/token").toString(), "c", "s", null,
                    Duration.ofSeconds(5));
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(json("{\"data\":{}}"));
            client(settings(Map.of("authType", "oauth2", "oauth2TokenUrl", "x", "oauth2ClientId", "c",
                    "oauth2ClientSecret", "s")), tokens).execute("connect", Map.of("os", "OS1"));
            assertEquals("Bearer t0", server.takeRequest().getHeader("Authorization"));
            assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"));
            assertEquals(2, idp.getRequestCount());
        } finally {
            idp.shutdown();
        }
    }

    @Test
    void downloadsRelativeUrlsWithTheSameHeaders() throws Exception {
        server.enqueue(new MockResponse().setBody("hello content"));
        FileNetClient client = client(settings(Map.of()), null);
        Optional<Path> file = client.download("/content?repositoryIdentifier=OS1&documentId=%7BA1%7D&elementSequenceNumber=0", 0);
        try {
            assertEquals("hello content", Files.readString(file.orElseThrow()));
            RecordedRequest r = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/content-services-graphql/content?repositoryIdentifier=OS1&documentId=%7BA1%7D&elementSequenceNumber=0",
                    r.getPath());
            assertEquals("GET", r.getMethod());
            assertTrue(r.getHeader("Authorization").startsWith("Basic "));
            assertEquals("ECM-CS-XSRF-Token=" + r.getHeader("ECM-CS-XSRF-Token"), r.getHeader("Cookie"));
        } finally {
            Files.deleteIfExists(file.orElseThrow());
        }
    }

    @Test
    void oversizedDownloadsAreAbandonedAndOffHostUrlsRefused() throws Exception {
        server.enqueue(new MockResponse().setBody("x".repeat(100)));
        assertTrue(client(settings(Map.of()), null).download("content?id=1", 10).isEmpty());
        FileNetClient client = client(settings(Map.of()), null);
        FileNetApiException e = assertThrows(FileNetApiException.class,
                () -> client.download("https://attacker.example.com/steal", 0));
        assertTrue(e.getMessage().contains("attacker.example.com"));
        assertEquals(1, server.getRequestCount(), "no request to another host");
        assertEquals(server.url("/content-services-graphql/content?a=1").uri(), client.resolve("content?a=1"));
        assertEquals(server.url("/content-services-graphql/content?a=1").uri(),
                client.resolve(server.url("/content-services-graphql/content?a=1").toString()));
    }

    @Test
    void anHttpErrorBodyIsAbbreviated() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom ".repeat(200)));
        FileNetApiException e = assertThrows(FileNetApiException.class,
                () -> client(settings(Map.of()), null).execute("connect", Map.of("os", "OS1")));
        assertEquals(500, e.status());
        assertFalse(e.isGraphQl());
        assertTrue(e.getMessage().length() < 450);
    }
}
