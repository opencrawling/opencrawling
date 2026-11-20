/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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
package org.opencrawling.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.SystemSettings;
import org.opencrawling.sdk.models.SystemStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemClientTest {

    private static HttpServer server;
    private static final java.util.concurrent.atomic.AtomicReference<String> POSTED = new java.util.concurrent.atomic.AtomicReference<>();
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/system", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String response = "";
            int status = 200;

            if (path.endsWith("/status")) {
                response = "{\"postgres\":\"UP\",\"redis\":\"UP\",\"ollama\":\"UP\",\"system\":\"HEALTHY\"}";
            } else if (path.endsWith("/throughput")) {
                response = "[{\"name\":\"08:00\",\"docs\":1200}]";
            } else if (path.endsWith("/logs")) {
                response = "[\"[INFO] System online\"]";
            } else if (path.endsWith("/settings")) {
                if ("GET".equals(method)) {
                    response = """
                        {
                          "embeddingProvider": "Ollama",
                          "ollamaBaseUrl": "http://127.0.0.1:11434",
                          "ollamaModel": "mxbai-embed-large",
                          "vectorDimensions": 1024,
                          "chunkerType": "TokenTextSplitter",
                          "chunkSize": 800,
                          "chunkOverlap": 100,
                          "tikaEngine": "PipesForkParser (Process-Isolated)",
                          "tikaTimeoutMs": 45000,
                          "claimCheckStore": "ozone"
                        }
                        """;
                } else {
                    POSTED.set(new String(exchange.getRequestBody().readAllBytes()));
                    status = 200;
                    response = "";
                }
            }

            exchange.sendResponseHeaders(status, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.start();
        client = OpenCrawlingClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testGetSystemStatus() {
        SystemStatus status = client.system().getStatus();
        assertThat(status.system()).isEqualTo("HEALTHY");
        assertThat(status.postgres()).isEqualTo("UP");
    }

    @Test
    void testGetThroughputAndLogs() {
        var throughput = client.system().getThroughput();
        assertThat(throughput).hasSize(1);

        List<String> logs = client.system().getLogs();
        assertThat(logs).hasSize(1);
    }

    @Test
    void testSettings() {
        SystemSettings settings = client.system().getSettings();
        assertThat(settings.ollamaModel()).isEqualTo("mxbai-embed-large");

        client.system().updateSettings(SystemSettings.builder().vectorDimensions(1536).build());
        String posted = POSTED.get();
        assertThat(posted).as("the model's fields are changed").contains("\"vectorDimensions\":1536");
        assertThat(posted).as("settings the model does not have are kept, not reset")
                .contains("\"tikaEngine\":\"PipesForkParser (Process-Isolated)\"", "\"tikaTimeoutMs\":45000",
                        "\"claimCheckStore\":\"ozone\"");
    }
}
