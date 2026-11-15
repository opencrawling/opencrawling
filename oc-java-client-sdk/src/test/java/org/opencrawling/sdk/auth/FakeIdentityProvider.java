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
package org.opencrawling.sdk.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A token endpoint, discovery document, device endpoint and an API that accepts one token at a time, on a local
 * port. Token responses are scripted per test.
 */
final class FakeIdentityProvider implements AutoCloseable {

    record Request(String path, String authorization, Map<String, String> form) {}

    final HttpServer server;
    final String base;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Deque<String[]> tokenResponses = new ArrayDeque<>(); // {status, body}
    volatile String acceptedApiToken;
    volatile String discoveredIssuer;

    FakeIdentityProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        discoveredIssuer = base + "/realms/test";
        server.createContext("/realms/test/.well-known/openid-configuration", exchange -> reply(exchange, 200, """
                {"issuer":"%s","token_endpoint":"%s/token","device_authorization_endpoint":"%s/device",
                 "revocation_endpoint":"%s/revoke"}""".formatted(discoveredIssuer, base, base, base)));
        server.createContext("/token", exchange -> {
            record(exchange);
            String[] next;
            synchronized (tokenResponses) {
                next = tokenResponses.isEmpty() ? new String[] {"500", "{}"} : tokenResponses.poll();
            }
            reply(exchange, Integer.parseInt(next[0]), next[1]);
        });
        server.createContext("/device", exchange -> {
            record(exchange);
            reply(exchange, 200, """
                    {"device_code":"dc-1","user_code":"ABCD-EFGH","verification_uri":"%s/activate",
                     "verification_uri_complete":"%s/activate?user_code=ABCD-EFGH","expires_in":600,"interval":5}"""
                    .formatted(base, base));
        });
        server.createContext("/revoke", exchange -> {
            record(exchange);
            reply(exchange, 200, "");
        });
        server.createContext("/api/jobs", exchange -> {
            record(exchange);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            boolean ok = acceptedApiToken != null && ("Bearer " + acceptedApiToken).equals(auth);
            reply(exchange, ok ? 200 : 401, ok ? "[]" : "");
        });
        server.start();
    }

    void respondToken(int status, String body) {
        synchronized (tokenResponses) {
            tokenResponses.add(new String[] {String.valueOf(status), body});
        }
    }

    void respondAccessToken(String token, long expiresIn) {
        respondToken(200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}");
    }

    List<Request> requestsTo(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (!pair.isBlank()) {
                String[] kv = pair.split("=", 2);
                form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        requests.add(new Request(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), form));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
