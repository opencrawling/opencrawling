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
package org.opencrawling.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.CopilotRequest;
import org.opencrawling.sdk.models.CopilotResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativizationCopilotClientTest {

    private static HttpServer server;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/transformation/copilot/generate", exchange -> {
            String response = """
                {
                  "template": "Document titled {{title}} with summary {{summary}}",
                  "mockData": {
                    "title": "Engineering Handbook",
                    "summary": "Core architectural standards"
                  }
                }
                """;
            exchange.sendResponseHeaders(200, response.getBytes().length);
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
    void testGenerateTemplate() {
        CopilotRequest request = CopilotRequest.builder()
                .connectorType("repository")
                .addField("title", "string", "Document Title")
                .addField("summary", "string", "Executive Summary")
                .build();

        CopilotResponse response = client.narrativization().generateTemplate(request);
        assertThat(response.template()).contains("Document titled {{title}}");
        assertThat(response.mockData()).containsEntry("title", "Engineering Handbook");
    }
}
