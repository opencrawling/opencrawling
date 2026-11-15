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
package org.opencrawling.runtime.api.v1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.runtime.api.copilot.TemplateCopilotResponse;
import org.opencrawling.runtime.api.copilot.TemplateGenerationCopilot;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.opencrawling.runtime.reporting.JobReportService;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.opencrawling.runtime.service.VespaInsightsService;
import org.opencrawling.runtime.transport.InternalTransportManager;
import org.opencrawling.runtime.transport.TestGrpcResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Every request example in the API description is sent to its endpoint and must succeed, so the documentation shows
 * requests that work. Examples that reach an outside system (ManifoldCF) are checked to reach the call to it.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1FeaturesHttpTest.Features.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "opencrawling.discovery.enabled=true",
                "opencrawling.jobs.dynamic.enabled=true", "opencrawling.sidecars.enabled=true"})
class PlatformApiV1ExamplesHttpTest {

    @LocalServerPort int port;
    @Autowired InternalTransportManager transport;
    @Autowired VespaInsightsService vespa;
    @Autowired TemplateGenerationCopilot copilot;
    @Autowired JobReportService reports;
    @Autowired DynamicJobService dynamicJobs;
    @Autowired DiscoveryService discovery;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();
    private String jobId;

    @BeforeEach
    void setUp() throws Exception {
        http = new PlatformApiTestSupport.Http(port);
        when(transport.getSettings()).thenReturn(TransportSettingsDTO.defaultSettings());
        when(transport.testGrpcConnection(anyString(), any())).thenReturn(new TestGrpcResponseDTO("SUCCESS", true, true, 3, "ok"));
        when(vespa.runQuery(anyString(), anyString(), anyString(), anyString())).thenReturn(new VespaInsightsService.VespaQueryResult(List.of(), 0, false, "ok"));
        when(vespa.deployBundledSchema(anyString())).thenReturn(new VespaInsightsService.VespaDeployResult(true, "ok", "{}"));
        when(copilot.generate(any())).thenReturn(new TemplateCopilotResponse("# {{cm:title}}", Map.of()));
        when(reports.executeDuckDbQuery(anyString())).thenReturn(new JobReportService.QueryResult(List.of("status", "documents"), List.of()));
        when(dynamicJobs.start(any(), any(), any(), anyString(), any()))
                .thenReturn(new DynamicJobService.Accepted("dj-1", "exec-1", "/x", DynamicJobService.State.RUNNING, false));
        when(dynamicJobs.document(any(), any(), any(), anyString(), any()))
                .thenReturn(new DynamicJobService.Accepted("dj-2", "exec-2", "/x", DynamicJobService.State.RUNNING, false));
        when(dynamicJobs.reject(any(), anyString(), anyString())).thenReturn(null);
        when(discovery.dryRun(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new DiscoveryService.DryRunView("c", 1L, "exact", List.of(), 0L, false, null, null));
    }

    @AfterEach
    void cleanUp() {
        if (jobId != null) {
            http.send("DELETE", "/api/v1/jobs/" + jobId, null);
        }
        http.send("DELETE", "/api/v1/connectors/HR_Share?force=true", null);
    }

    private HttpResponse<String> send(String operationId, String method, String path, String... headers) {
        return http.send(method, path, ApiV1Documentation.EXAMPLES.get(operationId), headers);
    }

    @Test
    void everyDocumentedExampleIsAcceptedByItsEndpoint() {
        assertThat(ApiV1Documentation.EXAMPLES.keySet()).as("every example is exercised below").containsExactlyInAnyOrder(
                "createJob", "replaceJob", "patchJob", "createConnector", "replaceConnector", "checkConnectorDefinition",
                "queryExecutions", "replaceSystemSettings", "replaceTransportSettings", "testGrpcTransport",
                "planManifoldCfMigration", "applyManifoldCfMigration", "queryVespa", "deployVespaBundledSchema",
                "generateNarrativizationTemplate", "startDynamicJob", "ingestDynamicDocument", "rejectDynamicJob", "dryRunDiscovery");

        HttpResponse<String> created = send("createJob", "POST", "/api/v1/jobs");
        assertThat(created.statusCode()).as("createJob " + created.body()).isEqualTo(201);
        jobId = json.readTree(created.body()).get("id").asString();
        assertOk("replaceJob", send("replaceJob", "PUT", "/api/v1/jobs/" + jobId));
        assertOk("patchJob", send("patchJob", "PATCH", "/api/v1/jobs/" + jobId, "Content-Type", ApiV1.MERGE_PATCH_JSON));

        assertThat(send("createConnector", "POST", "/api/v1/connectors").statusCode()).as("createConnector").isEqualTo(201);
        assertOk("replaceConnector", send("replaceConnector", "PUT", "/api/v1/connectors/HR_Share"));
        assertOk("checkConnectorDefinition", send("checkConnectorDefinition", "POST", "/api/v1/connectors:check"));
        assertOk("queryExecutions", send("queryExecutions", "POST", "/api/v1/executions:query"));
        String settings = http.send("GET", "/api/v1/settings/system", null).body();
        try {
            assertOk("replaceSystemSettings", send("replaceSystemSettings", "PUT", "/api/v1/settings/system"));
        } finally {
            http.send("PUT", "/api/v1/settings/system", settings);
        }
        assertOk("replaceTransportSettings", send("replaceTransportSettings", "PUT", "/api/v1/settings/transport"));
        assertOk("testGrpcTransport", send("testGrpcTransport", "POST", "/api/v1/settings/transport:test-grpc"));
        assertOk("queryVespa", send("queryVespa", "POST", "/api/v1/integrations/vespa:query"));
        assertOk("deployVespaBundledSchema", send("deployVespaBundledSchema", "POST", "/api/v1/integrations/vespa:deploy-bundled"));
        assertOk("generateNarrativizationTemplate", send("generateNarrativizationTemplate", "POST", "/api/v1/narrativization:generate-template"));
        assertThat(send("startDynamicJob", "POST", "/api/v1/dynamic-jobs").statusCode()).as("startDynamicJob").isEqualTo(202);
        assertThat(send("ingestDynamicDocument", "POST", "/api/v1/dynamic-jobs:ingest-document").statusCode()).as("ingestDynamicDocument").isEqualTo(202);
        assertOk("rejectDynamicJob", send("rejectDynamicJob", "POST", "/api/v1/dynamic-jobs/dj-1:reject"));
        assertOk("dryRunDiscovery", send("dryRunDiscovery", "POST", "/api/v1/discovery/connectors/Alfresco_Content_Services:dry-run"));

        // The ManifoldCF examples name a host that does not exist here: they must pass validation and reach it.
        for (String operation : List.of("planManifoldCfMigration", "applyManifoldCfMigration")) {
            String action = operation.startsWith("plan") ? "plan" : "apply";
            HttpResponse<String> response = send(operation, "POST", "/api/v1/migrations/manifoldcf:" + action);
            assertThat(response.statusCode()).as(operation + " " + response.body()).isEqualTo(502);
            assertThat(response.body()).contains("problems/upstream-error");
        }
    }

    private static void assertOk(String operation, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(operation + " " + response.body()).isBetween(200, 299);
    }
}
