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
package org.opencrawling.runtime.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome;
import org.opencrawling.migrator.mcf.engine.MigrationEngine;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.engine.MigrationSnapshot;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFApiException;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFClient;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;
import org.opencrawling.migrator.mcf.report.ApplyResultSummary;
import org.opencrawling.migrator.mcf.report.ConnectionSummary;
import org.opencrawling.migrator.mcf.report.JobSummary;
import org.opencrawling.migrator.mcf.report.MigrationReportData;
import org.opencrawling.migrator.mcf.report.PlanSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Backs the ManifoldCF Migration wizard in {@code oc-admin-ui}. A thin HTTP wrapper around {@code
 * oc-mcf-migrator}'s {@link MigrationEngine} — the same engine the standalone CLI uses.
 * Response DTOs and their construction ({@link MigrationReportData}) are shared with the CLI's own
 * JSON report renderer, so the two consumers can never drift on shape or {@code recommendedAction}
 * text.
 *
 * <p>{@code /apply} deliberately re-runs extract+plan itself rather than requiring the frontend to
 * round-trip a previously-fetched plan: avoids re-serializing the engine's internal result types
 * across an HTTP boundary just to send them back, and avoids acting on a plan that's gone stale
 * since it was fetched. Its {@link OpenCrawlingWriter} points at this very server (self-loopback
 * over HTTP to {@code /api/connectors} / {@code /api/jobs}) — the same code path the standalone
 * CLI takes against a remote OpenCrawling instance, just addressed at itself.
 *
 * <p>{@code selectedConnections}/{@code selectedJobs} on {@code /apply} reuse the same
 * {@code onlyConnections}/{@code onlyJobs} scoping the CLI's {@code --only-connections}/
 * {@code --only-jobs} flags already provide — an empty/absent selection applies everything
 * supported, same as the CLI's default. The response is scoped to whatever was actually
 * considered for that call.
 *
 * <p>{@code mcfPassword} is never echoed back in any response.
 */
@RestController
@RequestMapping("/api/mcf-migration")
public class McfMigrationController {

    private static final Logger log = LoggerFactory.getLogger(McfMigrationController.class);
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 384;
    private static final int TIMEOUT_SECONDS = 30;

    @Value("${server.port:8080}")
    private int serverPort;

    @PostMapping("/plan")
    public MigrationResponse plan(@RequestBody MigrationRequest request) {
        requireMcfUrl(request);
        MigrationOptions options = toOptions(request, false);
        ManifoldCFClient mcfClient = new ManifoldCFClient(
            request.mcfUrl(), request.mcfUsername(), request.mcfPassword(), TIMEOUT_SECONDS);
        MigrationEngine engine = new MigrationEngine(mcfClient, new ConnectorMapperRegistry(), null, options);

        MigrationSnapshot snapshot = engine.extract();
        MigrationPlan migrationPlan = engine.plan(snapshot);
        log.info("Planned migration from {}: {} connection(s), {} job(s)",
            request.mcfUrl(), migrationPlan.connections().size(), migrationPlan.jobs().size());
        return toResponse(migrationPlan, null);
    }

    @PostMapping("/apply")
    public MigrationResponse apply(@RequestBody MigrationRequest request) {
        requireMcfUrl(request);
        MigrationOptions options = toOptions(request, true);
        ManifoldCFClient mcfClient = new ManifoldCFClient(
            request.mcfUrl(), request.mcfUsername(), request.mcfPassword(), TIMEOUT_SECONDS);
        OpenCrawlingWriter writer = new OpenCrawlingWriter(
            "http://localhost:" + serverPort, null, null, TIMEOUT_SECONDS);
        MigrationEngine engine = new MigrationEngine(mcfClient, new ConnectorMapperRegistry(), writer, options);

        MigrationSnapshot snapshot = engine.extract();
        MigrationPlan migrationPlan = engine.plan(snapshot);
        ApplyOutcome outcome = engine.apply(migrationPlan);
        log.info("Applied migration from {}: {} connection(s) applied, {} job(s) applied",
            request.mcfUrl(), outcome.connectionResults().size(), outcome.jobResults().size());
        return toResponse(migrationPlan, outcome);
    }

    private static void requireMcfUrl(MigrationRequest request) {
        if (request.mcfUrl() == null || request.mcfUrl().isBlank()) {
            throw new ManifoldCFApiException("mcfUrl is required");
        }
    }

    @ExceptionHandler(ManifoldCFApiException.class)
    public ResponseEntity<Map<String, String>> handleManifoldCfError(ManifoldCFApiException e) {
        log.warn("ManifoldCF call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    private MigrationOptions toOptions(MigrationRequest request, boolean apply) {
        int dimensions = request.defaultEmbeddingDimensions() != null
            ? request.defaultEmbeddingDimensions() : DEFAULT_EMBEDDING_DIMENSIONS;
        List<String> onlyConnections = apply && request.selectedConnections() != null ? request.selectedConnections() : List.of();
        List<String> onlyJobs = apply && request.selectedJobs() != null ? request.selectedJobs() : List.of();
        return new MigrationOptions(
            request.mcfUrl(), request.mcfUsername(), request.mcfPassword(),
            "http://localhost:" + serverPort, null,
            apply, "unused-in-api-mode.md", dimensions, onlyConnections, onlyJobs, false, TIMEOUT_SECONDS, Map.of());
    }

    private MigrationResponse toResponse(MigrationPlan plan, ApplyOutcome outcome) {
        List<ConnectionSummary> connections = MigrationReportData.connectionSummaries(plan);
        List<JobSummary> jobs = MigrationReportData.jobSummaries(plan);
        PlanSummary summary = MigrationReportData.summary(plan);

        Map<String, ApplyResultSummary> connectionResults = null;
        Map<String, ApplyResultSummary> jobResults = null;
        if (outcome != null) {
            connectionResults = MigrationReportData.applyResultSummaries(outcome.connectionResults());
            jobResults = MigrationReportData.applyResultSummaries(outcome.jobResults());
        }

        return new MigrationResponse(connections, jobs, summary, connectionResults, jobResults);
    }

    public record MigrationRequest(
        String mcfUrl,
        String mcfUsername,
        String mcfPassword,
        Integer defaultEmbeddingDimensions,
        List<String> selectedConnections,
        List<String> selectedJobs
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MigrationResponse(
        List<ConnectionSummary> connections,
        List<JobSummary> jobs,
        PlanSummary summary,
        Map<String, ApplyResultSummary> connectionResults,
        Map<String, ApplyResultSummary> jobResults
    ) {
    }
}
