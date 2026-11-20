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
package org.opencrawling.sdk.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.v1.RetryPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OisIngestClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record RecordedRequest(String method, String rawPath, String authorization, String body) {}
    private record QueuedResponse(int status, String body) {}

    private HttpServer server;
    private String baseUrl;
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private final Deque<QueuedResponse> responses = new ArrayDeque<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/ingest/ois/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body));
            QueuedResponse next;
            synchronized (responses) {
                next = responses.isEmpty() ? new QueuedResponse(202, "") : responses.poll();
            }
            byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(next.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void upsertAndDeletePushValidOisPayloadsWithBearerToken() throws Exception {
        OpenCrawlingClient client = OpenCrawlingClient.builder()
                .baseUrl(baseUrl)
                .bearerToken("ois-secret-token")
                .build();

        OisDocument upsert = OisDocument.upsert(
                "custom://erp/invoices/INV-100",
                OisContent.markdown("# Invoice 100"),
                Map.of("department", "finance"),
                SecurityConfig.failClosed(List.of(
                        PermissionRule.allowGroup("finance-readers"),
                        PermissionRule.denyUser("contractor@example.com")
                )),
                OisSource.of("erp", "https://erp.example.com")
        );

        OisIngestResponse res1 = client.ingest().push("finance job", upsert);
        assertThat(res1.isAccepted()).isTrue();
        assertThat(res1.jobId()).isEqualTo("finance job");
        assertThat(res1.documentId()).isEqualTo("custom://erp/invoices/INV-100");
        assertThat(res1.action()).isEqualTo("UPSERT");

        RecordedRequest req1 = received.getFirst();
        assertThat(req1.method()).isEqualTo("POST");
        assertThat(req1.rawPath()).isEqualTo("/api/v1/ingest/ois/finance%20job");
        assertThat(req1.authorization()).isEqualTo("Bearer ois-secret-token");

        JsonNode json = JSON.readTree(req1.body());
        assertThat(json.get("id").asText()).isEqualTo("custom://erp/invoices/INV-100");
        assertThat(json.get("action").asText()).isEqualTo("UPSERT");
        assertThat(json.get("content").get("mimeType").asText()).isEqualTo("text/markdown");
        assertThat(json.get("content").get("text").asText()).isEqualTo("# Invoice 100");
        assertThat(json.get("security").get("evaluationPolicy").asText()).isEqualTo("FAIL_CLOSED");
        assertThat(json.get("security").get("permissions")).hasSize(2);
        assertThat(json.get("source").get("type").asText()).isEqualTo("erp");

        OisIngestResponse res2 = client.ingest().delete("1", "custom://erp/invoices/INV-100");
        assertThat(res2.action()).isEqualTo("DELETE");
        JsonNode deleteJson = JSON.readTree(received.get(1).body());
        assertThat(deleteJson.get("action").asText()).isEqualTo("DELETE");
        assertThat(deleteJson.has("content")).isFalse();
    }

    @Test
    void withTokenOverridesTheClientDefaultTokenAndRetriesTransient503() {
        synchronized (responses) {
            responses.add(new QueuedResponse(503, ""));
            responses.add(new QueuedResponse(202, ""));
        }

        OpenCrawlingClient client = OpenCrawlingClient.builder()
                .baseUrl(baseUrl)
                .bearerToken("management-jwt-token")
                .retryPolicy(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)))
                .build();

        OisIngestResponse response = client.ingest()
                .withToken("dedicated-ois-token")
                .upsert("1", "doc-1", OisContent.text("Hello"), Map.of(), SecurityConfig.none());

        assertThat(response.isAccepted()).isTrue();
        assertThat(received).hasSize(2);
        assertThat(received).extracting(RecordedRequest::authorization).containsOnly("Bearer dedicated-ois-token");
    }

    @Test
    void invalidArgumentsAndServerRefusalsFailFast() {
        assertThatThrownBy(() -> OisDocument.upsert(" ", OisContent.text("x"), Map.of(), SecurityConfig.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OisDocument.upsert("doc-1", null, Map.of(), SecurityConfig.none()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OisDocument.upsert("doc-1", OisContent.text("x"), Map.of(), null))
                .isInstanceOf(NullPointerException.class);

        synchronized (responses) {
            responses.add(new QueuedResponse(409, "the job runs in migration mode; OIS ingestion supports RAG jobs only"));
        }
        OpenCrawlingClient client = OpenCrawlingClient.builder().baseUrl(baseUrl).bearerToken("t").build();
        assertThatThrownBy(() -> client.ingest().delete("1", "doc-1"))
                .isInstanceOf(OpenCrawlingApiException.class)
                .satisfies(e -> assertThat(((OpenCrawlingApiException) e).getStatusCode()).isEqualTo(409));
    }
}
