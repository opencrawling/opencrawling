/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opencrawling.sdk.models.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end live integration test executing Java Client SDK calls against a real running
 * OpenCrawling Runtime REST API container. Enabled via OPENCRAWLING_LIVE_TEST=true.
 */
@EnabledIfEnvironmentVariable(named = "OPENCRAWLING_LIVE_TEST", matches = "true")
class LiveSystemIntegrationTest {

    @Test
    void testFullSdkAgainstLiveSystem() {
        String baseUrl = System.getenv().getOrDefault("OPENCRAWLING_BASE_URL", "http://localhost:8080");
        System.out.println("Running live SDK integration test against: " + baseUrl);

        try (OpenCrawlingClient client = OpenCrawlingClient.builder().baseUrl(baseUrl).build()) {
            // 1. System Health & Status
            SystemStatus status = client.system().getStatus();
            assertThat(status).isNotNull();
            System.out.println("Live System Status: " + status);

            SystemSettings settings = client.system().getSettings();
            assertThat(settings).isNotNull();
            System.out.println("Live System Settings: Provider=" + settings.embeddingProvider() + ", Model=" + settings.ollamaModel());

            List<String> logs = client.system().getLogs();
            assertThat(logs).isNotNull();

            // 2. Connectors
            List<ConnectorResponse> repoConnectors = client.connectors().list("repository");
            assertThat(repoConnectors).isNotNull();

            ConnectorRequest newConnector = ConnectorRequest.builder()
                    .name("Live_SDK_Test_Connector")
                    .description("Temporary SDK Integration Test Connector")
                    .type("repository")
                    .className("org.opencrawling.filesystem.FileConnector")
                    .build();
            client.connectors().create(newConnector);

            // 3. Jobs Management
            List<JobResponse> initialJobs = client.jobs().list();
            assertThat(initialJobs).isNotNull();

            JobRequest createReq = JobRequest.builder()
                    .name("SDK_Live_Test_Job")
                    .repositoryConnector("FileSystem_Local")
                    .outputConnector("PGVector_Output")
                    .path("/data")
                    .transformationConnector("Ollama_Embedding_Default")
                    .narrativization(NarrativizationConfig.builder()
                            .enabled(true)
                            .template("Live test template {{content}}")
                            .build())
                    .build();

            JobResponse createdJob = client.jobs().create(createReq);
            assertThat(createdJob).isNotNull();
            String jobId = createdJob.id();
            System.out.println("Created Live Job ID: " + jobId);

            Optional<JobResponse> fetchedJob = client.jobs().get(jobId);
            assertThat(fetchedJob).isPresent();

            // Control lifecycle: start, pause, stop
            client.jobs().start(jobId);
            client.jobs().pause(jobId);
            client.jobs().stop(jobId);

            // Clean up created test job & connector
            client.jobs().delete(jobId);
            client.connectors().delete("Live_SDK_Test_Connector");

            // 4. Copilot Template Generation
            CopilotRequest copilotReq = CopilotRequest.builder()
                    .connectorType("repository")
                    .addField("title", "STRING", "Document Title")
                    .addField("author", "STRING", "Author Name")
                    .build();
            CopilotResponse copilotResp = client.narrativization().generateTemplate(copilotReq);
            assertThat(copilotResp).isNotNull();
            assertThat(copilotResp.template()).isNotBlank();
            System.out.println("Live Copilot Generated Template: " + copilotResp.template());

            // 5. AIOps Diagnostics & Observability
            DiagnosticReport report = client.observability().diagnose(jobId != null ? jobId : "1");
            assertThat(report).isNotNull();
            System.out.println("Live AIOps Diagnostic Status: " + report.status());

            JobTraceResponse traces = client.observability().getTraces(jobId != null ? jobId : "1");
            assertThat(traces).isNotNull();

            ErrorLogsResponse errors = client.observability().getErrors(jobId != null ? jobId : "1", "all");
            assertThat(errors).isNotNull();

            ThroughputMetricsResponse metrics = client.observability().getMetrics("FileSystem_Local");
            assertThat(metrics).isNotNull();

            // 6. OIS Document Lifecycle Actions & Tombstone Deletes Integration Assertion
            DocumentPayload tombstonePayload = DocumentPayload.createDeleteTombstone(
                "sdk-live-tombstone-101",
                java.util.Map.of("type", "sdk", "instance", "live-decoupled-test")
            );
            assertThat(tombstonePayload.id()).isEqualTo("sdk-live-tombstone-101");
            assertThat(tombstonePayload.action()).isEqualTo(DocumentAction.DELETE);
            assertThat(tombstonePayload.content()).isNull();
            assertThat(tombstonePayload.metadata()).isNull();
            System.out.println("Live System SDK OIS Tombstone Payload Asserted: ID=" + tombstonePayload.id() + ", Action=" + tombstonePayload.action());
        }
    }
}
