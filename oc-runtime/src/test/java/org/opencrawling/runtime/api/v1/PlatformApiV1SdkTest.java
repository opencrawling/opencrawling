/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.runtime.api.copilot.TemplateCopilotResponse;
import org.opencrawling.runtime.api.copilot.TemplateGenerationCopilot;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.observability.AIOpsDiagnosticService;
import org.opencrawling.runtime.observability.ObservabilityMcpTools;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.reporting.JobReportService;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.opencrawling.runtime.service.VespaInsightsService;
import org.opencrawling.runtime.sidecar.SidecarService;
import org.opencrawling.runtime.transport.InternalTransportManager;
import org.opencrawling.runtime.transport.TestGrpcResponseDTO;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.CopilotRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.v1.ApiProblemException;
import org.opencrawling.sdk.v1.Page;
import org.opencrawling.sdk.v1.PageRequest;
import org.opencrawling.sdk.v1.PlatformClient;
import org.opencrawling.sdk.v1.RequestOptions;
import org.opencrawling.sdk.v1.Versioned;
import org.opencrawling.sdk.v1.model.Connector;
import org.opencrawling.sdk.v1.model.ConnectorInput;
import org.opencrawling.sdk.v1.model.DocumentIngestRequest;
import org.opencrawling.sdk.v1.model.DynamicJobAccepted;
import org.opencrawling.sdk.v1.model.DynamicJobRequest;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.JobInput;
import org.opencrawling.sdk.v1.model.JobRun;
import org.opencrawling.sdk.v1.model.JobRunHistory;
import org.opencrawling.sdk.v1.model.JobSchedule;
import org.opencrawling.sdk.v1.model.JobScheduleConfig;
import org.opencrawling.sdk.v1.model.CronValidationRequest;
import org.opencrawling.sdk.v1.model.CronValidationResult;
import org.opencrawling.sdk.v1.model.SchedulerOverview;
import org.opencrawling.sdk.v1.model.SystemSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * The Java SDK's Platform API client against this runtime: every v1 operation, reached through the SDK and read back
 * into its models. A filter records each request the runtime receives, so the test proves the SDK reaches all of the
 * operations the API describes, not just that its methods exist.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1FeaturesHttpTest.Features.class, PlatformApiV1SdkTest.Recorder.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "opencrawling.discovery.enabled=true",
                "opencrawling.jobs.dynamic.enabled=true",
                "opencrawling.sidecars.enabled=true"
        })
class PlatformApiV1SdkTest {

    /** Records "METHOD path" of every Platform API request. */
    @Configuration
    static class Recorder {
        static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> platformApiRequestRecorder() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws java.io.IOException, jakarta.servlet.ServletException {
                    REQUESTS.add(request.getMethod() + " " + request.getRequestURI());
                    chain.doFilter(request, response);
                }
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns(ApiV1.BASE, ApiV1.BASE + "/*");
            return registration;
        }
    }

    @LocalServerPort
    int port;

    @TempDir
    Path tmp;

    @Autowired JobOrchestrator orchestrator;
    @Autowired AIOpsDiagnosticService diagnostics;
    @Autowired ObservabilityMcpTools observability;
    @Autowired InternalTransportManager transport;
    @Autowired VespaInsightsService vespa;
    @Autowired TemplateGenerationCopilot copilot;
    @Autowired JobReportService reports;
    @Autowired DynamicJobService dynamicJobs;
    @Autowired DiscoveryService discovery;
    @Autowired SidecarService sidecars;

    private PlatformClient api;
    private final String tag = "sdk-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void client() {
        api = OpenCrawlingClient.builder().baseUrl("http://localhost:" + port).build().platform();
    }

    @Test
    void theSdkReachesEveryOperationAndReadsItsAnswers() throws Exception {
        Recorder.REQUESTS.clear();
        platform();
        String jobId = jobs();
        schedules(jobId);
        runs(jobId);
        executions(jobId);
        connectors();
        settingsAndOperations();
        tools();
        features();
        api.jobs().delete(jobId, RequestOptions.none());

        Map<String, String> unreached = new TreeMap<>(operationsOf(openApi()));
        Set<String> requests = new TreeSet<>(Recorder.REQUESTS);
        unreached.keySet().removeIf(template -> requests.stream().anyMatch(request -> matches(template, request)));
        assertThat(unreached).as("Platform API operations the SDK did not reach").isEmpty();
    }

    private void platform() {
        assertThat(api.index().hasFeature("dynamicJobs")).isTrue();
        assertThat(api.index().links()).containsEntry("jobs", "/api/v1/jobs");
        assertThat(api.version().apiVersion()).isEqualTo(ApiV1.VERSION);
        assertThat(api.health().status()).isNotBlank();
        assertThat(api.me()).isNotNull();
    }

    private String jobs() {
        JobInput input = JobInput.of(tag, "FileSystem_Local", "PGVector_Output", "/tmp/" + tag).withPipelineMode("rag");
        Versioned<Job> created = api.jobs().create(input, RequestOptions.idempotent(tag + "-create"));
        String id = created.value().id();
        assertThat(created.location()).isEqualTo("/api/v1/jobs/" + id);
        assertThat(created.etag()).isNotBlank();
        assertThat(created.value().state().status()).isEqualTo("Ready");
        assertThat(api.jobs().create(input, RequestOptions.idempotent(tag + "-create")).value().id())
                .as("a retry with the same key returns the same job").isEqualTo(id);

        Versioned<Job> read = api.jobs().get(id);
        assertThat(read.etag()).isEqualTo(created.etag());
        assertThat(api.jobs().getIfChanged(id, read.etag())).as("304").isEmpty();

        Versioned<Job> replaced = api.jobs().replace(id, read.value().toInput().withPath("/tmp/" + tag + "-2"),
                RequestOptions.ifMatch(read.etag()));
        assertThat(replaced.value().path()).isEqualTo("/tmp/" + tag + "-2");
        ApiProblemException stale = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(() ->
                api.jobs().patch(id, Map.of("name", tag + "-x"), RequestOptions.ifMatch(read.etag())));
        assertThat(stale.code()).isEqualTo("precondition-failed");
        assertThat(stale.problem().extensions()).containsEntry("currentEtag", replaced.etag());
        Versioned<Job> withSpec = api.jobs().patch(id, Map.of("crawlSpec", Map.of("maxDepth", 3)),
                RequestOptions.ifMatch(replaced.etag()));
        assertThat(withSpec.value().crawlSpec()).containsEntry("maxDepth", 3);
        java.util.HashMap<String, Object> clear = new java.util.HashMap<>();
        clear.put("crawlSpec", null);
        Versioned<Job> patched = api.jobs().patch(id, clear, RequestOptions.ifMatch(withSpec.etag()));
        assertThat(patched.value().crawlSpec()).as("the SDK sends the merge patch's null, which clears the field")
                .isNullOrEmpty();
        assertThat(patched.value().name()).as("members not sent are kept").isEqualTo(tag);

        assertThat(api.jobs().list(PlatformClient.JobFilter.all().withText(tag), PageRequest.ofSize(5)).items())
                .extracting(Job::id).containsExactly(id);
        assertThat(api.jobs().stream(PlatformClient.JobFilter.all().withText(tag))).extracting(Job::id).containsExactly(id);

        ApiProblemException missing = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(() ->
                api.jobs().get("none-" + tag));
        assertThat(missing.getStatusCode()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("not-found");
        assertThat(missing.problem().getRequestId()).isNotBlank();
        ApiProblemException invalid = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(() ->
                api.jobs().create(JobInput.of("", "Nope", "PGVector_Output", "/tmp"), RequestOptions.none()));
        assertThat(invalid.problem().getErrors()).extracting(e -> e.field()).contains("name");

        when(diagnostics.diagnoseJob(eq(id), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AIOpsDiagnosticService.DiagnosticReport(id, tag, "2026-11-15T10:00:00Z", "WARNING", "Slow embedding",
                        "Ollama queue", Map.of("Embedding", 4200L), List.of("embedding dominates"), List.of("scale the embedder"), List.of()));
        when(observability.fetchJobTraces(id)).thenReturn(new ObservabilityMcpTools.JobTraceResponse(id, 1, 120, "OK",
                List.of(new TelemetryTraceStore.SpanRecord("s1", "t1", id, "Scanning", "crawler", 0, 120, "OK", null, Map.of())), Map.of("Scanning", 120L)));
        when(observability.getErrorLogs(id, "24h")).thenReturn(new ObservabilityMcpTools.ErrorLogsResponse(id, 1,
                List.of(new TelemetryTraceStore.ErrorLogRecord(id, Instant.parse("2026-11-15T09:00:00Z"), "ERROR", "writer", "timeout", null))));
        assertThat(api.jobs().diagnose(id).recommendedActions()).containsExactly("scale the embedder");
        assertThat(api.jobs().traces(id).spans()).hasSize(1);
        assertThat(api.jobs().errors(id, "24h").errorCount()).isEqualTo(1);
        return id;
    }

    private void schedules(String jobId) {
        CronValidationResult validation = api.schedules().validateCron(new CronValidationRequest("0 2 * * *", "Europe/Rome", 3));
        assertThat(validation.valid()).isTrue();
        assertThat(validation.normalizedCron()).isEqualTo("0 0 2 * * *");
        assertThat(validation.nextFireTimes()).hasSize(3);

        Versioned<JobSchedule> set = api.schedules().set(jobId,
                JobScheduleConfig.builder().cron("0 2 * * *").timezone("Europe/Rome").enabled(true)
                        .overlapPolicy(JobScheduleConfig.OverlapPolicy.SKIP)
                        .misfirePolicy(JobScheduleConfig.MisfirePolicy.FIRE_ONCE)
                        .jitterSeconds(30).maxConsecutiveFailures(3).build(),
                RequestOptions.none());
        assertThat(set.etag()).isNotBlank();
        assertThat(set.value().schedule().enabled()).isTrue();

        Versioned<JobSchedule> read = api.schedules().get(jobId);
        assertThat(api.schedules().getIfChanged(jobId, read.etag())).isEmpty();
        assertThat(api.schedules().list(PlatformClient.ScheduleFilter.all().withEnabled(true), PageRequest.ofSize(10)).items())
                .extracting(JobSchedule::jobId).contains(jobId);

        SchedulerOverview overview = api.schedules().overview("24h", 10);
        assertThat(overview.activeSchedules()).isGreaterThanOrEqualTo(1);

        Versioned<JobSchedule> paused = api.schedules().pause(jobId, RequestOptions.ifMatch(read.etag()));
        assertThat(paused.value().schedule().enabled()).isFalse();
        Versioned<JobSchedule> resumed = api.schedules().resume(jobId, RequestOptions.ifMatch(paused.etag()));
        assertThat(resumed.value().schedule().enabled()).isTrue();

        assertThat(api.schedules().dispatches(jobId, null, PageRequest.ofSize(10)).items()).isNotNull();
        api.schedules().delete(jobId, RequestOptions.ifMatch(resumed.etag()));
    }

    private void runs(String jobId) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            BooleanSupplier stop = invocation.getArgument(9);
            for (int i = 0; i < 200 && !stop.getAsBoolean() && release.getCount() > 0; i++) {
                Thread.sleep(25);
            }
            return true;
        }).when(orchestrator).runJob(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        try {
            Versioned<JobRun> started = api.jobs().start(jobId,
                    RequestOptions.idempotent(tag + "-run").withCorrelationId("sdk-correlation"));
            String executionId = started.value().executionId();
            assertThat(started.location()).isEqualTo("/api/v1/job-runs/" + executionId);
            assertThat(api.jobRuns().get(executionId).jobId()).isEqualTo(jobId);
            assertThat(api.jobRuns().list(jobId, PageRequest.first()).items()).extracting(JobRun::executionId)
                    .containsExactly(executionId);
            ApiProblemException running = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(() ->
                    api.jobs().start(jobId, RequestOptions.none()));
            assertThat(running.code()).isEqualTo("job-already-running");
            assertThat(api.jobRuns().pause(executionId).state()).isEqualTo("PAUSED");
            assertThat(api.jobRuns().resume(executionId).state()).isEqualTo("RUNNING");
            assertThat(api.jobs().pause(jobId).state().status()).isEqualTo("Paused");
            assertThat(api.jobRuns().get(executionId).state()).isEqualTo("PAUSED");
            assertThat(api.jobs().resume(jobId).state().status()).isEqualTo("Running");
            assertThat(api.jobRuns().history(executionId).steps()).extracting(JobRunHistory.Step::event)
                    .containsExactly("STARTED", "PAUSED", "RESUMED", "PAUSED", "RESUMED");
            // The same run through the API of every kind of run (#316).
            assertThat(api.runs().list("JOB", "RUNNING", jobId, PageRequest.first()).items())
                    .extracting(org.opencrawling.sdk.v1.model.Run::runId).containsExactly(executionId);
            assertThat(api.runs().get(executionId).kind()).isEqualTo("JOB");
            assertThat(api.runs().pause(executionId).state()).isEqualTo("PAUSED");
            assertThat(api.runs().resume(executionId).state()).isEqualTo("RUNNING");
            assertThat(api.runs().history(executionId).steps()).extracting(JobRunHistory.Step::event)
                    .containsExactly("STARTED", "PAUSED", "RESUMED", "PAUSED", "RESUMED", "PAUSED", "RESUMED");
            assertThat(api.jobRuns().stop(executionId).stopRequested()).isTrue();
            for (int i = 0; i < 100 && api.jobRuns().list(jobId, PageRequest.first()).page().totalItems() > 0; i++) {
                Thread.sleep(50);
            }
            assertThat(api.jobRuns().history(executionId).state()).isEqualTo("STOPPED");
            ApiProblemException notRunning = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(() ->
                    api.jobs().resume(jobId));
            assertThat(notRunning.code()).isEqualTo("job-not-running");
            String again = api.jobs().start(jobId, RequestOptions.none()).value().executionId();
            assertThat(api.runs().stop(again).stopRequested()).isTrue();
            assertThat(api.jobs().stop(jobId).id()).isEqualTo(jobId);
            assertThat(api.jobs().pause(jobId).state().status()).isEqualTo("Paused");
        } finally {
            release.countDown();
            reset(orchestrator);
        }
    }

    private void executions(String jobId) throws Exception {
        Page<org.opencrawling.sdk.models.JobReportSummary> page = api.executions().list(
                PlatformClient.ExecutionFilter.all(), PageRequest.ofSize(1));
        assertThat(page.items()).extracting(org.opencrawling.sdk.models.JobReportSummary::executionId)
                .as("newest first").containsExactly(PlatformApiTestSupport.EXECUTION_ID);
        assertThat(page.hasNext()).isTrue();
        assertThat(api.executions().stream(PlatformClient.ExecutionFilter.all())).hasSize(2);
        assertThat(api.executions().listForJob(jobId, null, null, PageRequest.first()).items()).isEmpty();
        assertThat(api.executions().get(PlatformApiTestSupport.EXECUTION_ID).status()).isEqualTo("SUCCESS");

        PageRequest first = PageRequest.ofSize(50);
        Page<org.opencrawling.sdk.models.DocumentLedgerEntry> ledger = api.executions()
                .documents(PlatformApiTestSupport.EXECUTION_ID, null, null, first);
        assertThat(ledger.page().totalItems()).isEqualTo(120);
        assertThat(api.executions().documents(PlatformApiTestSupport.EXECUTION_ID, null, null, first.next(ledger))
                .page().nextCursor()).as("the third page follows").isNotNull();

        File csv = Files.writeString(tmp.resolve("manifest.csv"), "document_id,status\ndoc-1,SUCCESS\n").toFile();
        when(reports.getExportFile(PlatformApiTestSupport.EXECUTION_ID, "csv")).thenReturn(Optional.of(csv));
        when(reports.isAllowedReportFile(csv)).thenReturn(true);
        try {
            Path saved = api.executions().exportTo(PlatformApiTestSupport.EXECUTION_ID, "csv", tmp.resolve("out.csv"));
            assertThat(Files.readString(saved)).contains("doc-1,SUCCESS");
        } finally {
            // The mock is shared by every test on this cached context; leave its default for them.
            when(reports.getExportFile(anyString(), anyString())).thenReturn(Optional.empty());
        }
        when(reports.executeDuckDbQuery("SELECT 1 AS one")).thenReturn(new JobReportService.QueryResult(List.of("one"), List.of(List.of(1))));
        assertThat(api.executions().query("SELECT 1 AS one").columns()).containsExactly("one");
    }

    private void connectors() {
        String name = "SDK_" + tag;
        ConnectorInput input = ConnectorInput.of(name, "repository", "org.opencrawling.filesystem.FileSystemRepositoryConnector",
                Map.of("basePath", "/data", "password", "s3cret"));
        Versioned<Connector> created = api.connectors().create(input, RequestOptions.idempotent(tag + "-connector"));
        assertThat(created.value().configuration()).containsEntry("password", Connector.MASK);
        Versioned<Connector> read = api.connectors().get(name);
        Versioned<Connector> replaced = api.connectors().replace(name, read.value().toInput().withDescription("SDK test"),
                RequestOptions.ifMatch(read.etag()));
        assertThat(replaced.value().description()).isEqualTo("SDK test");
        assertThat(api.connectors().list("repository", tag, PageRequest.first()).items()).extracting(Connector::name)
                .containsExactly(name);
        assertThat(api.connectors().stream("repository", tag)).hasSize(1);
        assertThat(api.connectors().check(name).success()).isTrue();
        assertThat(api.connectors().checkDefinition(ConnectorInput.of(null, "repository",
                "org.opencrawling.filesystem.FileSystemRepositoryConnector", Map.of("basePath", "/data"))).success())
                .as("a definition without a name yet").isTrue();
        api.connectors().delete(name, true, RequestOptions.none());
        assertThatThrownBy(() -> api.connectors().get(name)).isInstanceOf(ApiProblemException.class);
    }

    private void settingsAndOperations() {
        Versioned<SystemSettings> system = api.settings().system();
        int chunkSize = system.value().chunkSize();
        Versioned<SystemSettings> changed = api.settings().replaceSystem(system.value().withChunkSize(chunkSize + 1),
                RequestOptions.ifMatch(system.etag()));
        assertThat(changed.value().chunkSize()).isEqualTo(chunkSize + 1);
        assertThat(changed.value().tikaEngine()).as("fields the change did not touch are kept").isEqualTo(system.value().tikaEngine());
        api.settings().replaceSystem(changed.value().withChunkSize(chunkSize), RequestOptions.ifMatch(changed.etag()));

        when(transport.getSettings()).thenReturn(TransportSettingsDTO.defaultSettings());
        when(transport.testGrpcConnection("grpc-host", 9095)).thenReturn(new TestGrpcResponseDTO("SUCCESS", true, true, 4, "ok"));
        var transportSettings = api.settings().transport();
        assertThat(transportSettings.value().get("grpcPort").asInt()).isEqualTo(TransportSettingsDTO.defaultSettings().grpcPort());
        assertThat(api.settings().replaceTransport(transportSettings.value(), RequestOptions.ifMatch(transportSettings.etag())).etag())
                .isNotBlank();
        assertThat(api.settings().testGrpc("grpc-host", 9095).get("status").asText()).isEqualTo("SUCCESS");

        assertThat(api.operations().status()).isNotNull();
        assertThat(api.operations().throughput()).isNotNull();
        assertThat(api.operations().logs()).isNotNull();
        when(observability.queryThroughputMetrics("FileSystem_Local"))
                .thenReturn(new ObservabilityMcpTools.ThroughputMetricsResponse("FileSystem_Local", 12.5, 80, 4, List.of()));
        assertThat(api.operations().metrics("FileSystem_Local").averageThroughputDocsPerSec()).isEqualTo(12.5);
    }

    private void tools() throws Exception {
        for (Runnable migration : List.<Runnable>of(() -> api.migrations().planManifoldCf(Map.of()),
                () -> api.migrations().applyManifoldCf(Map.of()))) {
            ApiProblemException refused = (ApiProblemException) org.assertj.core.api.Assertions.catchThrowable(migration::run);
            assertThat(refused.code()).as("the migration request is checked: mcfUrl is required").isEqualTo("validation-failed");
        }

        when(vespa.checkHealth("http://vespa:8080")).thenReturn(new VespaInsightsService.VespaHealthResult(true, "UP"));
        when(vespa.getDocumentCounts("http://vespa:8080")).thenReturn(List.of(new VespaInsightsService.DocumentTypeCount("opencrawling", "1024", 42, true)));
        when(vespa.runQuery("http://vespa:8080", "opencrawling", "contracts", "default"))
                .thenReturn(new VespaInsightsService.VespaQueryResult(List.of(), 0, false, "no hits"));
        when(vespa.deployBundledSchema("http://vespa:19071")).thenReturn(new VespaInsightsService.VespaDeployResult(true, "deployed", "{}"));
        when(vespa.deployCustomSchema(eq("http://vespa:19071"), any(), any())).thenReturn(new VespaInsightsService.VespaDeployResult(true, "custom", "{}"));
        assertThat(api.vespa().health("http://vespa:8080").toString()).contains("UP");
        assertThat(api.vespa().documentCounts("http://vespa:8080").toString()).contains("42");
        assertThat(api.vespa().query(Map.of("endpoint", "http://vespa:8080", "documentType", "opencrawling",
                "queryText", "contracts", "rankProfile", "default")).toString()).contains("no hits");
        assertThat(api.vespa().deployBundledSchema("http://vespa:19071").toString()).contains("deployed");
        Path zip = Files.write(tmp.resolve("app.zip"), new byte[] {'P', 'K', 3, 4});
        assertThat(api.vespa().deployCustomSchema("http://vespa:19071", zip).toString()).contains("custom");

        when(copilot.generate(any())).thenReturn(new TemplateCopilotResponse("# {{title}}", Map.of("title", "Contract")));
        assertThat(api.narrativization().generateTemplate(new CopilotRequest("alfresco",
                List.of(new CopilotRequest.FieldDto("title", "string", "Title")))).template()).isEqualTo("# {{title}}");
    }

    private static DynamicJobService.JobStatus status(String id, DynamicJobService.State state) {
        return new DynamicJobService.JobStatus(id, "exec-" + id, "Contracts", "Alfresco_Content_Services", DynamicJobService.Kind.FOLDER,
                new DynamicJobService.StartView("f-1", "legal", "/Sites/legal"), state, false,
                Instant.parse("2026-11-15T10:00:00Z"), null, null, "agent-7", "dynamic-api", null, null, null);
    }

    private void features() {
        when(dynamicJobs.start(any(), any(), eq(tag + "-dj"), anyString(), eq("sdk-dj")))
                .thenReturn(new DynamicJobService.Accepted("dj-1", "exec-dj-1", "/api/jobs/dynamic/dj-1", DynamicJobService.State.RUNNING, false));
        DynamicJobAccepted accepted = api.dynamicJobs().start(DynamicJobRequest.of("Alfresco_Content_Services", "/Sites/legal"),
                RequestOptions.idempotent(tag + "-dj").withCorrelationId("sdk-dj"));
        assertThat(accepted.jobId()).isEqualTo("dj-1");
        assertThat(accepted.statusUrl()).isEqualTo("/api/v1/dynamic-jobs/dj-1");
        assertThat(accepted.state()).isEqualTo("RUNNING");

        // The SDK gives every POST an Idempotency-Key, so the request carries one.
        when(dynamicJobs.document(any(), any(), org.mockito.ArgumentMatchers.startsWith("sdk-"), anyString(), isNull()))
                .thenReturn(new DynamicJobService.Accepted("dj-5", "exec-dj-5", "/api/jobs/dynamic/dj-5", DynamicJobService.State.RUNNING, false));
        assertThat(api.dynamicJobs().ingestDocument(DocumentIngestRequest.upsert("Alfresco_Content_Services",
                "workspace://SpacesStore/abc"), RequestOptions.none()).jobId()).isEqualTo("dj-5");

        when(dynamicJobs.list(any())).thenReturn(List.of(status("dj-1", DynamicJobService.State.RUNNING),
                status("dj-2", DynamicJobService.State.COMPLETED)));
        assertThat(api.dynamicJobs().list(PageRequest.ofSize(1)).page().totalItems()).isEqualTo(2);
        when(dynamicJobs.status(any(), eq("dj-1"))).thenReturn(status("dj-1", DynamicJobService.State.RUNNING));
        assertThat(api.dynamicJobs().get("dj-1").start()).containsEntry("path", "/Sites/legal");
        when(dynamicJobs.stop(any(), eq("dj-1"))).thenReturn(status("dj-1", DynamicJobService.State.STOPPED));
        assertThat(api.dynamicJobs().stop("dj-1").state()).isEqualTo("STOPPED");
        when(dynamicJobs.approve(any(), eq("dj-3"))).thenReturn(status("dj-3", DynamicJobService.State.RUNNING));
        assertThat(api.dynamicJobs().approve("dj-3").state()).isEqualTo("RUNNING");
        when(dynamicJobs.reject(any(), eq("dj-4"), eq("out of scope"))).thenReturn(status("dj-4", DynamicJobService.State.REJECTED));
        assertThat(api.dynamicJobs().reject("dj-4", "out of scope").state()).isEqualTo("REJECTED");

        when(discovery.listConnectors(any())).thenReturn(List.of(new DiscoveryService.ConnectorInfo("Alfresco_Content_Services", "Alfresco")));
        assertThat(api.discovery().connectors()).extracting(c -> c.name()).containsExactly("Alfresco_Content_Services");
        when(discovery.explore(any(), eq("Alfresco_Content_Services"), eq("/Sites"), eq(2), eq(50), eq(true)))
                .thenReturn(new DiscoveryService.TreeView("Alfresco_Content_Services", List.of(), 3L, false, null));
        assertThat(api.discovery().tree("Alfresco_Content_Services", "/Sites", 2, 50, true).hiddenCount()).isEqualTo(3);
        when(discovery.dryRun(any(), eq("Alfresco_Content_Services"), eq("/Sites/legal"), eq(5), eq(1000)))
                .thenReturn(new DiscoveryService.DryRunView("Alfresco_Content_Services", 120L, "exact", List.of(), 0L, false, null, null));
        assertThat(api.discovery().dryRun("Alfresco_Content_Services",
                new DiscoveryDryRunRequest("/Sites/legal", 5, 1000, null, null)).matchCount()).isEqualTo(120);

        when(sidecars.listStores(any())).thenReturn(List.of(new SidecarService.StoreInfo("Ozone_Migration_Output", "Ozone", "ozone")));
        assertThat(api.sidecars().stores().get(0).get("kind").asText()).isEqualTo("ozone");
        when(sidecars.read(any(), eq("Ozone_Migration_Output"), eq("hr/"), eq(10), eq("c-1")))
                .thenReturn(new SidecarService.SidecarPage("Ozone_Migration_Output", "hr/", List.of(), null, "c-2", false));
        assertThat(api.sidecars().read("Ozone_Migration_Output", "hr/", 10, "c-1").get("nextCursor").asText()).isEqualTo("c-2");
    }

    @Test
    void requestsAreMatchedToTheirOperationsTemplate() {
        assertThat(matches("GET /api/v1/jobs", "GET /api/v1/jobs")).isTrue();
        assertThat(matches("POST /api/v1/jobs/{id}:start", "POST /api/v1/jobs/1763038591234:start")).isTrue();
        assertThat(matches("GET /api/v1/connectors/{name}", "GET /api/v1/connectors/HR%20Share")).isTrue();
        assertThat(matches("POST /api/v1/jobs/{id}:stop", "POST /api/v1/jobs/1:start")).isFalse();
        assertThat(matches("GET /api/v1/jobs/{id}", "GET /api/v1/jobs/1/traces")).isFalse();
        assertThat(matches("GET /api/v1/jobs/{id}", "POST /api/v1/jobs/1")).isFalse();
        assertThat(matches("POST /api/v1/connectors/{name}:check", "POST /api/v1/connectors:check")).isFalse();
    }

    private JsonNode openApi() {
        return JsonMapper.builder().build().readTree(new PlatformApiTestSupport.Http(port)
                .send("GET", "/v3/api-docs/v1", null).body());
    }

    /** "METHOD /api/v1/path/{var}" of every operation, with its id. */
    private static Map<String, String> operationsOf(JsonNode doc) {
        Map<String, String> operations = new TreeMap<>();
        doc.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
                operations.put(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue().get("operationId").asString())));
        return operations;
    }

    /** Whether a recorded request ("POST /api/v1/jobs/123:start") is an operation's template. */
    static boolean matches(String template, String request) {
        String regex = Pattern.quote(template).replaceAll("\\{[^}]+}", "\\\\E[^/:]+\\\\Q");
        return request.matches(regex);
    }
}
