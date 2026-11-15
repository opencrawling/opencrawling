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
package org.opencrawling.sharepoint;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A mock Entra ID token endpoint and Microsoft Graph: routes are {@code "<METHOD> <decoded path and query>"}, answered from
 * the fixtures in {@code src/test/resources/sharepoint-mock-responses/} with {@code {{BASE}}} replaced by the server's URL.
 * Every request is recorded.
 */
final class MockGraph implements AutoCloseable {

    static final String SITE_ID = "contoso.sharepoint.com,7c0e5b0a-1111-4bd8-8f2b-2a8e9a349201,9a3f2c1d-2222-4e8a-9c0f-1a2b3c4d5e6f";
    static final String DELTA_START = "GET /v1.0/drives/b!lib1/root/delta?$top=100&$expand=listItem($expand=fields)";

    final MockWebServer server = new MockWebServer();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();

    MockGraph() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String key = request.getMethod() + " " + URLDecoder.decode(request.getPath().replace("+", "%2B"), StandardCharsets.UTF_8);
                requests.add(key);
                recorded.add(request);
                Function<RecordedRequest, MockResponse> route = routes.get(key);
                return route != null ? route.apply(request)
                        : new MockResponse().setResponseCode(404).setBody("{\"error\":{\"code\":\"itemNotFound\",\"message\":\"no route " + key + "\"}}");
            }
        });
        server.start();
    }

    String base() {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    String fixture(String name) {
        try (InputStream is = MockGraph.class.getResourceAsStream("/sharepoint-mock-responses/" + name)) {
            if (is == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("{{BASE}}", base());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void json(String key, String fixture) {
        routes.put(key, r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(fixture(fixture)));
    }

    void body(String key, String contentType, String body) {
        routes.put(key, r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(body));
    }

    /** Token, site, drives, the first delta enumeration, permissions and downloads: what a first delta crawl needs. */
    void standard() {
        json("POST /tenant-1/oauth2/v2.0/token", "token.json");
        json("GET /v1.0/sites/contoso.sharepoint.com:/sites/engineering", "site.json");
        json("GET /v1.0/sites/" + SITE_ID + "/drives", "drives.json");
        json(DELTA_START, "delta-1a.json");
        json("GET /v1.0/drives/b!lib1/root/delta?token=page2", "delta-1b.json");
        json("GET /v1.0/drives/b!lib1/items/i1/permissions", "permissions-i1.json");
        json("GET /v1.0/drives/b!lib1/items/i2/permissions", "permissions-empty.json");
        json("GET /v1.0/drives/b!lib1/items/i3/permissions", "permissions-empty.json");
        body("GET /download/i1?tempauth=signed", "application/octet-stream", "Architecture specification body");
        routes.put("GET /v1.0/drives/b!lib1/items/i2/content", r -> new MockResponse().setResponseCode(302)
                .setHeader("Location", base() + "/download/i2?tempauth=signed2"));
        body("GET /download/i2?tempauth=signed2", "text/plain", "Read me first.");
    }

    long count(String key) {
        return requests.stream().filter(key::equals).count();
    }

    RecordedRequest request(String key) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).equals(key)) {
                return recorded.get(i);
            }
        }
        throw new AssertionError("no request " + key + " in " + requests);
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
