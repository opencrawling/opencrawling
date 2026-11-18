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
package org.opencrawling.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.ConnectorResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorClientTest {

    private static HttpServer server;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/connectors", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String response = "";
            int status = 200;

            if ("GET".equals(method)) {
                response = """
                    [
                      {
                        "name": "FileSystem_Local",
                        "description": "Local File System",
                        "type": "repository",
                        "className": "org.opencrawling.filesystem.FileConnector",
                        "maxConnections": 10,
                        "configuration": {}
                      }
                    ]
                    """;
            } else if ("POST".equals(method)) {
                status = 201;
            } else if ("DELETE".equals(method)) {
                status = 200;
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
    void testListConnectors() {
        List<ConnectorResponse> connectors = client.connectors().list("repository");
        assertThat(connectors).hasSize(1);
        assertThat(connectors.get(0).name()).isEqualTo("FileSystem_Local");
    }

    @Test
    void testCreateAndDeleteConnector() {
        ConnectorRequest req = ConnectorRequest.builder()
                .name("Custom_Output")
                .type("output")
                .className("org.opencrawling.CustomOutputConnector")
                .addConfiguration("key", "value")
                .build();

        client.connectors().create(req);
        client.connectors().delete("Custom_Output");
    }
}
