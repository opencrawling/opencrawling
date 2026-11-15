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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.discovery.DiscoveryApiException;
import org.opencrawling.runtime.discovery.DiscoveryController;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.opencrawling.runtime.dynamicjob.DynamicJobController;
import org.opencrawling.runtime.dynamicjob.DynamicJobException;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.opencrawling.runtime.sidecar.SidecarController;
import org.opencrawling.runtime.sidecar.SidecarException;
import org.opencrawling.runtime.sidecar.SidecarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The optional features over HTTP, switched on: discovery, dynamic jobs and sidecars. */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1FeaturesHttpTest.Features.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "opencrawling.discovery.enabled=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.sidecars.enabled=true"
        })
class PlatformApiV1FeaturesHttpTest {

    @Configuration
    @Import({DynamicJobController.class, DiscoveryController.class, SidecarController.class,
            DynamicJobsV1Controller.class, DiscoveryV1Controller.class, SidecarsV1Controller.class})
    static class Features {
        @Bean
        DynamicJobService dynamicJobService() {
            return mock(DynamicJobService.class);
        }

        @Bean
        DiscoveryService discoveryService() {
            return mock(DiscoveryService.class);
        }

        @Bean
        SidecarService sidecarService() {
            return mock(SidecarService.class);
        }
    }

    @LocalServerPort
    int port;

    @Autowired DynamicJobService dynamicJobs;
    @Autowired DiscoveryService discovery;
    @Autowired SidecarService sidecars;

    private PlatformApiTestSupport.Http http;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void client() {
        http = new PlatformApiTestSupport.Http(port);
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private static DynamicJobService.JobStatus status(String id, DynamicJobService.State state) {
        return new DynamicJobService.JobStatus(id, "exec-" + id, "Contracts", "Alfresco_Content_Services", DynamicJobService.Kind.FOLDER,
                null, state, false, java.time.Instant.parse("2026-11-15T10:00:00Z"), null, null, "agent-7", "dynamic-api", null, null, null);
    }

    @Test
    void theIndexAnnouncesTheEnabledFeatures() {
        JsonNode index = body(http.send("GET", "/api/v1", null));
        assertThat(index.get("features").get("dynamicJobs").asBoolean()).isTrue();
        assertThat(index.get("features").get("discovery").asBoolean()).isTrue();
        assertThat(index.get("features").get("sidecars").asBoolean()).isTrue();
        assertThat(index.get("links").get("dynamicJobs").asString()).isEqualTo("/api/v1/dynamic-jobs");
    }

    @Test
    void dynamicJobsAreStartedFollowedAndDecidedOn() {
        when(dynamicJobs.start(any(), any(), eq("dj-key"), anyString(), eq("corr-9")))
                .thenReturn(new DynamicJobService.Accepted("dj-1", "exec-dj-1", "/api/jobs/dynamic/dj-1", DynamicJobService.State.RUNNING, false));
        HttpResponse<String> started = http.send("POST", "/api/v1/dynamic-jobs",
                "{\"connector\":\"Alfresco_Content_Services\",\"start\":\"/Sites/legal\"}", "Idempotency-Key", "dj-key", "X-Correlation-Id", "corr-9");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        assertThat(started.headers().firstValue("Location")).hasValue("/api/v1/dynamic-jobs/dj-1");
        assertThat(body(started).get("jobId").asString()).isEqualTo("dj-1");
        assertThat(body(started).get("statusUrl").asString()).as("the v1 resource, not the legacy path")
                .isEqualTo("/api/v1/dynamic-jobs/dj-1");

        when(dynamicJobs.list(any())).thenReturn(List.of(status("dj-1", DynamicJobService.State.RUNNING), status("dj-2", DynamicJobService.State.COMPLETED)));
        JsonNode page = body(http.send("GET", "/api/v1/dynamic-jobs?limit=1", null));
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("page").get("totalItems").asInt()).isEqualTo(2);

        when(dynamicJobs.status(any(), eq("dj-1"))).thenReturn(status("dj-1", DynamicJobService.State.RUNNING));
        assertThat(body(http.send("GET", "/api/v1/dynamic-jobs/dj-1", null)).get("state").asString()).isEqualTo("RUNNING");
        when(dynamicJobs.stop(any(), eq("dj-1"))).thenReturn(status("dj-1", DynamicJobService.State.STOPPED));
        assertThat(http.send("POST", "/api/v1/dynamic-jobs/dj-1:stop", null).statusCode()).isEqualTo(202);
        when(dynamicJobs.approve(any(), eq("dj-3"))).thenReturn(status("dj-3", DynamicJobService.State.RUNNING));
        assertThat(body(http.send("POST", "/api/v1/dynamic-jobs/dj-3:approve", null)).get("state").asString()).isEqualTo("RUNNING");
        when(dynamicJobs.reject(any(), eq("dj-4"), eq("out of scope"))).thenReturn(status("dj-4", DynamicJobService.State.REJECTED));
        assertThat(body(http.send("POST", "/api/v1/dynamic-jobs/dj-4:reject", "{\"reason\":\"out of scope\"}")).get("state").asString())
                .isEqualTo("REJECTED");

        when(dynamicJobs.status(any(), eq("dj-x"))).thenThrow(new DynamicJobException(HttpStatus.NOT_FOUND, "No dynamic job dj-x."));
        HttpResponse<String> missing = http.send("GET", "/api/v1/dynamic-jobs/dj-x", null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("#problem-not-found", "No dynamic job dj-x.");
    }

    @Test
    void aSingleDocumentIsIngestedThroughTheCustomMethod() {
        when(dynamicJobs.document(any(), any(), isNull(), anyString(), isNull()))
                .thenReturn(new DynamicJobService.Accepted("dj-5", "exec-dj-5", "/api/jobs/dynamic/dj-5", DynamicJobService.State.RUNNING, false));
        HttpResponse<String> accepted = http.send("POST", "/api/v1/dynamic-jobs:ingest-document",
                "{\"connector\":\"Alfresco_Content_Services\",\"document\":\"workspace://SpacesStore/abc\",\"action\":\"UPSERT\"}");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertThat(accepted.headers().firstValue("Location")).hasValue("/api/v1/dynamic-jobs/dj-5");
    }

    @Test
    void discoveryBrowsesWithTheParametersSent() {
        when(discovery.listConnectors(any())).thenReturn(List.of(new DiscoveryService.ConnectorInfo("Alfresco_Content_Services", "Alfresco")));
        assertThat(body(http.send("GET", "/api/v1/discovery/connectors", null)).get(0).get("name").asString()).isEqualTo("Alfresco_Content_Services");

        when(discovery.explore(any(), eq("Alfresco_Content_Services"), eq("/Sites"), eq(2), eq(50), eq(true)))
                .thenReturn(new DiscoveryService.TreeView("Alfresco_Content_Services", List.of(), 3L, false, null));
        JsonNode tree = body(http.send("GET", "/api/v1/discovery/connectors/Alfresco_Content_Services/tree?start=/Sites&depth=2&maxNodes=50&includeDocuments=true", null));
        assertThat(tree.get("hiddenCount").asLong()).isEqualTo(3);

        when(discovery.dryRun(any(), eq("Alfresco_Content_Services"), eq("/Sites/legal"), eq(5), eq(1000)))
                .thenReturn(new DiscoveryService.DryRunView("Alfresco_Content_Services", 120L, "exact", List.of(), 0L, false, null, null));
        assertThat(body(http.send("POST", "/api/v1/discovery/connectors/Alfresco_Content_Services:dry-run",
                "{\"scope\":\"/Sites/legal\",\"sampleSize\":5,\"maxScan\":1000}")).get("matchCount").asLong()).isEqualTo(120);

        when(discovery.listConnectors(any())).thenThrow(new DiscoveryApiException(HttpStatus.UNAUTHORIZED, "Discovery needs a verified caller."));
        HttpResponse<String> refused = http.send("GET", "/api/v1/discovery/connectors", null);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).contains("Discovery needs a verified caller.");
    }

    @Test
    void sidecarsAreReadWithPrefixLimitAndCursor() {
        when(sidecars.listStores(any())).thenReturn(List.of(new SidecarService.StoreInfo("Ozone_Migration_Output", "Ozone", "ozone")));
        assertThat(body(http.send("GET", "/api/v1/sidecar-stores", null)).get(0).get("kind").asString()).isEqualTo("ozone");

        when(sidecars.read(any(), eq("Ozone_Migration_Output"), eq("hr/"), eq(10), eq("c-1")))
                .thenReturn(new SidecarService.SidecarPage("Ozone_Migration_Output", "hr/", List.of(), null, "c-2", false));
        assertThat(body(http.send("GET", "/api/v1/sidecar-stores/Ozone_Migration_Output/sidecars?prefix=hr/&limit=10&cursor=c-1", null))
                .get("nextCursor").asString()).isEqualTo("c-2");
        verify(sidecars).read(any(), eq("Ozone_Migration_Output"), eq("hr/"), eq(10), eq("c-1"));

        when(sidecars.read(any(), eq("Nope"), any(), any(), any())).thenThrow(new SidecarException(HttpStatus.NOT_FOUND, "No sidecar store Nope."));
        assertThat(http.send("GET", "/api/v1/sidecar-stores/Nope/sidecars", null).statusCode()).isEqualTo(404);
    }

    @Test
    void theOptionalFeaturesAreDescribedAtTheirPaths() {
        java.util.Map<String, String> expected = new java.util.HashMap<>(PlatformApiTestSupport.CORE_OPERATIONS);
        expected.putAll(PlatformApiTestSupport.FEATURE_OPERATIONS);
        assertThat(PlatformApiTestSupport.operations(body(http.send("GET", "/v3/api-docs/v1", null))))
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void curlSamplesWithAQuoteInTheirBodyStillRunInAShell() {
        String reject = body(http.send("GET", "/v3/api-docs/v1", null)).get("paths").get("/api/v1/dynamic-jobs/{jobId}:reject")
                .get("post").get("x-codeSamples").get(0).get("source").asString();
        assertThat(reject).contains("team'\\''s").doesNotContain("team's");
    }
}
