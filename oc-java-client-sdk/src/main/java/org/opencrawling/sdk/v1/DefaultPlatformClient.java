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
package org.opencrawling.sdk.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.exceptions.OpenCrawlingNetworkException;
import org.opencrawling.sdk.http.HttpTransport;
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
import org.opencrawling.sdk.v1.model.CronValidationRequest;
import org.opencrawling.sdk.v1.model.CronValidationResult;
import org.opencrawling.sdk.v1.model.DocumentIngestRequest;
import org.opencrawling.sdk.v1.model.DynamicJob;
import org.opencrawling.sdk.v1.model.DynamicJobAccepted;
import org.opencrawling.sdk.v1.model.DynamicJobRequest;
import org.opencrawling.sdk.v1.model.Health;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.JobSchedule;
import org.opencrawling.sdk.v1.model.JobScheduleConfig;
import org.opencrawling.sdk.v1.model.ScheduleDispatch;
import org.opencrawling.sdk.v1.model.SchedulerOverview;
import org.opencrawling.sdk.v1.model.SystemSettings;
import org.opencrawling.sdk.v1.model.TransportSettings;
import org.opencrawling.sdk.v1.model.SidecarPage;
import org.opencrawling.sdk.v1.model.SidecarStoreInfo;
import org.opencrawling.sdk.v1.model.JobInput;
import org.opencrawling.sdk.v1.model.JobRun;
import org.opencrawling.sdk.v1.model.JobRunHistory;
import org.opencrawling.sdk.v1.model.Run;
import org.opencrawling.sdk.v1.model.RunHistory;
import org.opencrawling.sdk.v1.model.VersionInfo;
import org.opencrawling.sdk.exceptions.OpenCrawlingException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** {@link PlatformClient} over the SDK's HTTP transport. */
public class DefaultPlatformClient implements PlatformClient {

    static final String BASE = "/api/v1";
    private static final String JSON = "application/json";
    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final String ACCEPT = "application/json, application/problem+json";

    private final HttpTransport transport;
    private final ObjectMapper json;
    private final RetryPolicy retryPolicy;
    private final RequestOptions defaults;

    public DefaultPlatformClient(HttpTransport transport) {
        this(transport, RetryPolicy.defaults());
    }

    public DefaultPlatformClient(HttpTransport transport, RetryPolicy retryPolicy) {
        this(transport, retryPolicy, RequestOptions.none());
    }

    private DefaultPlatformClient(HttpTransport transport, RetryPolicy retryPolicy, RequestOptions defaults) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = transport.getObjectMapper();
        this.retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.defaults();
        this.defaults = defaults != null ? defaults : RequestOptions.none();
    }

    /**
     * Only the options that make sense on every request are kept: the correlation and request ids. An Idempotency-Key
     * or an If-Match names one request or one version, so as defaults they would make unrelated calls replay each
     * other's answers or fail; they are ignored here and belong on each call.
     */
    @Override
    public PlatformClient withDefaults(RequestOptions options) {
        RequestOptions o = options != null ? options : RequestOptions.none();
        return new DefaultPlatformClient(transport, retryPolicy, merge(defaults,
                new RequestOptions(null, o.correlationId(), o.requestId(), null)));
    }

    /** Each option set in {@code overrides} wins; the others come from {@code base}. */
    static RequestOptions merge(RequestOptions base, RequestOptions overrides) {
        RequestOptions o = overrides != null ? overrides : RequestOptions.none();
        return new RequestOptions(o.idempotencyKey() != null ? o.idempotencyKey() : base.idempotencyKey(),
                o.correlationId() != null ? o.correlationId() : base.correlationId(),
                o.requestId() != null ? o.requestId() : base.requestId(),
                o.ifMatch() != null ? o.ifMatch() : base.ifMatch());
    }

    // ---------------------------------------------------------------- platform

    @Override
    public ApiIndex index() {
        return getJson(path(), ApiIndex.class);
    }

    @Override
    public VersionInfo version() {
        return getJson(path("version"), VersionInfo.class);
    }

    @Override
    public Health health() {
        // Not retried: a 503 here is an answer (the runtime is unhealthy), not a failure to get one.
        HttpTransport.RawResponse response = send("GET", path("health"), null, null, RequestOptions.none(), Map.of(), false);
        if (response.status() == 503) {
            return read(response, type(Health.class)); // unhealthy: the body is the health report
        }
        return read(ok(response), type(Health.class));
    }

    @Override
    public CallerInfo me() {
        return getJson(path("me"), CallerInfo.class);
    }

    @Override
    public Jobs jobs() {
        return jobs;
    }

    @Override
    public Schedules schedules() {
        return schedules;
    }

    @Override
    public JobRuns jobRuns() {
        return jobRuns;
    }

    @Override
    public Runs runs() {
        return runs;
    }

    @Override
    public Executions executions() {
        return executions;
    }

    @Override
    public Connectors connectors() {
        return connectors;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Operations operations() {
        return operations;
    }

    @Override
    public Migrations migrations() {
        return migrations;
    }

    @Override
    public Vespa vespa() {
        return vespa;
    }

    @Override
    public Narrativization narrativization() {
        return narrativization;
    }

    @Override
    public DynamicJobs dynamicJobs() {
        return dynamicJobs;
    }

    @Override
    public Discovery discovery() {
        return discovery;
    }

    @Override
    public Sidecars sidecars() {
        return sidecars;
    }

    // ---------------------------------------------------------------- jobs

    private final Jobs jobs = new Jobs() {
        @Override
        public Page<Job> list(JobFilter filter, PageRequest page) {
            JobFilter f = filter != null ? filter : JobFilter.all();
            return getPage(path("jobs"), params("status", f.status(), "pipelineMode", f.pipelineMode(),
                    "repositoryConnector", f.repositoryConnector(), "outputConnector", f.outputConnector(),
                    "q", f.q(), "sort", f.sort()), page, Job.class);
        }

        @Override
        public Stream<Job> stream(JobFilter filter) {
            return streamOf(page -> list(filter, page));
        }

        @Override
        public Versioned<Job> create(JobInput job, RequestOptions options) {
            return versioned(send("POST", path("jobs"), job, JSON, options, Map.of()), Job.class);
        }

        @Override
        public Versioned<Job> get(String id) {
            return versioned(send("GET", path("jobs", id), null, null, RequestOptions.none(), Map.of()), Job.class);
        }

        @Override
        public Optional<Versioned<Job>> getIfChanged(String id, String etag) {
            HttpTransport.RawResponse response = send("GET", path("jobs", id), null, null, RequestOptions.none(),
                    etag != null ? Map.of("If-None-Match", etag) : Map.of());
            return response.status() == 304 ? Optional.empty() : Optional.of(versioned(response, Job.class));
        }

        @Override
        public Versioned<Job> replace(String id, JobInput job, RequestOptions options) {
            return versioned(send("PUT", path("jobs", id), job, JSON, options, Map.of()), Job.class);
        }

        @Override
        public Versioned<Job> patch(String id, Map<String, Object> changes, RequestOptions options) {
            return versioned(send("PATCH", path("jobs", id), new NullsKept(changes), MERGE_PATCH, options, Map.of()),
                    Job.class);
        }

        @Override
        public void delete(String id, RequestOptions options) {
            ok(send("DELETE", path("jobs", id), null, null, options, Map.of()));
        }

        @Override
        public Versioned<JobRun> start(String id, RequestOptions options) {
            return versioned(send("POST", action(path("jobs", id), "start"), null, null, options, Map.of()), JobRun.class);
        }

        @Override
        public Job stop(String id) {
            return post(action(path("jobs", id), "stop"), null, Job.class);
        }

        @Override
        public Job pause(String id) {
            return post(action(path("jobs", id), "pause"), null, Job.class);
        }

        @Override
        public Job resume(String id) {
            return post(action(path("jobs", id), "resume"), null, Job.class);
        }

        @Override
        public DiagnosticReport diagnose(String id) {
            return getJson(path("jobs", id, "diagnosis"), DiagnosticReport.class);
        }

        @Override
        public JobTraceResponse traces(String id) {
            return getJson(path("jobs", id, "traces"), JobTraceResponse.class);
        }

        @Override
        public ErrorLogsResponse errors(String id, String timeframe) {
            return getJson(path("jobs", id, "errors") + params("timeframe", timeframe), ErrorLogsResponse.class);
        }
    };

    private final Schedules schedules = new Schedules() {
        @Override
        public Page<JobSchedule> list(ScheduleFilter filter, PageRequest page) {
            ScheduleFilter f = filter != null ? filter : ScheduleFilter.all();
            return getPage(path("schedules"), params("enabled", f.enabled(), "outcome", f.outcome(),
                    "q", f.q(), "sort", f.sort()), page, JobSchedule.class);
        }

        @Override
        public Stream<JobSchedule> stream(ScheduleFilter filter) {
            return streamOf(page -> list(filter, page));
        }

        @Override
        public SchedulerOverview overview(String horizon, Integer limit) {
            return getJson(path("schedules", "overview") + params("horizon", horizon, "limit", limit),
                    SchedulerOverview.class);
        }

        @Override
        public CronValidationResult validateCron(CronValidationRequest request) {
            return post(action(path("schedules"), "validate-cron"), request, CronValidationResult.class);
        }

        @Override
        public Versioned<JobSchedule> get(String jobId) {
            return versioned(send("GET", path("jobs", jobId, "schedule"), null, null, RequestOptions.none(), Map.of()),
                    JobSchedule.class);
        }

        @Override
        public Optional<Versioned<JobSchedule>> getIfChanged(String jobId, String etag) {
            HttpTransport.RawResponse response = send("GET", path("jobs", jobId, "schedule"), null, null,
                    RequestOptions.none(), etag != null ? Map.of("If-None-Match", etag) : Map.of());
            return response.status() == 304 ? Optional.empty() : Optional.of(versioned(response, JobSchedule.class));
        }

        @Override
        public Versioned<JobSchedule> set(String jobId, JobScheduleConfig config, RequestOptions options) {
            return versioned(send("PUT", path("jobs", jobId, "schedule"), config, JSON, options, Map.of()),
                    JobSchedule.class);
        }

        @Override
        public void delete(String jobId, RequestOptions options) {
            ok(send("DELETE", path("jobs", jobId, "schedule"), null, null, options, Map.of()));
        }

        @Override
        public Versioned<JobSchedule> pause(String jobId, RequestOptions options) {
            return versioned(send("POST", action(path("jobs", jobId, "schedule"), "pause"), null, null, options,
                    Map.of()), JobSchedule.class);
        }

        @Override
        public Versioned<JobSchedule> resume(String jobId, RequestOptions options) {
            return versioned(send("POST", action(path("jobs", jobId, "schedule"), "resume"), null, null, options,
                    Map.of()), JobSchedule.class);
        }

        @Override
        public Page<ScheduleDispatch> dispatches(String jobId, String outcome, PageRequest page) {
            return getPage(path("jobs", jobId, "schedule", "dispatches"), params("outcome", outcome), page,
                    ScheduleDispatch.class);
        }

        @Override
        public Stream<ScheduleDispatch> streamDispatches(String jobId, String outcome) {
            return streamOf(page -> dispatches(jobId, outcome, page));
        }
    };

    private final JobRuns jobRuns = new JobRuns() {
        @Override
        public Page<JobRun> list(String jobId, PageRequest page) {
            return getPage(path("job-runs"), params("jobId", jobId), page, JobRun.class);
        }

        @Override
        public JobRun get(String executionId) {
            return getJson(path("job-runs", executionId), JobRun.class);
        }

        @Override
        public JobRun stop(String executionId) {
            return post(action(path("job-runs", executionId), "stop"), null, JobRun.class);
        }

        @Override
        public JobRun pause(String executionId) {
            return post(action(path("job-runs", executionId), "pause"), null, JobRun.class);
        }

        @Override
        public JobRun resume(String executionId) {
            return post(action(path("job-runs", executionId), "resume"), null, JobRun.class);
        }

        @Override
        public JobRunHistory history(String executionId) {
            return getJson(path("job-runs", executionId, "history"), JobRunHistory.class);
        }

        private static final Set<String> TERMINAL_RUN_STATES = Set.of("COMPLETED", "STOPPED", "FAILED", "INTERRUPTED");

        @Override
        public JobRunHistory waitForCompletion(String executionId, Duration pollInterval, Duration timeout) {
            Duration interval = (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
                    ? Duration.ofSeconds(2)
                    : pollInterval;
            Duration maxWait = (timeout == null || timeout.isNegative()) ? Duration.ofMinutes(10) : timeout;
            Instant deadline = Instant.now().plus(maxWait);
            while (true) {
                JobRunHistory current = history(executionId);
                if (current != null && (current.endedAt() != null
                        || (current.state() != null && TERMINAL_RUN_STATES.contains(current.state())))) {
                    return current;
                }
                if (!Instant.now().isBefore(deadline)) {
                    throw new OpenCrawlingException("Timed out after " + maxWait + " waiting for job run "
                            + executionId + " to finish (last state: "
                            + (current != null ? current.state() : "unknown") + ")");
                }
                DefaultPlatformClient.pause(interval);
            }
        }
    };

    // ---------------------------------------------------------------- runs

    private final Runs runs = new Runs() {
        @Override
        public Page<Run> list(String kind, String state, String jobId, PageRequest page) {
            return getPage(path("runs"), params("kind", kind, "state", state, "jobId", jobId), page, Run.class);
        }

        @Override
        public Run get(String runId) {
            return getJson(path("runs", runId), Run.class);
        }

        @Override
        public RunHistory history(String runId) {
            return getJson(path("runs", runId, "history"), RunHistory.class);
        }

        @Override
        public Run stop(String runId) {
            return post(action(path("runs", runId), "stop"), null, Run.class);
        }

        @Override
        public Run pause(String runId) {
            return post(action(path("runs", runId), "pause"), null, Run.class);
        }

        @Override
        public Run resume(String runId) {
            return post(action(path("runs", runId), "resume"), null, Run.class);
        }
    };

    // ---------------------------------------------------------------- executions

    private final Executions executions = new Executions() {
        @Override
        public Page<JobReportSummary> list(ExecutionFilter filter, PageRequest page) {
            ExecutionFilter f = filter != null ? filter : ExecutionFilter.all();
            return getPage(path("executions"), params("jobId", f.jobId(), "status", f.status(),
                    "pipelineMode", f.pipelineMode(), "sort", f.sort()), page, JobReportSummary.class);
        }

        @Override
        public Stream<JobReportSummary> stream(ExecutionFilter filter) {
            return streamOf(page -> list(filter, page));
        }

        @Override
        public Page<JobReportSummary> listForJob(String jobId, String status, String sort, PageRequest page) {
            return getPage(path("jobs", jobId, "executions"), params("status", status, "sort", sort), page,
                    JobReportSummary.class);
        }

        @Override
        public JobReportSummary get(String executionId) {
            return getJson(path("executions", executionId), JobReportSummary.class);
        }

        @Override
        public Page<DocumentLedgerEntry> documents(String executionId, String status, String q, PageRequest page) {
            return getPage(path("executions", executionId, "documents"), params("status", status, "q", q), page,
                    DocumentLedgerEntry.class);
        }

        @Override
        public byte[] export(String executionId, String format) {
            HttpTransport.RawResponse response = send("GET", path("executions", executionId, "export")
                    + params("format", format), null, null, RequestOptions.none(), Map.of("Accept", "*/*"));
            return ok(response).body();
        }

        @Override
        public Path exportTo(String executionId, String format, Path target) {
            Map<String, String> headers = new LinkedHashMap<>(headersFor(merge(defaults, RequestOptions.none())));
            headers.put("Accept", "*/*");
            try (HttpTransport.StreamResponse response = transport.openStream("GET",
                    path("executions", executionId, "export") + params("format", format), headers)) {
                if (response.status() >= 400) {
                    byte[] problem = response.body().readAllBytes();
                    ok(new HttpTransport.RawResponse(response.status(), response.headers(), problem));
                }
                // Streamed to a file next to the target, then moved: report files can be large, and a failed download
                // leaves no truncated file behind.
                Path absolute = target.toAbsolutePath();
                Path partial = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".part");
                try {
                    Files.copy(response.body(), partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(partial);
                    throw e;
                }
                return target;
            } catch (IOException e) {
                throw new OpenCrawlingNetworkException("Could not write the report to " + target, e);
            }
        }

        @Override
        public QueryResult query(String sql) {
            return post(action(path("executions"), "query"), Map.of("sql", sql), QueryResult.class);
        }
    };

    // ---------------------------------------------------------------- connectors

    private final Connectors connectors = new Connectors() {
        @Override
        public Page<Connector> list(String type, String q, PageRequest page) {
            return getPage(path("connectors"), params("type", type, "q", q), page, Connector.class);
        }

        @Override
        public Stream<Connector> stream(String type, String q) {
            return streamOf(page -> list(type, q, page));
        }

        @Override
        public Versioned<Connector> get(String name) {
            return versioned(send("GET", path("connectors", name), null, null, RequestOptions.none(), Map.of()),
                    Connector.class);
        }

        @Override
        public Versioned<Connector> create(ConnectorInput connector, RequestOptions options) {
            return versioned(send("POST", path("connectors"), connector, JSON, options, Map.of()), Connector.class);
        }

        @Override
        public Versioned<Connector> replace(String name, ConnectorInput connector, RequestOptions options) {
            return versioned(send("PUT", path("connectors", name), connector, JSON, options, Map.of()), Connector.class);
        }

        @Override
        public void delete(String name, boolean force, RequestOptions options) {
            ok(send("DELETE", path("connectors", name) + (force ? "?force=true" : ""), null, null, options, Map.of()));
        }

        @Override
        public ConnectionCheckResponse check(String name) {
            return post(action(path("connectors", name), "check"), null, ConnectionCheckResponse.class);
        }

        @Override
        public ConnectionCheckResponse checkDefinition(ConnectorInput connector) {
            return post(action(path("connectors"), "check"), connector, ConnectionCheckResponse.class);
        }
    };

    // ---------------------------------------------------------------- settings and operations

    private final Settings settings = new Settings() {
        @Override
        public Versioned<SystemSettings> system() {
            return versioned(send("GET", path("settings", "system"), null, null, RequestOptions.none(), Map.of()),
                    SystemSettings.class);
        }

        @Override
        public Versioned<SystemSettings> replaceSystem(SystemSettings value, RequestOptions options) {
            return versioned(send("PUT", path("settings", "system"), value, JSON, options, Map.of()),
                    SystemSettings.class);
        }

        @Override
        public Versioned<JsonNode> transport() {
            return versioned(send("GET", path("settings", "transport"), null, null, RequestOptions.none(), Map.of()),
                    JsonNode.class);
        }

        @Override
        public Versioned<JsonNode> replaceTransport(Object value, RequestOptions options) {
            return versioned(send("PUT", path("settings", "transport"), value, JSON, options, Map.of()), JsonNode.class);
        }

        @Override
        public Versioned<TransportSettings> transportSettings() {
            return versioned(send("GET", path("settings", "transport"), null, null, RequestOptions.none(), Map.of()),
                    TransportSettings.class);
        }

        @Override
        public Versioned<TransportSettings> replaceTransportSettings(TransportSettings value, RequestOptions options) {
            return versioned(send("PUT", path("settings", "transport"), value, JSON, options, Map.of()),
                    TransportSettings.class);
        }

        @Override
        public JsonNode testGrpc(String host, Integer port) {
            Map<String, Object> target = new LinkedHashMap<>();
            if (host != null) {
                target.put("host", host);
            }
            if (port != null) {
                target.put("port", port);
            }
            return post(action(path("settings", "transport"), "test-grpc"), target, JsonNode.class);
        }
    };

    private final Operations operations = new Operations() {
        @Override
        public SystemStatus status() {
            return getJson(path("system", "status"), SystemStatus.class);
        }

        @Override
        public List<Map<String, Object>> throughput() {
            return read(ok(send("GET", path("system", "throughput"), null, null, RequestOptions.none(), Map.of())),
                    json.getTypeFactory().constructType(new TypeReference<List<Map<String, Object>>>() {}));
        }

        @Override
        public List<String> logs() {
            return read(ok(send("GET", path("system", "logs"), null, null, RequestOptions.none(), Map.of())),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
        }

        @Override
        public ThroughputMetricsResponse metrics(String connectorId) {
            return getJson(path("observability", "metrics") + params("connectorId", connectorId),
                    ThroughputMetricsResponse.class);
        }
    };

    // ---------------------------------------------------------------- tools

    private final Migrations migrations = new Migrations() {
        @Override
        public JsonNode planManifoldCf(Object request) {
            return post(action(path("migrations", "manifoldcf"), "plan"), request, JsonNode.class);
        }

        @Override
        public JsonNode applyManifoldCf(Object request) {
            return post(action(path("migrations", "manifoldcf"), "apply"), request, JsonNode.class);
        }
    };

    private final Vespa vespa = new Vespa() {
        @Override
        public JsonNode health(String endpoint) {
            return getJson(path("integrations", "vespa", "health") + params("endpoint", endpoint), JsonNode.class);
        }

        @Override
        public JsonNode documentCounts(String endpoint) {
            return getJson(path("integrations", "vespa", "document-counts") + params("endpoint", endpoint), JsonNode.class);
        }

        @Override
        public JsonNode query(Object query) {
            return post(action(path("integrations", "vespa"), "query"), query, JsonNode.class);
        }

        @Override
        public JsonNode deployBundledSchema(String configEndpoint) {
            return post(action(path("integrations", "vespa"), "deploy-bundled"),
                    Map.of("configEndpoint", configEndpoint), JsonNode.class);
        }

        @Override
        public JsonNode deployCustomSchema(String configEndpoint, Path applicationPackage) {
            String boundary = "oc-" + UUID.randomUUID();
            byte[] body = multipart(boundary, configEndpoint, applicationPackage);
            return read(ok(send("POST", action(path("integrations", "vespa"), "deploy-custom"), body,
                    "multipart/form-data; boundary=" + boundary, RequestOptions.none(), Map.of())), type(JsonNode.class));
        }
    };

    private final Narrativization narrativization = request ->
            post(action(path("narrativization"), "generate-template"), request, CopilotResponse.class);

    // ---------------------------------------------------------------- optional features

    private final DynamicJobs dynamicJobs = new DynamicJobs() {
        @Override
        public DynamicJobAccepted start(DynamicJobRequest request, RequestOptions options) {
            return read(ok(send("POST", path("dynamic-jobs"), request, JSON, options, Map.of())),
                    type(DynamicJobAccepted.class));
        }

        @Override
        public DynamicJobAccepted ingestDocument(DocumentIngestRequest request, RequestOptions options) {
            return read(ok(send("POST", action(path("dynamic-jobs"), "ingest-document"), request, JSON, options,
                    Map.of())), type(DynamicJobAccepted.class));
        }

        @Override
        public Page<DynamicJob> list(PageRequest page) {
            return getPage(path("dynamic-jobs"), "", page, DynamicJob.class);
        }

        @Override
        public DynamicJob get(String jobId) {
            return getJson(path("dynamic-jobs", jobId), DynamicJob.class);
        }

        @Override
        public DynamicJob stop(String jobId) {
            return post(action(path("dynamic-jobs", jobId), "stop"), null, DynamicJob.class);
        }

        @Override
        public DynamicJob approve(String jobId) {
            return post(action(path("dynamic-jobs", jobId), "approve"), null, DynamicJob.class);
        }

        @Override
        public DynamicJob reject(String jobId, String reason) {
            return post(action(path("dynamic-jobs", jobId), "reject"),
                    reason != null ? Map.of("reason", reason) : null, DynamicJob.class);
        }
    };

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DryRunBody(String scope, Integer sampleSize, Integer maxScan) {}

    private final Discovery discovery = new Discovery() {
        @Override
        public List<DiscoveryConnectorInfo> connectors() {
            return read(ok(send("GET", path("discovery", "connectors"), null, null, RequestOptions.none(), Map.of())),
                    json.getTypeFactory().constructCollectionType(List.class, DiscoveryConnectorInfo.class));
        }

        @Override
        public DiscoveryTreeView tree(String connector, String start, Integer depth, Integer maxNodes,
                                      Boolean includeDocuments) {
            return getJson(path("discovery", "connectors", connector, "tree") + params("start", start, "depth", depth,
                    "maxNodes", maxNodes, "includeDocuments", includeDocuments), DiscoveryTreeView.class);
        }

        @Override
        public DiscoveryDryRunView dryRun(String connector, DiscoveryDryRunRequest request) {
            DryRunBody body = request == null ? new DryRunBody(null, null, null)
                    : new DryRunBody(request.scope(), request.sampleSize(), request.maxScan());
            return post(action(path("discovery", "connectors", connector), "dry-run"), body, DiscoveryDryRunView.class);
        }
    };

    private final Sidecars sidecars = new Sidecars() {
        @Override
        public JsonNode stores() {
            return getJson(path("sidecar-stores"), JsonNode.class);
        }

        @Override
        public List<SidecarStoreInfo> listStores() {
            return DefaultPlatformClient.this.read(
                    ok(send("GET", path("sidecar-stores"), null, null, RequestOptions.none(), Map.of())),
                    json.getTypeFactory().constructCollectionType(List.class, SidecarStoreInfo.class));
        }

        @Override
        public JsonNode read(String connector, String prefix, Integer limit, String cursor) {
            return getJson(path("sidecar-stores", connector, "sidecars")
                    + params("prefix", prefix, "limit", limit, "cursor", cursor), JsonNode.class);
        }

        @Override
        public SidecarPage readPage(String connector, String prefix, Integer limit, String cursor) {
            return getJson(path("sidecar-stores", connector, "sidecars")
                    + params("prefix", prefix, "limit", limit, "cursor", cursor), SidecarPage.class);
        }

        @Override
        public Stream<Map<String, Object>> stream(String connector, String prefix) {
            Iterator<Map<String, Object>> items = new Iterator<>() {
                private SidecarPage page = readPage(connector, prefix, null, null);
                private Iterator<Map<String, Object>> current = page.items().iterator();

                @Override
                public boolean hasNext() {
                    while (!current.hasNext() && page.hasNext()) {
                        page = readPage(connector, prefix, null, page.nextCursor());
                        current = page.items().iterator();
                    }
                    return current.hasNext();
                }

                @Override
                public Map<String, Object> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return current.next();
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(items, Spliterator.ORDERED), false);
        }
    };

    // ---------------------------------------------------------------- HTTP

    /** {@code /api/v1/<each segment, encoded>}. */
    static String path(String... segments) {
        StringBuilder path = new StringBuilder(BASE);
        for (String segment : segments) {
            Objects.requireNonNull(segment, "path segment");
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("A path segment (an id or name) must not be empty");
            }
            path.append('/').append(encode(segment));
        }
        return path.toString();
    }

    /** A custom method: {@code <path>:<name>}. */
    static String action(String path, String name) {
        return path + ":" + name;
    }

    /** {@code ?name=value&...} of the pairs whose value is not null; empty if none. */
    static String params(Object... pairs) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                query.append(query.isEmpty() ? '?' : '&').append(encode(pairs[i].toString())).append('=')
                        .append(encode(pairs[i + 1].toString()));
            }
        }
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpTransport.RawResponse send(String method, String path, Object body, String contentType,
                                           RequestOptions options, Map<String, String> extraHeaders) {
        return send(method, path, body, contentType, options, extraHeaders, true);
    }

    private static Map<String, String> headersFor(RequestOptions o) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", ACCEPT);
        headers.put("Idempotency-Key", o.idempotencyKey());
        headers.put("X-Correlation-Id", o.correlationId());
        headers.put("X-Request-Id", o.requestId());
        headers.put("If-Match", o.ifMatch());
        return headers;
    }

    /**
     * Sends a request, and repeats it when that is safe and may help ({@link RetryPolicy}). A {@code POST} without a
     * key gets one, so that it can be repeated without running twice; uploads (multipart) take no key and are not
     * repeated.
     */
    private HttpTransport.RawResponse send(String method, String path, Object body, String contentType,
                                           RequestOptions options, Map<String, String> extraHeaders, boolean retry) {
        RequestOptions o = merge(defaults, options);
        boolean upload = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        if (upload) {
            o = o.withIdempotencyKey(null); // the runtime refuses a key on an upload
        } else if ("POST".equals(method) && o.idempotencyKey() == null) {
            o = o.withIdempotencyKey("sdk-" + UUID.randomUUID());
        }
        boolean repeatable = switch (method) {
            case "GET", "HEAD", "PUT", "DELETE" -> true;
            case "POST" -> o.idempotencyKey() != null && !upload;
            case "PATCH" -> o.ifMatch() != null; // a conditional patch applies at most once
            default -> false;
        };
        int attempts = retry && repeatable ? retryPolicy.maxAttempts() : 1;
        Map<String, String> headers = headersFor(o);
        headers.putAll(extraHeaders);
        byte[] bytes;
        if (body == null) {
            bytes = null;
        } else if (body instanceof byte[] raw) {
            bytes = raw;
        } else {
            try {
                bytes = body instanceof NullsKept kept
                        ? json.writeValueAsBytes(kept.changes())
                        : json.writeValueAsBytes(body);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not write the request body as JSON", e);
            }
        }
        for (int attempt = 1; ; attempt++) {
            HttpTransport.RawResponse response;
            try {
                response = transport.exchange(method, path, headers, bytes, bytes != null ? contentType : null);
            } catch (OpenCrawlingNetworkException e) {
                if (attempt >= attempts) {
                    throw e;
                }
                pause(retryPolicy.delay(attempt, null));
                continue;
            }
            if (attempt >= attempts || !retryable(response)) {
                return response;
            }
            java.time.Duration wait = retryPolicy.delay(attempt, response.header("Retry-After"));
            if (wait == null) {
                return response; // the runtime asks for a longer wait than the policy allows: give up, do not retry early
            }
            pause(wait);
        }
    }

    /**
     * Whether repeating may help: overload and gateway errors, and a 409 idempotency-in-progress, which means the first
     * attempt (perhaps one that timed out here) is still running; the repeat then gets its answer.
     */
    private static boolean retryable(HttpTransport.RawResponse response) {
        if (RetryPolicy.RETRYABLE_STATUSES.contains(response.status())) {
            return true;
        }
        return response.status() == 409
                && new String(response.body(), StandardCharsets.UTF_8).contains("problem-idempotency-in-progress");
    }

    private static void pause(java.time.Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenCrawlingNetworkException("Interrupted while waiting to retry", e);
        }
    }

    /** The response if it succeeded; otherwise its problem as an exception. */
    private HttpTransport.RawResponse ok(HttpTransport.RawResponse response) {
        if (response.status() < 400) {
            return response;
        }
        String text = new String(response.body(), StandardCharsets.UTF_8);
        ApiProblem problem = null;
        String contentType = response.header("Content-Type");
        if (contentType != null && contentType.contains("json") && !text.isBlank()) {
            try {
                problem = json.readValue(text, ApiProblem.class);
            } catch (IOException ignored) {
                // not a problem detail: fall through
            }
        }
        if (problem == null || problem.getStatus() == 0) {
            problem = new ApiProblem(response.status(), null, "HTTP " + response.status(),
                    text.isBlank() ? null : text);
        }
        if (problem.getRequestId() == null) {
            problem.setRequestId(response.header("X-Request-Id"));
        }
        throw new ApiProblemException(problem, text);
    }

    private <T> T read(HttpTransport.RawResponse response, JavaType type) {
        if (response.body().length == 0) {
            return null;
        }
        try {
            return json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new OpenCrawlingNetworkException("Could not read the response as " + type.getTypeName(), e);
        }
    }

    private JavaType type(Class<?> type) {
        return json.getTypeFactory().constructType(type);
    }

    private <T> T getJson(String path, Class<T> type) {
        return read(ok(send("GET", path, null, null, RequestOptions.none(), Map.of())), type(type));
    }

    private <T> T post(String path, Object body, Class<T> type) {
        return read(ok(send("POST", path, body, body != null ? JSON : null, RequestOptions.none(), Map.of())),
                type(type));
    }

    private <T> Versioned<T> versioned(HttpTransport.RawResponse response, Class<T> type) {
        HttpTransport.RawResponse checked = ok(response);
        return new Versioned<>(read(checked, type(type)), checked.header("ETag"), checked.header("Location"));
    }

    private <T> Page<T> getPage(String path, String filters, PageRequest page, Class<T> itemType) {
        PageRequest p = page != null ? page : PageRequest.first();
        String paging = params("limit", p.limit(), "cursor", p.cursor());
        String query = filters.isEmpty() ? paging
                : paging.isEmpty() ? filters : filters + "&" + paging.substring(1);
        return read(ok(send("GET", path + query, null, null, RequestOptions.none(), Map.of())),
                json.getTypeFactory().constructParametricType(Page.class, itemType));
    }

    /** Every item of a collection, reading the next page only when the stream gets there. */
    static <T> Stream<T> streamOf(Function<PageRequest, Page<T>> reader) {
        Iterator<T> items = new Iterator<>() {
            private Page<T> page = reader.apply(PageRequest.first());
            private Iterator<T> current = page.items().iterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && page.hasNext()) {
                    page = reader.apply(new PageRequest(null, page.page().nextCursor()));
                    current = page.items().iterator();
                }
                return current.hasNext();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(items, Spliterator.ORDERED), false);
    }

    /** A merge patch: unlike other bodies, its null values are sent, since they clear fields. */
    private record NullsKept(Map<String, Object> changes) {}

    private static byte[] multipart(String boundary, String configEndpoint, Path file) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"configEndpoint\"\r\n\r\n"
                    + configEndpoint + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + file.getFileName().toString().replace("\"", "") + "\"\r\nContent-Type: application/zip\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(file));
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new OpenCrawlingNetworkException("Could not read " + file, e);
        }
    }
}
