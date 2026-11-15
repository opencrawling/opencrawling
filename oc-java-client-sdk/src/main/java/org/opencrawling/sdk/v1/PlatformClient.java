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
package org.opencrawling.sdk.v1;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.sdk.models.CallerInfo;
import org.opencrawling.sdk.models.ConnectionCheckResponse;
import org.opencrawling.sdk.models.CopilotRequest;
import org.opencrawling.sdk.models.CopilotResponse;
import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.DiscoveryConnectorInfo;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunView;
import org.opencrawling.sdk.models.DiscoveryTreeView;
import org.opencrawling.sdk.models.DocumentLedgerEntry;
import org.opencrawling.sdk.models.ErrorLogsResponse;
import org.opencrawling.sdk.models.JobReportSummary;
import org.opencrawling.sdk.models.JobTraceResponse;
import org.opencrawling.sdk.models.QueryResult;
import org.opencrawling.sdk.models.SystemStatus;
import org.opencrawling.sdk.models.ThroughputMetricsResponse;
import org.opencrawling.sdk.v1.model.ApiIndex;
import org.opencrawling.sdk.v1.model.Connector;
import org.opencrawling.sdk.v1.model.ConnectorInput;
import org.opencrawling.sdk.v1.model.DocumentIngestRequest;
import org.opencrawling.sdk.v1.model.DynamicJob;
import org.opencrawling.sdk.v1.model.DynamicJobAccepted;
import org.opencrawling.sdk.v1.model.DynamicJobRequest;
import org.opencrawling.sdk.v1.model.Health;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.SystemSettings;
import org.opencrawling.sdk.v1.model.JobInput;
import org.opencrawling.sdk.v1.model.JobRun;
import org.opencrawling.sdk.v1.model.VersionInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The OpenCrawling Platform API ({@code /api/v1}): every management operation, over one versioned REST API.
 *
 * <p>Errors throw {@link ApiProblemException}, whose {@link ApiProblem} has the problem type, the details and the
 * request id. Resources that have versions come back as {@link Versioned}; send the ETag back with
 * {@link RequestOptions#ifMatch(String)} so a change made in the meantime is not overwritten. Send an
 * {@link RequestOptions#idempotent(String) Idempotency-Key} with requests that create or start something, so a
 * retry after a network error does not do it twice; without one, the client makes one for each {@code POST}. Requests
 * that are safe to repeat are retried after network errors and {@code 429}/{@code 502}/{@code 503}/{@code 504}
 * ({@link RetryPolicy}). Collections come in {@link Page pages}; the {@code stream} methods follow the cursors.
 *
 * <p>Obtain it from {@code OpenCrawlingClient.platform()}.
 */
public interface PlatformClient {

    /**
     * This client, sending these options with every request (for example a correlation or request id, or an
     * {@code If-Match}); options given to a single call win. Every {@code POST} gets an {@code Idempotency-Key} anyway,
     * so it can be retried safely ({@link RetryPolicy}).
     */
    PlatformClient withDefaults(RequestOptions options);

    /** Links to every collection, and which optional features are enabled. */
    ApiIndex index();

    VersionInfo version();

    /** The runtime's health. Answers (rather than throwing) when the runtime is unhealthy. */
    Health health();

    /** Who the runtime takes this client for, and what it may do. */
    CallerInfo me();

    Jobs jobs();

    JobRuns jobRuns();

    Executions executions();

    Connectors connectors();

    Settings settings();

    Operations operations();

    Migrations migrations();

    Vespa vespa();

    Narrativization narrativization();

    /** Needs {@code opencrawling.jobs.dynamic.enabled} on the runtime. */
    DynamicJobs dynamicJobs();

    /** Needs {@code opencrawling.discovery.enabled} on the runtime. */
    Discovery discovery();

    /** Needs {@code opencrawling.sidecars.enabled} on the runtime. */
    Sidecars sidecars();

    /**
     * Filters for the jobs collection; null fields do not filter. Start from {@link #all()}.
     *
     * @param sort a field (name, status, lastRun, id), with {@code -} in front for descending
     */
    record JobFilter(String status, String pipelineMode, String repositoryConnector, String outputConnector,
                     String q, String sort) {
        public static JobFilter all() {
            return new JobFilter(null, null, null, null, null, null);
        }

        public JobFilter withStatus(String value) {
            return new JobFilter(value, pipelineMode, repositoryConnector, outputConnector, q, sort);
        }

        public JobFilter withPipelineMode(String value) {
            return new JobFilter(status, value, repositoryConnector, outputConnector, q, sort);
        }

        public JobFilter withRepositoryConnector(String value) {
            return new JobFilter(status, pipelineMode, value, outputConnector, q, sort);
        }

        public JobFilter withOutputConnector(String value) {
            return new JobFilter(status, pipelineMode, repositoryConnector, value, q, sort);
        }

        /** Jobs whose name contains this text, ignoring case. */
        public JobFilter withText(String value) {
            return new JobFilter(status, pipelineMode, repositoryConnector, outputConnector, value, sort);
        }

        public JobFilter withSort(String value) {
            return new JobFilter(status, pipelineMode, repositoryConnector, outputConnector, q, value);
        }
    }

    /**
     * Filters for executions; null fields do not filter. Start from {@link #all()}.
     *
     * @param sort a field (startTime, jobId, status), with {@code -} in front for descending; default newest first
     */
    record ExecutionFilter(String jobId, String status, String pipelineMode, String sort) {
        public static ExecutionFilter all() {
            return new ExecutionFilter(null, null, null, null);
        }

        public ExecutionFilter withJobId(String value) {
            return new ExecutionFilter(value, status, pipelineMode, sort);
        }

        public ExecutionFilter withStatus(String value) {
            return new ExecutionFilter(jobId, value, pipelineMode, sort);
        }

        public ExecutionFilter withPipelineMode(String value) {
            return new ExecutionFilter(jobId, status, value, sort);
        }

        public ExecutionFilter withSort(String value) {
            return new ExecutionFilter(jobId, status, pipelineMode, value);
        }
    }

    interface Jobs {
        Page<Job> list(JobFilter filter, PageRequest page);

        /** Every job matching the filter, reading pages as the stream is consumed. */
        Stream<Job> stream(JobFilter filter);

        /** Creates a job; the caller becomes its owner. */
        Versioned<Job> create(JobInput job, RequestOptions options);

        Versioned<Job> get(String id);

        /** The job, unless it still has the given ETag: then empty (the runtime answered {@code 304}). */
        Optional<Versioned<Job>> getIfChanged(String id, String etag);

        /** Replaces the definition; with {@link RequestOptions#ifMatch} only if unchanged since read. */
        Versioned<Job> replace(String id, JobInput job, RequestOptions options);

        /** Changes some fields (JSON Merge Patch: a null value clears an optional field). */
        Versioned<Job> patch(String id, Map<String, Object> changes, RequestOptions options);

        void delete(String id, RequestOptions options);

        /** Starts a run; {@link Versioned#location()} is the run's URL. */
        Versioned<JobRun> start(String id, RequestOptions options);

        Job stop(String id);

        Job pause(String id);

        DiagnosticReport diagnose(String id);

        JobTraceResponse traces(String id);

        /** @param timeframe for example {@code 1h}, {@code 24h} or {@code all} */
        ErrorLogsResponse errors(String id, String timeframe);
    }

    interface JobRuns {
        /** @param jobId only this job's run; null for all */
        Page<JobRun> list(String jobId, PageRequest page);

        JobRun get(String executionId);

        JobRun stop(String executionId);
    }

    interface Executions {
        Page<JobReportSummary> list(ExecutionFilter filter, PageRequest page);

        Stream<JobReportSummary> stream(ExecutionFilter filter);

        /** A job's executions, through the job's own collection. */
        Page<JobReportSummary> listForJob(String jobId, String status, String sort, PageRequest page);

        JobReportSummary get(String executionId);

        /**
         * The documents of an execution.
         *
         * @param status SUCCESS, FAILED or SKIPPED; null for all
         * @param q      text the document id or source URI contains; null for all
         */
        Page<DocumentLedgerEntry> documents(String executionId, String status, String q, PageRequest page);

        /** @param format json, csv, parquet or avro */
        byte[] export(String executionId, String format);

        /** Writes the report file to {@code target} and returns it. */
        Path exportTo(String executionId, String format, Path target);

        /** A read-only SQL query over the audit lake (admins). */
        QueryResult query(String sql);
    }

    interface Connectors {
        /** @param type repository, output, transformation or authority; null for all */
        Page<Connector> list(String type, String q, PageRequest page);

        Stream<Connector> stream(String type, String q);

        Versioned<Connector> get(String name);

        Versioned<Connector> create(ConnectorInput connector, RequestOptions options);

        Versioned<Connector> replace(String name, ConnectorInput connector, RequestOptions options);

        /** @param force delete even if jobs use it */
        void delete(String name, boolean force, RequestOptions options);

        /** Tests the stored connector's connection. */
        ConnectionCheckResponse check(String name);

        /** Tests a definition before saving it. */
        ConnectionCheckResponse checkDefinition(ConnectorInput connector);
    }

    interface Settings {
        Versioned<SystemSettings> system();

        Versioned<SystemSettings> replaceSystem(SystemSettings settings, RequestOptions options);

        Versioned<JsonNode> transport();

        Versioned<JsonNode> replaceTransport(Object settings, RequestOptions options);

        /** Tests a gRPC connection; null host and port test the configured one. */
        JsonNode testGrpc(String host, Integer port);
    }

    interface Operations {
        SystemStatus status();

        List<Map<String, Object>> throughput();

        List<String> logs();

        /** @param connectorId one connector's metrics; null for all */
        ThroughputMetricsResponse metrics(String connectorId);
    }

    interface Migrations {
        /** Plans a ManifoldCF migration (the request as in the API reference); nothing is written. */
        JsonNode planManifoldCf(Object request);

        JsonNode applyManifoldCf(Object request);
    }

    interface Vespa {
        JsonNode health(String endpoint);

        JsonNode documentCounts(String endpoint);

        /** The query as in the API reference: endpoint, documentType, queryText, rankProfile. */
        JsonNode query(Object query);

        JsonNode deployBundledSchema(String configEndpoint);

        /** Deploys an application package (a zip file). */
        JsonNode deployCustomSchema(String configEndpoint, Path applicationPackage);
    }

    interface Narrativization {
        CopilotResponse generateTemplate(CopilotRequest request);
    }

    interface DynamicJobs {
        /** Starts a folder crawl, files it for approval, or with {@code validateOnly} only checks it. */
        DynamicJobAccepted start(DynamicJobRequest request, RequestOptions options);

        /** Ingests one document, or removes it from the index if the repository reports it gone. */
        DynamicJobAccepted ingestDocument(DocumentIngestRequest request, RequestOptions options);

        Page<DynamicJob> list(PageRequest page);

        DynamicJob get(String jobId);

        DynamicJob stop(String jobId);

        DynamicJob approve(String jobId);

        DynamicJob reject(String jobId, String reason);
    }

    interface Discovery {
        List<DiscoveryConnectorInfo> connectors();

        /** Null arguments use the runtime's defaults. */
        DiscoveryTreeView tree(String connector, String start, Integer depth, Integer maxNodes, Boolean includeDocuments);

        DiscoveryDryRunView dryRun(String connector, DiscoveryDryRunRequest request);
    }

    interface Sidecars {
        /** The stores sidecars can be read from. */
        JsonNode stores();

        /** One page of a store's sidecars: {@code items} and {@code nextCursor}. */
        JsonNode read(String connector, String prefix, Integer limit, String cursor);
    }
}
