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
package org.opencrawling.migrator.mcf.engine;

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome.ApplyResult;
import org.opencrawling.migrator.mcf.job.JobMapper;
import org.opencrawling.migrator.mcf.job.JobMappingResult;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFSource;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ties extraction, mapping and (optionally) writing together: {@link #extract} pulls the raw
 * ManifoldCF configuration, {@link #plan} runs every connection/job through the connector/job-level
 * matching rule with zero network writes, and {@link #apply} — only ever called under {@code
 * --apply} — sends the supported half of the plan to OpenCrawling, connectors before jobs,
 * continuing past a failed item rather than aborting the batch (there is no rollback concept on
 * the OpenCrawling side to abort into).
 */
public class MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);

    private final ManifoldCFSource mcfClient;
    private final ConnectorMapperRegistry mapperRegistry;
    private final JobMapper jobMapper;
    private final OpenCrawlingWriter writer;
    private final MigrationOptions options;

    public MigrationEngine(
            ManifoldCFSource mcfClient,
            ConnectorMapperRegistry mapperRegistry,
            OpenCrawlingWriter writer,
            MigrationOptions options) {
        this.mcfClient = mcfClient;
        this.mapperRegistry = mapperRegistry;
        this.jobMapper = new JobMapper();
        this.writer = writer;
        this.options = options;
    }

    public MigrationSnapshot extract() {
        List<McfConnection> connections = new ArrayList<>();
        connections.addAll(mcfClient.listRepositoryConnections());
        connections.addAll(mcfClient.listOutputConnections());
        connections.addAll(mcfClient.listTransformationConnections());
        connections.addAll(mcfClient.listAuthorityConnections());
        List<McfJob> jobs = mcfClient.listJobs();
        return new MigrationSnapshot(connections, jobs);
    }

    public MigrationPlan plan(MigrationSnapshot snapshot) {
        List<ConnectionPlanEntry> connectionEntries = new ArrayList<>();
        // Keyed by McfConnection.lookupKey() (kind+name), not name alone — ManifoldCF only
        // guarantees name-uniqueness within one kind's registry, so a repository and an output
        // connection can legally share a name.
        Map<String, McfConnection> connectionsByKey = new LinkedHashMap<>();
        Map<String, ConnectorMappingResult> mappingsByKey = new LinkedHashMap<>();

        for (McfConnection connection : snapshot.connections()) {
            if (!options.isNameSelected(options.onlyConnections(), connection.name())) {
                continue;
            }
            String overrideTarget = options.connectorOverrides().get(connection.name());
            ConnectorMappingResult mapping = overrideTarget != null
                ? ConnectorMappingResult.overridden(overrideTarget)
                : mapperRegistry.find(connection.className())
                    .map(mapper -> mapper.map(connection, options))
                    .orElse(ConnectorMappingResult.unsupported(
                        "no registered mapper for class '" + connection.className() + "'"));
            connectionEntries.add(new ConnectionPlanEntry(connection, mapping));
            connectionsByKey.put(connection.lookupKey(), connection);
            mappingsByKey.put(connection.lookupKey(), mapping);
        }

        List<JobPlanEntry> jobEntries = new ArrayList<>();
        for (McfJob job : snapshot.jobs()) {
            if (!options.isNameSelected(options.onlyJobs(), job.description())) {
                continue;
            }
            JobMappingResult mapping = jobMapper.map(job, connectionsByKey, mappingsByKey, options);
            jobEntries.add(new JobPlanEntry(job, mapping));
        }

        log.info("Planned {} connection(s) ({} supported) and {} job(s) ({} supported)",
            connectionEntries.size(), connectionEntries.stream().filter(e -> e.mapping().supported()).count(),
            jobEntries.size(), jobEntries.stream().filter(e -> e.mapping().supported()).count());

        return new MigrationPlan(connectionEntries, jobEntries);
    }

    public ApplyOutcome apply(MigrationPlan plan) {
        Map<String, ApplyResult> connectionResults = new LinkedHashMap<>();
        for (ConnectionPlanEntry entry : plan.connections()) {
            if (!entry.mapping().supported() || entry.mapping().overrideTargetName() != null) {
                continue;
            }
            try {
                writer.upsertConnector(entry.mapping().target());
                connectionResults.put(entry.source().name(), new ApplyResult(true, "created/updated"));
            } catch (Exception e) {
                log.error("Failed to apply connection '{}': {}", entry.source().name(), e.getMessage(), e);
                connectionResults.put(entry.source().name(), new ApplyResult(false, e.getMessage()));
            }
        }

        Map<String, ApplyResult> jobResults = new LinkedHashMap<>();
        for (JobPlanEntry entry : plan.jobs()) {
            if (!entry.mapping().supported()) {
                continue;
            }
            try {
                writer.upsertJob(entry.mapping().target());
                jobResults.put(entry.source().description(), new ApplyResult(true, "created/updated"));
            } catch (Exception e) {
                log.error("Failed to apply job '{}': {}", entry.source().description(), e.getMessage(), e);
                jobResults.put(entry.source().description(), new ApplyResult(false, e.getMessage()));
            }
        }

        return new ApplyOutcome(connectionResults, jobResults);
    }
}
