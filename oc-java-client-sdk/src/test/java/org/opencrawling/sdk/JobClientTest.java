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
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;
import org.opencrawling.sdk.models.NarrativizationConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JobClientTest {

    private static HttpServer server;
    private static int port;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/jobs", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String response = "";
            int status = 200;

            if ("GET".equals(method)) {
                if ("/api/jobs/1".equals(path)) {
                    response = """
                        {
                          "id": "1",
                          "name": "Documentation Crawler",
                          "repositoryConnector": "FileSystem_Local",
                          "outputConnector": "PGVector_Output",
                          "authorityConnector": "",
                          "path": "/docs",
                          "status": "Ready",
                          "currentStage": "Idle",
                          "documents": 100,
                          "lastRun": "2026-07-27 10:00",
                          "transformationConnector": "Ollama_Embedding_Default",
                          "narrativization": {
                            "enabled": true,
                            "template": "{{content}}",
                            "connectorType": "repository"
                          }
                        }
                        """;
                } else if ("/api/jobs/999".equals(path)) {
                    status = 404;
                    response = "{\"error\": \"Job not found\"}";
                } else {
                    response = """
                        [
                          {
                            "id": "1",
                            "name": "Documentation Crawler",
                            "repositoryConnector": "FileSystem_Local",
                            "outputConnector": "PGVector_Output",
                            "authorityConnector": "",
                            "path": "/docs",
                            "status": "Ready",
                            "currentStage": "Idle",
                            "documents": 100,
                            "lastRun": "2026-07-27 10:00",
                            "transformationConnector": "Ollama_Embedding_Default",
                            "narrativization": {
                              "enabled": true,
                              "template": "{{content}}",
                              "connectorType": "repository"
                            }
                          }
                        ]
                        """;
                }
            } else if ("POST".equals(method)) {
                if (path.endsWith("/start") || path.endsWith("/pause") || path.endsWith("/stop")) {
                    status = 200;
                    response = "";
                } else {
                    status = 201;
                    response = "";
                }
            } else if ("DELETE".equals(method)) {
                status = 200;
                response = "";
            }

            exchange.sendResponseHeaders(status, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.start();

        client = OpenCrawlingClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testListJobs() {
        List<JobResponse> jobs = client.jobs().list();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).name()).isEqualTo("Documentation Crawler");
        assertThat(jobs.get(0).narrativization().enabled()).isTrue();
    }

    @Test
    void testGetJobFound() {
        Optional<JobResponse> jobOpt = client.jobs().get("1");
        assertThat(jobOpt).isPresent();
        JobResponse job = jobOpt.get();
        assertThat(job.id()).isEqualTo("1");
        assertThat(job.path()).isEqualTo("/docs");
    }

    @Test
    void testGetJobNotFound() {
        Optional<JobResponse> jobOpt = client.jobs().get("999");
        assertThat(jobOpt).isEmpty();
    }

    @Test
    void testCreateJob() {
        JobRequest request = JobRequest.builder()
                .name("New Crawler")
                .targetUrl("https://example.com/docs")
                .narrativization(NarrativizationConfig.builder()
                        .enabled(true)
                        .template("Sample template")
                        .build())
                .build();

        JobResponse response = client.jobs().create(request);
        assertThat(response).isNotNull();
    }

    @Test
    void testJobLifecycle() {
        client.jobs().start("1");
        client.jobs().pause("1");
        client.jobs().stop("1");
        client.jobs().delete("1");
    }
}
