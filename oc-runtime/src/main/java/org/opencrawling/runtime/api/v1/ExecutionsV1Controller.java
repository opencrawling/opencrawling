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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.runtime.api.ReportController;
import org.opencrawling.runtime.reporting.JobReportService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executions: the record of every finished run, with its report, its document ledger and exports, and analytics over
 * the audit lake. Visibility follows the job's, as in the admin UI.
 */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Executions", description = "Finished runs: reports, document ledgers, exports and audit analytics")
public class ExecutionsV1Controller {

    private final ReportController reports;
    private final JobsV1Controller jobs;

    public ExecutionsV1Controller(ReportController reports, JobsV1Controller jobs) {
        this.reports = reports;
        this.jobs = jobs;
    }

    @Schema(description = "A DuckDB SQL query over the audit lake")
    public record QueryInput(
            @NotBlank @Size(max = 20_000)
            @Schema(description = "SQL; read_parquet over the report files", example = "SELECT status, count(*) FROM read_parquet('mode=RAG/**/*.parquet') GROUP BY status")
            String sql) {}

    @Operation(operationId = "listExecutions", summary = "List executions",
            description = "The executions the caller may see, newest first by default.")
    @ApiResponse(responseCode = "200", description = "A page of execution reports")
    @GetMapping("/executions")
    public Page<JobReportSummary> list(
            @Parameter(description = "Only executions of this job") @RequestParam(required = false) String jobId,
            @Parameter(description = "Only executions with this status (SUCCESS, PARTIAL, FAILED)") @RequestParam(required = false) String status,
            @Parameter(description = "Only executions in this pipeline mode (RAG, MIGRATION)") @RequestParam(required = false) String pipelineMode,
            @Parameter(description = "Sort fields: startTime, endTime, durationMs, totalDocuments, jobName; '-' for descending", example = "-startTime") @RequestParam(required = false) String sort,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        List<JobReportSummary> items = all().stream()
                .filter(r -> jobId == null || jobId.equals(r.jobId()))
                .filter(r -> status == null || status.equalsIgnoreCase(r.status()))
                .filter(r -> pipelineMode == null || (r.pipelineMode() != null && pipelineMode.equalsIgnoreCase(r.pipelineMode().name())))
                .toList();
        String order = sort == null ? "-startTime" : sort;
        items = Paging.sort(items, order, Map.of(
                "startTime", Comparator.comparing(JobReportSummary::startTime, Comparator.nullsFirst(Comparator.naturalOrder())),
                "endTime", Comparator.comparing(JobReportSummary::endTime, Comparator.nullsFirst(Comparator.naturalOrder())),
                "durationMs", Comparator.comparingLong(JobReportSummary::durationMs),
                "totalDocuments", Comparator.comparingLong(JobReportSummary::totalDocuments),
                "jobName", Paging.byText(JobReportSummary::jobName)), JobReportSummary::executionId);
        return Paging.page(items, limit, cursor, String.join("|", Objects.toString(jobId, ""),
                Objects.toString(status, ""), Objects.toString(pipelineMode, ""), order), response, JobReportSummary::executionId);
    }

    @Operation(operationId = "listJobExecutions", summary = "List a job's executions",
            description = "The same as /executions?jobId=..., for a job the caller may see.")
    @ApiResponse(responseCode = "200", description = "A page of execution reports")
    @GetMapping("/jobs/{jobId}/executions")
    public Page<JobReportSummary> listForJob(@PathVariable String jobId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) String cursor,
                                             HttpServletResponse response) {
        jobs.find(jobId);
        return list(jobId, status, null, sort, limit, cursor, response);
    }

    @Operation(operationId = "getExecution", summary = "Get an execution's report",
            description = "Totals, timings, costs and the report files of one execution.")
    @ApiResponse(responseCode = "200", description = "The execution report")
    @GetMapping("/executions/{executionId}")
    public JobReportSummary get(@PathVariable String executionId) {
        return find(executionId);
    }

    @Operation(operationId = "listExecutionDocuments", summary = "List an execution's documents",
            description = "The document ledger: one entry per document with its outcome, timings and target.")
    @ApiResponse(responseCode = "200", description = "A page of ledger entries")
    @GetMapping("/executions/{executionId}/documents")
    public Page<DocumentLedgerEntry> documents(
            @PathVariable String executionId,
            @Parameter(description = "Only documents with this outcome (SUCCESS, FAILED, SKIPPED, QUARANTINED)") @RequestParam(required = false) String status,
            @Parameter(description = "Only documents whose id or source URI contains this text") @RequestParam(required = false) String q,
            @Parameter(description = "Page size, 1 to 500 (default 50); keep the same size when following a cursor") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor, read with the same filters and page size") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        JobReportSummary execution = find(executionId);
        int size = limit == null ? Paging.DEFAULT_LIMIT : limit;
        if (size < 1 || size > Paging.MAX_LIMIT) {
            throw ApiProblem.invalid("limit", "must be between 1 and " + Paging.MAX_LIMIT);
        }
        String query = String.join("|", executionId, Objects.toString(status, ""), Objects.toString(q, ""), String.valueOf(size));
        String fingerprint = Etags.hash(query);
        int offset = cursor == null || cursor.isBlank() ? 0 : Paging.decode(cursor, fingerprint);
        int pageNumber = offset / size;
        ResponseEntity<JobReportService.PaginatedLedgerResponse> result = reports.getDocumentLedger(execution.jobId(),
                executionId, status == null ? "ALL" : status.toUpperCase(java.util.Locale.ROOT), q, pageNumber, size);
        JobsV1Controller.requireOk(result, "Execution", executionId);
        JobReportService.PaginatedLedgerResponse ledger = result.getBody();
        List<DocumentLedgerEntry> items = ledger == null ? List.of() : ledger.items();
        long total = ledger == null ? 0 : ledger.total();
        int nextOffset = offset + size;
        String next = nextOffset < total ? Paging.encode(nextOffset, fingerprint) : null;
        if (next != null) {
            response.addHeader("Link", "<" + org.springframework.web.servlet.support.ServletUriComponentsBuilder
                    .fromCurrentRequest().replaceQueryParam("cursor", next).build().toUriString() + ">; rel=\"next\"");
        }
        return new Page<>(items, new Page.PageInfo(size, next, (int) Math.min(total, Integer.MAX_VALUE)));
    }

    @Operation(operationId = "exportExecution", summary = "Download an execution's report file",
            description = "The report as a file: json (summary), csv (document manifest), parquet or avro (document ledger).")
    @ApiResponse(responseCode = "200", description = "The file", content = {
            @Content(mediaType = "application/json"), @Content(mediaType = "text/csv"),
            @Content(mediaType = "application/vnd.apache.parquet"), @Content(mediaType = "application/avro")})
    @GetMapping("/executions/{executionId}/export")
    public ResponseEntity<Resource> export(@PathVariable String executionId,
                                           @Parameter(description = "json, csv, parquet or avro") @RequestParam(defaultValue = "json") String format) {
        JobReportSummary execution = find(executionId);
        String fmt = format.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("json", "csv", "parquet", "avro").contains(fmt)) {
            throw ApiProblem.invalid("format", "must be json, csv, parquet or avro");
        }
        ResponseEntity<Resource> file = reports.exportReport(execution.jobId(), executionId, fmt);
        if (file.getStatusCode().value() == 404) {
            throw new ApiProblem(org.springframework.http.HttpStatus.NOT_FOUND, "export-not-available", "Not found",
                    "This execution has no " + fmt + " report file.");
        }
        JobsV1Controller.requireOk(file, "Execution", executionId);
        return file;
    }

    @Operation(operationId = "queryExecutions", summary = "Query the audit lake with SQL",
            description = "Runs a read-only DuckDB query over the Parquet report files. Admins only.")
    @ApiResponse(responseCode = "200", description = "Columns and rows")
    @PostMapping("/executions:query")
    public Object query(@Valid @RequestBody QueryInput input) {
        // A query refused as not read-only, or one the engine cannot run (syntax, unknown column, missing file), is the
        // caller's to fix: the engine's message says why, with file paths shortened to their names. Anything else is a
        // server fault (500), whose detail stays in the log. Only admins may query.
        return switch (reports.query(input.sql())) {
            case ReportController.QueryOutcome.Rows rows -> rows.result();
            case ReportController.QueryOutcome.Refused refused -> throw new ApiProblem(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalid-query", "Invalid query", shortenPaths(refused.message()));
            case ReportController.QueryOutcome.EngineError engine -> throw new ApiProblem(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "invalid-query", "Invalid query", shortenPaths(engine.message()));
            case ReportController.QueryOutcome.Failed failed -> throw new ApiProblem(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                    "The query could not be run because of a server error; the request id finds it in the log.");
        };
    }

    private List<JobReportSummary> all() {
        ResponseEntity<List<JobReportSummary>> result = reports.getAllReports();
        return result.getBody() == null ? List.of() : result.getBody();
    }

    /** Absolute paths in an engine message, shortened to their last part, so the answer shows no server layout. */
    static String shortenPaths(String message) {
        return message == null ? null : message.replaceAll("(?<![\\w:])/(?:[^/\\s'\"]+/)+([^/\\s'\"]+)", "$1");
    }

    /** Read directly by id: the cost does not grow with the number of executions kept. */
    private JobReportSummary find(String executionId) {
        return reports.findReport(executionId).orElseThrow(() -> ApiProblem.notFound("Execution", executionId));
    }
}
