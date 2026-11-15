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

import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Dynamic jobs, when {@code opencrawling.jobs.dynamic.enabled} is on, or BPM events need them
 * ({@code opencrawling.events.bpm.enabled}). They use the registered connectors the job
 * API runs ({@code connectors.json}), built the same way, and the same run registry and its caps.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${" + DynamicJobProperties.PREFIX + ".enabled:false} or ${" + BpmEventProperties.PREFIX + ".enabled:false}")
@EnableConfigurationProperties(DynamicJobProperties.class)
public class DynamicJobConfiguration {

    @Bean
    public DynamicJobService dynamicJobService(ConnectorFactory connectorFactory, JobRunRegistry runRegistry,
                                               JobOrchestrator orchestrator,
                                               ObjectProvider<JobLedgerAccumulator> ledger,
                                               DynamicJobProperties properties) {
        DynamicJobService.CrawlRunner runner = crawlRunner(orchestrator, properties);
        return new DynamicJobService(
                () -> PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, List.of()),
                connectorFactory::repository, runRegistry, runner,
                executionId -> {
                    JobLedgerAccumulator accumulator = ledger.getIfAvailable();
                    JobExecutionCompletedEvent event = accumulator != null ? accumulator.getCompletedEvent(executionId) : null;
                    return event != null ? event.totalDocuments() : null;
                },
                properties, Clock.systemUTC());
    }

    /**
     * Runs a dynamic job with no output connector, so every document goes to the documents topic only: ingestion,
     * the permission sync and (later) the governance gate see each one. Always RAG mode, whatever the global
     * default, and no narrativization.
     */
    static DynamicJobService.CrawlRunner crawlRunner(JobOrchestrator orchestrator, DynamicJobProperties properties) {
        return (connector, start, jobId, jobName, executionId, stopRequested, provenance, chunking) ->
                orchestrator.runJob(connector, null, start, properties.getTransformationConnector(), jobId, jobName,
                        NarrativizationConfig.disabled(), PipelineMode.RAG, executionId, stopRequested, provenance, chunking);
    }
}
