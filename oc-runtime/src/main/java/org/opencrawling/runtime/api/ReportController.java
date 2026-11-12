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

import org.springframework.beans.factory.annotation.Autowired;
import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.runtime.reporting.JobReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST Controller providing execution reporting summaries, paginated document ledgers,
 * one-click file exports (CSV, Parquet, JSON), and serverless analytical querying.
 */
@Tag(name = "Reports & Lakehouse Audit", description = "Execution reports, document ledgers, Parquet/Avro/CSV exports, and serverless DuckDB analytics")
@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");
    private static final Set<String> ALLOWED_EXPORT_FORMATS = Set.of("csv", "parquet", "avro", "json");

    private final JobReportService reportService;

    /** Absent when a test builds the controller itself; then every report is visible, as with authorization off. */
    @Autowired(required = false)
    private JobVisibility visibility;

    public ReportController(JobReportService reportService) {
        this.reportService = reportService;
    }

    void setVisibility(JobVisibility visibility) {
        this.visibility = visibility;
    }

    private boolean visible(JobReportSummary summary) {
        return visibility == null || visibility.canReadReport(summary);
    }

    /**
     * Whether the caller may read this execution's ledger and exports. They are looked up by execution id, so the job
     * id in the path decides nothing: the execution's own summary does. Without a summary, only admins may read it.
     */
    private boolean executionVisible(String executionId) {
        if (visibility == null) {
            return true;
        }
        return reportService.getSummary(executionId).map(this::visible).orElseGet(visibility::isAdmin);
    }

    private static boolean isValidId(String id) {
        return id != null && SAFE_ID_PATTERN.matcher(id).matches() && !id.contains("..");
    }

    @Operation(summary = "List all job reports", description = "Retrieves execution report summaries for all completed or running jobs.")
    @ApiResponse(responseCode = "200", description = "List of report summaries successfully retrieved")
    @GetMapping
    public ResponseEntity<List<JobReportSummary>> getAllReports() {
        List<JobReportSummary> summaries = reportService.getAllSummaries();
        if (visibility != null) {
            summaries = summaries.stream().filter(visibility::canReadReport).toList();
        }
        return ResponseEntity.ok(summaries);
    }

    @Operation(summary = "Get execution report summary", description = "Retrieves the execution summary and key performance indicators for a specific job run.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job execution report summary successfully retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid jobId or executionId"),
        @ApiResponse(responseCode = "404", description = "Report not found")
    })
    @GetMapping("/{jobId}/{executionId}")
    public ResponseEntity<JobReportSummary> getReport(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId,
            @Parameter(description = "Execution unique identifier", example = "exec-abc12345") @PathVariable String executionId) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        return reportService.getSummary(executionId)
                .filter(this::visible)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get paginated document ledger", description = "Queries the detailed document execution ledger with optional status filtering and text search.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated document ledger entries"),
        @ApiResponse(responseCode = "400", description = "Invalid jobId or executionId")
    })
    @GetMapping("/{jobId}/{executionId}/documents")
    public ResponseEntity<JobReportService.PaginatedLedgerResponse> getDocumentLedger(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId,
            @Parameter(description = "Execution unique identifier", example = "exec-abc12345") @PathVariable String executionId,
            @Parameter(description = "Filter by document processing status (ALL, SUCCESS, FAILED, SKIPPED, QUARANTINED)", example = "ALL") @RequestParam(defaultValue = "ALL") String status,
            @Parameter(description = "Text query search on document ID or source URI", example = "") @RequestParam(required = false) String query,
            @Parameter(description = "Page number (0-indexed)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-1000)", example = "50") @RequestParam(defaultValue = "50") int size) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        if (!executionVisible(executionId)) {
            return ResponseEntity.notFound().build();
        }
        int safePage = Math.max(0, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(size, 1_000));
        return ResponseEntity.ok(reportService.getDocumentEntries(executionId, status, query, safePage, safeSize));
    }

    @Operation(summary = "Export document ledger file", description = "Downloads the document audit ledger in the specified format: CSV, Parquet, Avro, or JSON.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Audit ledger file download"),
        @ApiResponse(responseCode = "400", description = "Invalid parameters or unsupported format"),
        @ApiResponse(responseCode = "404", description = "Report export file not found")
    })
    @GetMapping("/{jobId}/{executionId}/export")
    public ResponseEntity<Resource> exportReport(
            @Parameter(description = "Job unique identifier", example = "1") @PathVariable String jobId,
            @Parameter(description = "Execution unique identifier", example = "exec-abc12345") @PathVariable String executionId,
            @Parameter(description = "Export file format (csv, parquet, avro, json)", example = "parquet") @RequestParam(defaultValue = "json") String format) {
        if (!isValidId(jobId) || !isValidId(executionId)) {
            return ResponseEntity.badRequest().build();
        }
        if (!executionVisible(executionId)) {
            return ResponseEntity.notFound().build();
        }
        String fmt = format != null ? format.trim().toLowerCase() : "json";
        if (!ALLOWED_EXPORT_FORMATS.contains(fmt)) {
            return ResponseEntity.badRequest().build();
        }

        return reportService.getExportFile(executionId, fmt)
                .filter(reportService::isAllowedReportFile)
                .map(file -> {
                    Resource resource = new FileSystemResource(file);
                    String contentType = switch (fmt) {
                        case "csv" -> "text/csv; charset=UTF-8";
                        case "parquet" -> "application/vnd.apache.parquet";
                        case "avro" -> "application/avro";
                        default -> MediaType.APPLICATION_JSON_VALUE;
                    };

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(resource);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Execute serverless DuckDB SQL query", description = "Executes a read-only analytical SQL query powered by DuckDB over generated Parquet audit logs.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Query result table with columns and rows"),
        @ApiResponse(responseCode = "400", description = "Invalid or non-read-only SQL query"),
        @ApiResponse(responseCode = "500", description = "Query execution runtime failure")
    })
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody QueryRequest request) {
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body("SQL query must not be empty");
        }
        try {
            JobReportService.validateReadOnlySql(request.sql());
            return ResponseEntity.ok(reportService.executeDuckDbQuery(request.sql()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid SQL query: " + e.getMessage());
        } catch (Throwable e) {
            log.error("DuckDB SQL query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Query execution failed: " + e.getMessage());
        }
    }

    @Schema(description = "Serverless analytical SQL query request")
    public record QueryRequest(
            @Schema(description = "Read-only SQL query to execute against Parquet audit logs", example = "SELECT status, count(*) FROM read_parquet('data/reports/*.parquet') GROUP BY status")
            String sql) {}
}
