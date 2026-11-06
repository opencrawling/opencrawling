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
package org.opencrawling.runtime.dynamicjob;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dynamic jobs exist only when enabled, their MCP tools only with the MCP server, and they crawl to Kafka only. */
class DynamicJobConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ConnectorFactory.class, () -> mock(ConnectorFactory.class))
            .withBean(JobOrchestrator.class, () -> mock(JobOrchestrator.class))
            .withBean(JobRunRegistry.class, () -> new JobRunRegistry(0))
            .withUserConfiguration(DynamicJobConfiguration.class, DynamicJobController.class, DynamicJobMcpTools.class,
                    BpmEventConfiguration.class, BpmEventController.class);

    @Test
    void dynamicJobsAreOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DynamicJobService.class);
            assertThat(context).doesNotHaveBean(DynamicJobController.class);
            assertThat(context).doesNotHaveBean(DynamicJobMcpTools.class);
            assertThat(context).doesNotHaveBean(BpmEventService.class);
            assertThat(context).doesNotHaveBean(BpmEventController.class);
        });
    }

    @Test
    void bpmEventsAloneBringTheServiceButNotTheAgentEndpoints() {
        runner.withPropertyValues("opencrawling.events.bpm.enabled=true",
                "opencrawling.events.bpm.sources[0].name=aps",
                "opencrawling.events.bpm.sources[0].token=aps-static-token-0123456789abcdefghijklmn").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BpmEventService.class);
            assertThat(context).hasSingleBean(BpmEventController.class);
            assertThat(context).hasSingleBean(DynamicJobService.class);
            assertThat(context).doesNotHaveBean(DynamicJobController.class);
            assertThat(context).doesNotHaveBean(DynamicJobMcpTools.class);
            assertThat(context.getBean(BpmEventProperties.class).getRequiredScope()).isEqualTo("oc:events:write");
        });
    }

    @Test
    void aBpmSourceWithAShortSecretStopsStartup() {
        runner.withPropertyValues("opencrawling.events.bpm.enabled=true",
                "opencrawling.events.bpm.sources[0].name=aps", "opencrawling.events.bpm.sources[0].token=short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enablingThemAddsTheServiceTheEndpointsAndTheTools() {
        runner.withPropertyValues("opencrawling.jobs.dynamic.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DynamicJobService.class);
            assertThat(context).hasSingleBean(DynamicJobController.class);
            assertThat(context).hasSingleBean(DynamicJobMcpTools.class);
        });
        runner.withPropertyValues("opencrawling.jobs.dynamic.enabled=true", "opencrawling.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamicJobController.class);
                    assertThat(context).doesNotHaveBean(DynamicJobMcpTools.class);
                });
    }

    @Test
    void settingsBindWithSafeDefaults() {
        runner.withPropertyValues("opencrawling.jobs.dynamic.enabled=true").run(context -> {
            DynamicJobProperties defaults = context.getBean(DynamicJobProperties.class);
            assertThat(defaults.getConnectors()).isEmpty();
            assertThat(defaults.getRequiredScope()).isEqualTo("oc:jobs:write");
            assertThat(defaults.getDocumentScope()).isEqualTo("oc:jobs:run");
            assertThat(defaults.getAdminGroups()).isEmpty();
            assertThat(defaults.getMaxRunningPerUser()).isEqualTo(2);
        });
        runner.withPropertyValues("opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.jobs.dynamic.connectors=Alfresco_Main,CMIS_Archive",
                "opencrawling.jobs.dynamic.required-scope=ingest",
                "opencrawling.jobs.dynamic.document-scope=ingest-one",
                "opencrawling.jobs.dynamic.admin-groups=oc-admins",
                "opencrawling.jobs.dynamic.max-running-per-user=5",
                "opencrawling.jobs.dynamic.retention=2h",
                "opencrawling.jobs.dynamic.max-retained=10",
                "opencrawling.jobs.dynamic.transformation-connector=OpenAI_Embedding").run(context -> {
            DynamicJobProperties properties = context.getBean(DynamicJobProperties.class);
            assertThat(properties.getConnectors()).containsExactly("Alfresco_Main", "CMIS_Archive");
            assertThat(properties.getRequiredScope()).isEqualTo("ingest");
            assertThat(properties.getDocumentScope()).isEqualTo("ingest-one");
            assertThat(properties.getAdminGroups()).containsExactly("oc-admins");
            assertThat(properties.getMaxRunningPerUser()).isEqualTo(5);
            assertThat(properties.getRetention()).isEqualTo(Duration.ofHours(2));
            assertThat(properties.getMaxRetained()).isEqualTo(10);
            assertThat(properties.getTransformationConnector()).isEqualTo("OpenAI_Embedding");
        });
    }

    @Test
    void aDynamicJobPublishesToTheDocumentsTopicOnlyInRagMode() {
        JobOrchestrator orchestrator = mock(JobOrchestrator.class);
        DynamicJobProperties properties = new DynamicJobProperties();
        properties.setTransformationConnector("OpenAI_Embedding");
        RepositoryConnector connector = new FakeDiscoverableConnector();
        BooleanSupplier stop = () -> false;
        ExecutionProvenance provenance = ExecutionProvenance.verified("alice", null, List.of(), null, "mcp", null);
        when(orchestrator.runJob(any(), any(), any(), any(), any(), any(), any(NarrativizationConfig.class), any(),
                any(), any(), any())).thenReturn(true);

        boolean result = DynamicJobConfiguration.crawlRunner(orchestrator, properties)
                .run(connector, "folder-1", "dyn-1", "Q3", "exec-1", stop, provenance);

        assertThat(result).isTrue();
        verify(orchestrator).runJob(eq(connector), isNull(), eq("folder-1"), eq("OpenAI_Embedding"), eq("dyn-1"),
                eq("Q3"), eq(NarrativizationConfig.disabled()), eq(PipelineMode.RAG), eq("exec-1"), eq(stop),
                eq(provenance));
    }
}
