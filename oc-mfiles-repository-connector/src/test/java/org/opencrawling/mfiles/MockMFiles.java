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
package org.opencrawling.mfiles;

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
 * A mock M-Files Web Service: routes are {@code "<METHOD> <path after /REST>"} (query included, URL-decoded), answered from
 * the fixtures in {@code src/test/resources/mfiles-mock-responses/}; every request is recorded.
 */
final class MockMFiles implements AutoCloseable {

    static final String BASE = "/REST";
    static final String VAULT = "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}";
    static final String VAULT_KEY = "C0B3B52E-6512-4BD8-8F2B-2A8E9A349201";

    final MockWebServer server = new MockWebServer();
    /** Every request as {@code "<METHOD> <decoded path after /REST>"}. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();

    MockMFiles() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String key = request.getMethod() + " " + decode(request.getPath().substring(BASE.length()));
                requests.add(key);
                recorded.add(request);
                Function<RecordedRequest, MockResponse> route = routes.get(key);
                if (route == null && key.startsWith("GET /objects?")) {
                    route = routes.get("GET /objects?*");
                }
                return route != null ? route.apply(request)
                        : new MockResponse().setResponseCode(404).setBody("{\"Message\":\"no route " + key + "\"}");
            }
        });
        server.start();
    }

    static String decode(String path) {
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    String url() {
        return server.url(BASE).toString();
    }

    static String fixture(String name) {
        try (InputStream is = MockMFiles.class.getResourceAsStream("/mfiles-mock-responses/" + name)) {
            if (is == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void json(String key, String fixture) {
        routes.put(key, r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(fixture(fixture)));
    }

    void body(String key, String contentType, String body) {
        routes.put(key, r -> new MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(body));
    }

    void status(String key, int status) {
        routes.put(key, r -> new MockResponse().setResponseCode(status));
    }

    /** Login, vault, structure and logout: what every scan needs. */
    void session() {
        json("POST /server/authenticationtokens", "auth.json");
        json("GET /session/vault", "vault.json");
        json("GET /structure/properties", "property-definitions.json");
        json("GET /structure/objecttypes", "object-types.json");
        status("DELETE /session", 204);
    }

    long count(String key) {
        return requests.stream().filter(key::equals).count();
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
