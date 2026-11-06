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
package org.opencrawling.runtime.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceDecision.Outcome;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.GovernanceUnavailableException;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The {@code http} hook against a real HTTP server. */
class HttpGovernanceHookTest {

    private static final String SECRET = "governance-shared-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final SecurityConfig FINANCE = new SecurityConfig(false,
            List.of(new PermissionRule("finance", "GROUP", "Finance", "read")));

    private record Reply(int status, String body) {}

    private record Received(String timestamp, String signature, byte[] body) {}

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private GovernanceProperties.Http settings;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/govern", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (received) {
                received.add(new Received(exchange.getRequestHeaders().getFirst("X-OpenCrawling-Timestamp"),
                        exchange.getRequestHeaders().getFirst("X-OpenCrawling-Signature"), body));
            }
            Reply reply = replies.isEmpty() ? new Reply(500, "") : replies.poll();
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.status() == 302) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/other");
            }
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        settings = new GovernanceProperties.Http();
        settings.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/govern");
        settings.setSecret(SECRET);
        settings.setTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpGovernanceHook hook() {
        return new HttpGovernanceHook(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);
    }

    private static GovernanceSubject subject() {
        return new GovernanceSubject("doc-1", "cmis://repo/doc-1", "Pay alice@example.com",
                Map.of("title", List.of("Invoice")), FINANCE);
    }

    @Test
    void theRequestIsSignedAndCarriesTheDocument() throws Exception {
        replies.add(new Reply(200, "{\"decision\":\"APPROVE\",\"audit\":{\"policy\":\"p-7\"}}"));

        GovernanceDecision decision = hook().evaluate(subject());

        assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(decision.audit()).containsEntry("policy", "p-7");
        Received request = received.getFirst();
        assertThat(request.timestamp()).isEqualTo(String.valueOf(NOW.getEpochSecond()));
        assertThat(request.signature()).isEqualTo(HttpGovernanceHook.sign(SECRET, request.timestamp(), request.body()));
        assertThat(request.signature()).isNotEqualTo(HttpGovernanceHook.sign(SECRET + "x", request.timestamp(), request.body()));
        JsonNode json = JsonMapper.builder().build().readTree(request.body());
        assertThat(json.get("documentId").asString()).isEqualTo("doc-1");
        assertThat(json.get("uri").asString()).isEqualTo("cmis://repo/doc-1");
        assertThat(json.get("text").asString()).isEqualTo("Pay alice@example.com");
        assertThat(json.get("metadata").get("title").get(0).asString()).isEqualTo("Invoice");
        assertThat(json.get("security").get("permissions").get(0).get("identity").asString()).isEqualTo("finance");
    }

    @Test
    void theSignatureIsTheDocumentedHmac() {
        // Computed independently: HMAC-SHA256(secret, "1700000000" + "." + body), lowercase hex.
        assertThat(HttpGovernanceHook.sign(SECRET, "1700000000", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("sha256=bae9e90772c024281be4f7fcfed75ffdfdc9832fb76c999ec743d7965600bc28");
    }

    @Test
    void sanitizeAndRejectAnswersAreRead() throws Exception {
        replies.add(new Reply(200, "{\"decision\":\"sanitize\",\"text\":\"Pay [masked]\",\"metadata\":{\"title\":[\"Inv\"]}}"));
        replies.add(new Reply(200, "{\"decision\":\"REJECT\",\"reason\":\"credentials\",\"later\":true}"));

        GovernanceDecision sanitized = hook().evaluate(subject());
        GovernanceDecision rejected = hook().evaluate(subject());

        assertThat(sanitized.outcome()).isEqualTo(Outcome.SANITIZE);
        assertThat(sanitized.text()).isEqualTo("Pay [masked]");
        assertThat(sanitized.metadata()).containsExactlyEntriesOf(Map.of("title", List.of("Inv")));
        assertThat(rejected.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(rejected.reason()).isEqualTo("credentials");
    }

    @Test
    void busyOrFailingServicesAreRetriedWithGrowingPauses() throws Exception {
        settings.setMaxAttempts(4);
        settings.setInitialBackoff(Duration.ofMillis(100));
        settings.setMaxBackoff(Duration.ofMillis(250));
        replies.add(new Reply(503, ""));
        replies.add(new Reply(429, ""));
        replies.add(new Reply(500, ""));
        replies.add(new Reply(200, "{\"decision\":\"APPROVE\"}"));

        assertThat(hook().evaluate(subject()).outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(received).hasSize(4);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250));
    }

    @Test
    void retriesAreBounded() {
        settings.setMaxAttempts(3);
        for (int i = 0; i < 5; i++) {
            replies.add(new Reply(503, ""));
        }

        assertThatThrownBy(() -> hook().evaluate(subject())).isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("503").hasMessageContaining("3 attempt");
        assertThat(received).hasSize(3);
    }

    @Test
    void otherStatusesAndRedirectsAreNotRetried() {
        replies.add(new Reply(401, ""));
        assertThatThrownBy(() -> hook().evaluate(subject())).isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("401");
        // The production client: a redirect is not followed, so the signed request goes nowhere else.
        replies.add(new Reply(302, ""));
        assertThatThrownBy(() -> new HttpGovernanceHook(settings).evaluate(subject()))
                .isInstanceOf(GovernanceUnavailableException.class).hasMessageContaining("302");
        assertThat(received).hasSize(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void answersThatAreNotValidMeanNoDecision() {
        for (String body : List.of("not json", "{}", "{\"decision\":\"MAYBE\"}", "{\"decision\":\"SANITIZE\"}", "[]")) {
            replies.add(new Reply(200, body));
            assertThatThrownBy(() -> hook().evaluate(subject())).as(body).isInstanceOf(GovernanceUnavailableException.class);
        }
    }

    @Test
    void anAnswerTooLargeIsNotRead() {
        settings.setMaxResponseBytes(64);
        replies.add(new Reply(200, "{\"decision\":\"APPROVE\",\"audit\":{\"x\":\"" + "y".repeat(100) + "\"}}"));

        assertThatThrownBy(() -> hook().evaluate(subject())).isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("larger than 64");
    }

    @Test
    void anUnreachableServiceIsRetriedThenNoDecision() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        settings.setUrl("http://127.0.0.1:" + closedPort + "/govern");
        settings.setMaxAttempts(2);

        assertThatThrownBy(() -> hook().evaluate(subject())).isInstanceOf(GovernanceUnavailableException.class)
                .hasMessageContaining("could not be reached").hasMessageContaining("2 attempt");
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void theSettingsAreChecked() {
        settings.setSecret("too-short");
        assertThatThrownBy(this::hook).hasMessageContaining("at least 32");
        settings.setSecret(SECRET);
        settings.setUrl(null);
        assertThatThrownBy(this::hook).hasMessageContaining("url is required");
        settings.setUrl("ftp://example.com/x");
        assertThatThrownBy(this::hook).hasMessageContaining("http or https");
        settings.setUrl("https://governance.example.com/check");
        settings.setMaxAttempts(0);
        assertThatThrownBy(this::hook).hasMessageContaining("max-attempts");
        settings.setMaxAttempts(1);
        assertThat(hook().toString()).isEqualTo("HttpGovernanceHook[governance.example.com]").doesNotContain(SECRET);
        assertThat(hook().name()).isEqualTo("http");
    }
}
