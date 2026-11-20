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
import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.ErrorLogsResponse;
import org.opencrawling.sdk.models.JobTraceResponse;
import org.opencrawling.sdk.models.ThroughputMetricsResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityClientTest {

    private static HttpServer server;
    private static OpenCrawlingClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/api/observability", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response = "";
            int status = 200;

            if (path.contains("/diagnose/")) {
                response = """
                    {
                      "jobId": "1",
                      "jobName": "Documentation Crawler",
                      "timestamp": "2026-07-27T10:00:00Z",
                      "status": "HEALTHY",
                      "summary": "Pipeline execution clean",
                      "rootCauseAnalysis": "No anomalies detected",
                      "stageTimingMillis": {"Scanning": 120},
                      "bottleneckInsights": [],
                      "recommendedActions": [],
                      "errorLogs": []
                    }
                    """;
            } else if (path.contains("/traces/")) {
                response = """
                    {
                      "jobId": "1",
                      "totalSpans": 5,
                      "totalDurationMillis": 1500,
                      "overallStatus": "COMPLETED",
                      "spans": [],
                      "stageDurationBreakdownMillis": {"Scanning": 200}
                    }
                    """;
            } else if (path.contains("/errors/")) {
                response = "{\"jobId\":\"1\",\"errorCount\":0,\"errors\":[]}";
            } else if (path.contains("/metrics")) {
                response = """
                    {
                      "connectorId": "FileSystem_Local",
                      "averageThroughputDocsPerSec": 45.5,
                      "p95LatencyMillis": 110.0,
                      "activeVirtualThreads": 64.0,
                      "rawMetrics": []
                    }
                    """;
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
    void testDiagnoseJob() {
        DiagnosticReport report = client.observability().diagnose("1");
        assertThat(report.jobId()).isEqualTo("1");
        assertThat(report.status()).isEqualTo("HEALTHY");
    }

    @Test
    void testGetTracesAndErrors() {
        JobTraceResponse traces = client.observability().getTraces("1");
        assertThat(traces.overallStatus()).isEqualTo("COMPLETED");

        ErrorLogsResponse errors = client.observability().getErrors("1", "24h");
        assertThat(errors.errorCount()).isEqualTo(0);
    }

    @Test
    void testGetMetrics() {
        ThroughputMetricsResponse metrics = client.observability().getMetrics("FileSystem_Local");
        assertThat(metrics.connectorId()).isEqualTo("FileSystem_Local");
        assertThat(metrics.averageThroughputDocsPerSec()).isEqualTo(45.5);
    }
}
