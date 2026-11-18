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
package org.opencrawling.zammad;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ZammadClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
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
    void tokenAuthAndOnBehalfOfHeadersAreSentAnd429IsRetried() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> onBehalfHeader = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();

        server.createContext("/api/v1/users/me", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            onBehalfHeader.set(exchange.getRequestHeaders().getFirst("X-On-Behalf-Of"));
            if (attempts.getAndIncrement() == 0) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"id\":2,\"email\":\"crawler@acme.com\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "token",
                "accessToken", "zammad-secret-token",
                "onBehalfOf", "audit@acme.com",
                "initialBackoffMs", "10"
        ));
        try (ZammadClient client = new ZammadClient(props)) {
            JsonNode me = client.getCurrentUser();
            assertThat(me.path("email").asText()).isEqualTo("crawler@acme.com");
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(authHeader.get()).isEqualTo("Token token=zammad-secret-token");
            assertThat(onBehalfHeader.get()).isEqualTo("audit@acme.com");
        }
    }

    @Test
    void searchTicketsSupportsAssetsEnvelopeAndAttachmentRespectsSizeLimit(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/tickets/search", exchange -> {
            String json = """
                    {
                      "tickets": [4821],
                      "tickets_count": 1,
                      "assets": {
                        "Ticket": {
                          "4821": {
                            "id": 4821,
                            "number": "9402817",
                            "title": "SAML rotation issue"
                          }
                        }
                      }
                    }
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/api/v1/ticket_attachment/4821/10/99", exchange -> {
            byte[] body = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        ZammadProperties props = ZammadProperties.fromConfiguration(Map.of(
                "baseUrl", baseUrl,
                "authType", "oauth2",
                "accessToken", "oauth-bearer-123"
        ));
        try (ZammadClient client = new ZammadClient(props)) {
            List<JsonNode> tickets = client.searchTicketsPage("updated_at:>=2026-01-01", 1, 50);
            assertThat(tickets).hasSize(1);
            assertThat(tickets.getFirst().path("number").asText()).isEqualTo("9402817");

            Path okFile = tempDir.resolve("att-ok.txt");
            long written = client.downloadAttachment(4821, 10, 99, okFile, 1024);
            assertThat(written).isEqualTo(16);
            assertThat(Files.readString(okFile)).isEqualTo("0123456789ABCDEF");

            Path tooBigFile = tempDir.resolve("att-big.txt");
            long rejected = client.downloadAttachment(4821, 10, 99, tooBigFile, 8);
            assertThat(rejected).isEqualTo(-1L);
            assertThat(Files.exists(tooBigFile)).isFalse();
        }
    }
}
