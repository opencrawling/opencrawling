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
package org.opencrawling.cli.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.cli.util.TableFormatter;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.DocumentLedgerEntry;
import org.opencrawling.sdk.models.JobReportSummary;
import org.opencrawling.sdk.models.PaginatedLedgerResponse;
import org.opencrawling.sdk.models.QueryResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Picocli command group for audit reporting, document-level ledgers, file exports, and analytical DuckDB querying (`oc report`).
 */
@Command(
    name = "report",
    mixinStandardHelpOptions = true,
    description = "Audit reporting, document-level ledgers, file exports, and analytical DuckDB querying (list, get, ledger, export, query)",
    subcommands = {
        ReportCommand.ReportListCommand.class,
        ReportCommand.ReportGetCommand.class,
        ReportCommand.ReportLedgerCommand.class,
        ReportCommand.ReportExportCommand.class,
        ReportCommand.ReportQueryCommand.class
    }
)
public class ReportCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: list, get, ledger, export, query"));
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DecimalFormat COST_FMT = new DecimalFormat("$#,##0.0000");

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainingSec = seconds % 60;
        return minutes + "m " + remainingSec + "s";
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null || bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static String colorStatus(String status) {
        if (status == null) return "-";
        return switch (status.toUpperCase()) {
            case "COMPLETED", "SUCCESS" -> AnsiColors.green(status);
            case "FAILED", "ERROR" -> AnsiColors.red(status);
            case "RUNNING", "SCANNING", "INGESTING" -> AnsiColors.cyan(status);
            default -> AnsiColors.yellow(status);
        };
    }

    @Command(name = "list", aliases = {"ls"}, description = "List executive summaries of all crawl executions")
    public static class ReportListCommand implements Callable<Integer> {

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<JobReportSummary> summaries = client.reports().list();

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaries));
                    return 0;
                }

                if (summaries.isEmpty()) {
                    System.out.println(AnsiColors.yellow("No execution reports found."));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("EXECUTION ID", "JOB ID", "MODE", "STATUS", "START TIME", "DURATION", "DOCS (OK/FAIL)", "CHUNKS", "TOKENS", "EST. COST");

                for (JobReportSummary r : summaries) {
                    String docs = r.totalDocuments() + " (" + r.successfulDocuments() + "/" + r.failedDocuments() + ")";
                    String cost = r.estimatedEmbeddingCostUsd() != null ? COST_FMT.format(r.estimatedEmbeddingCostUsd()) : "-";
                    String chunks = r.totalChunks() != null ? String.valueOf(r.totalChunks()) : "-";
                    String tokens = r.totalTokens() != null ? String.valueOf(r.totalTokens()) : "-";
                    String startTime = r.startTime() != null ? TIME_FMT.format(r.startTime()) : "-";
                    String mode = r.pipelineMode() != null ? r.pipelineMode() : "RAG";

                    table.addRow(
                            r.executionId(),
                            r.jobId(),
                            mode,
                            colorStatus(r.status()),
                            startTime,
                            formatDuration(r.durationMs()),
                            docs,
                            chunks,
                            tokens,
                            cost
                    );
                }

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing reports: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "get", description = "Inspect detailed KPI report summary for a specific execution")
    public static class ReportGetCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Job ID")
        private String positionalJobId;

        @Parameters(index = "1", arity = "0..1", description = "Execution ID")
        private String positionalExecutionId;

        @Option(names = {"--job-id", "-j"}, description = "Job ID")
        private String jobIdOpt;

        @Option(names = {"--id", "--execution-id", "-e"}, description = "Execution ID")
        private String execIdOpt;

        @Option(names = {"--format"}, description = "Output format: text, json", defaultValue = "text")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String jobId = jobIdOpt != null ? jobIdOpt : positionalJobId;
            String executionId = execIdOpt != null ? execIdOpt : positionalExecutionId;

            if (jobId == null || jobId.isBlank() || executionId == null || executionId.isBlank()) {
                System.err.println(AnsiColors.red("Error: Both Job ID and Execution ID must be specified (e.g. oc report get <jobId> <executionId>)"));
                return 2;
            }

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                Optional<JobReportSummary> reportOpt = client.reports().get(jobId, executionId);
                if (reportOpt.isEmpty()) {
                    System.err.println(AnsiColors.red("Report not found for Job " + jobId + " and Execution " + executionId));
                    return 1;
                }

                JobReportSummary r = reportOpt.get();

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
                    return 0;
                }

                System.out.println(AnsiColors.bold("\n--- Execution Report: " + r.executionId() + " ---"));
                System.out.println(AnsiColors.bold("Job ID: ") + r.jobId() + (r.jobName() != null ? " (" + r.jobName() + ")" : ""));
                System.out.println(AnsiColors.bold("Pipeline Mode: ") + (r.pipelineMode() != null ? r.pipelineMode() : "RAG"));
                System.out.println(AnsiColors.bold("Status: ") + colorStatus(r.status()));
                System.out.println(AnsiColors.bold("Connectors: ") + (r.repositoryConnector() != null ? r.repositoryConnector() : "-")
                        + " -> " + (r.outputConnector() != null ? r.outputConnector() : "-"));
                System.out.println(AnsiColors.bold("Timing: ") + (r.startTime() != null ? TIME_FMT.format(r.startTime()) : "-")
                        + " to " + (r.endTime() != null ? TIME_FMT.format(r.endTime()) : "running")
                        + " (Duration: " + formatDuration(r.durationMs()) + ")");

                System.out.println(AnsiColors.bold("\nDocument Throughput:"));
                System.out.println("  Total:      " + r.totalDocuments());
                System.out.println("  Successful: " + AnsiColors.green(String.valueOf(r.successfulDocuments())));
                System.out.println("  Failed:     " + (r.failedDocuments() > 0 ? AnsiColors.red(String.valueOf(r.failedDocuments())) : "0"));
                System.out.println("  Skipped:    " + r.skippedDocuments());

                if ("MIGRATION".equalsIgnoreCase(r.pipelineMode())) {
                    System.out.println(AnsiColors.bold("\nMigration Metrics:"));
                    System.out.println("  Binary Data Transferred: " + formatBytes(r.totalBinaryBytes()));
                    System.out.println("  Disk Space Reclaimed:    " + formatBytes(r.totalReclaimedBytes()));
                    System.out.println("  Safe Pruned Documents:   " + (r.safePrunedCount() != null ? r.safePrunedCount() : 0));
                    System.out.println("  Verified Checksums:      " + (r.verifiedChecksumCount() != null ? r.verifiedChecksumCount() : 0));
                } else {
                    System.out.println(AnsiColors.bold("\nRAG & AI Ingestion Metrics:"));
                    System.out.println("  Total Chunks:            " + (r.totalChunks() != null ? r.totalChunks() : 0));
                    System.out.println("  Total Tokens:            " + (r.totalTokens() != null ? r.totalTokens() : 0));
                    System.out.println("  Estimated AI Cost (USD): " + (r.estimatedEmbeddingCostUsd() != null ? COST_FMT.format(r.estimatedEmbeddingCostUsd()) : "$0.0000"));
                    System.out.println("  Embedding Model:         " + (r.embeddingModel() != null ? r.embeddingModel() : "N/A"));
                }

                System.out.println(AnsiColors.bold("\nReport Artifacts:"));
                System.out.println("  Parquet: " + (r.reportPathParquet() != null ? r.reportPathParquet() : "N/A"));
                System.out.println("  CSV:     " + (r.reportPathCsv() != null ? r.reportPathCsv() : "N/A"));
                System.out.println("  JSON:    " + (r.reportPathJson() != null ? r.reportPathJson() : "N/A"));
                System.out.println();

                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching report: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "ledger", description = "Query granular document-level execution ledger")
    public static class ReportLedgerCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Job ID")
        private String positionalJobId;

        @Parameters(index = "1", arity = "0..1", description = "Execution ID")
        private String positionalExecutionId;

        @Option(names = {"--job-id", "-j"}, description = "Job ID")
        private String jobIdOpt;

        @Option(names = {"--id", "--execution-id", "-e"}, description = "Execution ID")
        private String execIdOpt;

        @Option(names = {"--status", "-s"}, description = "Filter by document status: ALL, SUCCESS, FAILED, SKIPPED", defaultValue = "ALL")
        private String status;

        @Option(names = {"--query", "-q"}, description = "Filter by document ID or URL search term")
        private String query;

        @Option(names = {"--page"}, description = "Page index (0-based)", defaultValue = "0")
        private int page;

        @Option(names = {"--size"}, description = "Page size", defaultValue = "20")
        private int size;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String jobId = jobIdOpt != null ? jobIdOpt : positionalJobId;
            String executionId = execIdOpt != null ? execIdOpt : positionalExecutionId;

            if (jobId == null || jobId.isBlank() || executionId == null || executionId.isBlank()) {
                System.err.println(AnsiColors.red("Error: Both Job ID and Execution ID must be specified (e.g. oc report ledger <jobId> <executionId>)"));
                return 2;
            }

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PaginatedLedgerResponse ledger = client.reports().getDocumentLedger(jobId, executionId, status, query, page, size);

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ledger));
                    return 0;
                }

                if (ledger.items().isEmpty()) {
                    System.out.println(AnsiColors.yellow("No document ledger records matched the criteria."));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("DOCUMENT ID", "SOURCE URI", "STATUS", "CHUNKS", "TOKENS", "DURATION", "ERROR");

                for (DocumentLedgerEntry entry : ledger.items()) {
                    String uri = entry.sourceUri() != null ? entry.sourceUri() : "-";
                    if (uri.length() > 40) {
                        uri = uri.substring(0, 37) + "...";
                    }

                    String chunks = entry.ragChunkCount() != null ? String.valueOf(entry.ragChunkCount()) : "-";
                    String tokens = entry.ragTokenCount() != null ? String.valueOf(entry.ragTokenCount()) : "-";
                    String err = entry.errorMessage() != null ? entry.errorMessage() : "-";
                    if (err.length() > 30) {
                        err = err.substring(0, 27) + "...";
                    }

                    table.addRow(
                            entry.documentId(),
                            uri,
                            colorStatus(entry.status()),
                            chunks,
                            tokens,
                            formatDuration(entry.durationMs()),
                            err
                    );
                }

                System.out.println(table.render());
                System.out.printf(AnsiColors.bold("Showing %d items | Page %d | Total: %d\n"),
                        ledger.items().size(), ledger.page(), ledger.total());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching document ledger: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "export", description = "Download execution report in Parquet, CSV, JSON, or Avro format")
    public static class ReportExportCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Job ID")
        private String positionalJobId;

        @Parameters(index = "1", arity = "0..1", description = "Execution ID")
        private String positionalExecutionId;

        @Option(names = {"--job-id", "-j"}, description = "Job ID")
        private String jobIdOpt;

        @Option(names = {"--id", "--execution-id", "-e"}, description = "Execution ID")
        private String execIdOpt;

        @Option(names = {"--format", "-f"}, description = "Export format: parquet, csv, json, avro", defaultValue = "parquet")
        private String exportFormat;

        @Option(names = {"--out", "-o"}, description = "Destination file path")
        private String outPath;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String jobId = jobIdOpt != null ? jobIdOpt : positionalJobId;
            String executionId = execIdOpt != null ? execIdOpt : positionalExecutionId;

            if (jobId == null || jobId.isBlank() || executionId == null || executionId.isBlank()) {
                System.err.println(AnsiColors.red("Error: Both Job ID and Execution ID must be specified (e.g. oc report export <jobId> <executionId>)"));
                return 2;
            }

            String ext = exportFormat.toLowerCase();
            Path destination = outPath != null ? Paths.get(outPath) : Paths.get("job-" + jobId + "-" + executionId + "." + ext);

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                System.out.println("Downloading " + ext.toUpperCase() + " report for Job " + jobId + " / " + executionId + "...");
                client.reports().downloadToFile(jobId, executionId, ext, destination);

                long sizeBytes = Files.exists(destination) ? Files.size(destination) : 0;
                System.out.println(AnsiColors.green("✔ Report successfully exported to: ")
                        + AnsiColors.bold(destination.toAbsolutePath().toString())
                        + " (" + formatBytes(sizeBytes) + ")");
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error exporting report: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "query", description = "Execute serverless DuckDB analytical SQL query over report Lakehouse files")
    public static class ReportQueryCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "DuckDB SQL Query")
        private String positionalSql;

        @Option(names = {"--sql", "-s"}, description = "DuckDB SQL Query")
        private String sqlOpt;

        @Option(names = {"--format"}, description = "Output format: table, json, csv", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String sql = sqlOpt != null ? sqlOpt : positionalSql;

            if (sql == null || sql.isBlank()) {
                System.err.println(AnsiColors.red("Error: Please provide a SQL query. Example:"));
                System.err.println("  oc report query \"SELECT count(*) AS doc_count, avg(duration_ms) AS avg_duration FROM read_parquet('mode=RAG/**/*.parquet')\"");
                return 2;
            }

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                long startNs = System.nanoTime();
                QueryResult result = client.reports().query(sql);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return 0;
                }

                if ("csv".equalsIgnoreCase(format)) {
                    System.out.println(String.join(",", result.columns()));
                    for (List<Object> row : result.rows()) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < row.size(); i++) {
                            if (i > 0) sb.append(",");
                            Object cell = row.get(i);
                            sb.append(cell != null ? cell.toString() : "");
                        }
                        System.out.println(sb);
                    }
                    return 0;
                }

                TableFormatter table = new TableFormatter();
                table.setHeaders(result.columns().toArray(new String[0]));

                for (List<Object> row : result.rows()) {
                    String[] cells = new String[row.size()];
                    for (int i = 0; i < row.size(); i++) {
                        Object cell = row.get(i);
                        cells[i] = cell != null ? cell.toString() : "-";
                    }
                    table.addRow(cells);
                }

                System.out.println(table.render());
                System.out.println(AnsiColors.green("✔ Query executed in " + elapsedMs + " ms (" + result.rows().size() + " rows returned)"));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error executing query: " + e.getMessage()));
                return 1;
            }
        }
    }
}
