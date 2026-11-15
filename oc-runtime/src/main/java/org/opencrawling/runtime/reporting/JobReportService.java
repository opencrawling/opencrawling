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
package org.opencrawling.runtime.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.JobReportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service providing reporting retrieval, document-level filtering and pagination,
 * file export resolution, and serverless DuckDB analytical queries over Parquet files.
 */
@Service
public class JobReportService {

    private static final Logger log = LoggerFactory.getLogger(JobReportService.class);
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");
    private static final Set<String> ALLOWED_FORMATS = Set.of("csv", "parquet", "avro", "json");
    private static final Pattern READ_ONLY_PATTERN = Pattern.compile("(?is)^\\s*(SELECT|WITH)\\b.*");
    private static final Pattern BLOCKED_KEYWORDS = Pattern.compile(
            "(?is)\\b(COPY|EXPORT|IMPORT|ATTACH|DETACH|INSTALL|LOAD|CREATE|DROP|ALTER|INSERT|UPDATE|DELETE|PRAGMA|CALL|SET)\\b"
    );

    private final JobReportProperties properties;
    private final JobLedgerAccumulator accumulator;
    private final ObjectMapper objectMapper;

    public JobReportService(JobReportProperties properties, JobLedgerAccumulator accumulator) {
        this.properties = properties;
        this.accumulator = accumulator;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static String sanitizeExecutionId(String executionId) {
        if (executionId == null || !SAFE_ID_PATTERN.matcher(executionId).matches() || executionId.contains("..")) {
            throw new IllegalArgumentException("Invalid executionId: " + executionId);
        }
        return new File(executionId).getName();
    }

    public static String normalizeSql(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.trim().replaceAll(";+\\s*$", "").trim();
    }

    public static void validateReadOnlySql(String sql) {
        String normalized = normalizeSql(sql);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be empty");
        }
        if (normalized.contains(";")) {
            throw new IllegalArgumentException("Multi-statement queries containing semicolons are forbidden");
        }
        if (!READ_ONLY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Only read-only SELECT or WITH queries are permitted");
        }
        if (BLOCKED_KEYWORDS.matcher(normalized).find()) {
            throw new IllegalArgumentException("SQL query contains forbidden administrative or mutation keywords");
        }
    }

    public boolean isAllowedReportFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }
        try {
            Path targetPath = file.getCanonicalFile().toPath();
            for (File dir : getCandidateSearchDirs()) {
                if (dir != null && dir.exists()) {
                    Path allowedBase = dir.getCanonicalFile().toPath();
                    if (targetPath.startsWith(allowedBase)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public List<JobReportSummary> getAllSummaries() {
        Set<File> searchDirs = getCandidateSearchDirs();
        Map<String, JobReportSummary> summariesByExecId = new LinkedHashMap<>();

        for (File dir : searchDirs) {
            File[] summaryFiles = dir.listFiles((d, name) -> name.startsWith("summary_") && name.endsWith(".json"));
            if (summaryFiles != null) {
                for (File file : summaryFiles) {
                    try {
                        JobReportSummary summary = objectMapper.readValue(file, JobReportSummary.class);
                        summariesByExecId.putIfAbsent(summary.executionId(), summary);
                    } catch (Exception e) {
                        log.warn("Failed to read report summary from {}: {}", file.getName(), e.getMessage());
                    }
                }
            }
        }

        if (accumulator != null) {
            for (JobExecutionCompletedEvent event : accumulator.getAllCompletedEvents()) {
                summariesByExecId.computeIfAbsent(event.executionId(), id -> toSummary(event));
            }
        }

        List<JobReportSummary> list = new ArrayList<>(summariesByExecId.values());
        // Sort descending by start time
        list.sort((a, b) -> b.startTime().compareTo(a.startTime()));
        return list;
    }

    public Optional<JobReportSummary> getSummary(String executionId) {
        String safeExecId = sanitizeExecutionId(executionId);
        Set<File> searchDirs = getCandidateSearchDirs();

        for (File dir : searchDirs) {
            try {
                File baseDir = dir.getCanonicalFile();
                File file = new File(baseDir, String.format("summary_%s.json", safeExecId)).getCanonicalFile();
                if (file.toPath().startsWith(baseDir.toPath()) && file.exists()) {
                    try {
                        return Optional.of(objectMapper.readValue(file, JobReportSummary.class));
                    } catch (Exception e) {
                        log.error("Failed to parse summary for execution {}: {}", safeExecId.replaceAll("[\r\n]", ""), e.getMessage());
                    }
                }
            } catch (Exception ignored) {}
        }

        if (accumulator != null) {
            JobExecutionCompletedEvent event = accumulator.getCompletedEvent(safeExecId);
            if (event != null) {
                return Optional.of(toSummary(event));
            }
        }

        return Optional.empty();
    }

    private JobReportSummary toSummary(JobExecutionCompletedEvent event) {
        long durationMs = java.time.Duration.between(event.startTime(), event.endTime()).toMillis();
        String status = event.failedDocuments() == 0 ? "SUCCESS"
                : (event.successfulDocuments() > 0 ? "PARTIAL" : "FAILED");
        java.time.LocalDate date = java.time.LocalDate.ofInstant(event.startTime(), java.time.ZoneOffset.UTC);
        String relativeParquet = String.format("mode=%s/year=%d/month=%02d/day=%02d/job_%s_exec_%s.parquet",
                event.pipelineMode().name(), date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                event.jobId(), event.executionId());
        return new JobReportSummary(
                event.jobId(),
                event.executionId(),
                event.jobName(),
                event.pipelineMode(),
                event.repositoryConnector(),
                event.outputConnector(),
                status,
                event.startTime(),
                event.endTime(),
                durationMs,
                event.totalDocuments(),
                event.successfulDocuments(),
                event.failedDocuments(),
                event.skippedDocuments(),
                (Long) event.summaryMetrics().get("totalChunks"),
                (Long) event.summaryMetrics().get("totalTokens"),
                (Double) event.summaryMetrics().get("estimatedEmbeddingCostUsd"),
                (String) event.summaryMetrics().get("embeddingModel"),
                (Long) event.summaryMetrics().get("totalBinaryBytes"),
                (Long) event.summaryMetrics().get("totalReclaimedBytes"),
                (Long) event.summaryMetrics().get("safePrunedCount"),
                (Long) event.summaryMetrics().get("verifiedChecksumCount"),
                relativeParquet,
                String.format("manifest_%s.csv", event.executionId()),
                String.format("summary_%s.json", event.executionId()),
                event.provenance()
        );
    }

    public PaginatedLedgerResponse getDocumentEntries(String executionId, String statusFilter, String query, int page, int size) {
        String safeExecId = sanitizeExecutionId(executionId);
        List<DocumentLedgerEntry> all = accumulator.getEntries(safeExecId);
        if (all == null || all.isEmpty()) {
            all = loadEntriesFromDisk(safeExecId);
        }

        List<DocumentLedgerEntry> filtered = all.stream()
                .filter(entry -> {
                    if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
                        if (!statusFilter.equalsIgnoreCase(entry.status())) {
                            return false;
                        }
                    }
                    if (query != null && !query.isBlank()) {
                        String q = query.toLowerCase();
                        boolean matchId = entry.documentId() != null && entry.documentId().toLowerCase().contains(q);
                        boolean matchUri = entry.sourceUri() != null && entry.sourceUri().toLowerCase().contains(q);
                        if (!matchId && !matchUri) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        int total = filtered.size();
        int safePage = Math.max(0, Math.min(page, 100_000));
        int safeSize = Math.max(1, Math.min(size, 1_000));
        long offset = (long) safePage * safeSize;
        int fromIndex = (int) Math.min(offset, (long) total);
        int toIndex = Math.min(fromIndex + safeSize, total);

        List<DocumentLedgerEntry> pageItems = filtered.subList(fromIndex, toIndex);
        return new PaginatedLedgerResponse(pageItems, total, safePage, safeSize);
    }

    public Optional<File> getExportFile(String executionId, String format) {
        String safeExecId = sanitizeExecutionId(executionId);
        Set<File> searchDirs = getCandidateSearchDirs();
        String fmt = format != null ? format.toLowerCase().trim() : "json";
        if (!ALLOWED_FORMATS.contains(fmt)) {
            return Optional.empty();
        }

        for (File dir : searchDirs) {
            try {
                File baseDir = dir.getCanonicalFile();
                Optional<File> found = switch (fmt) {
                    case "csv" -> {
                        File file = new File(baseDir, String.format("manifest_%s.csv", safeExecId)).getCanonicalFile();
                        yield (file.toPath().startsWith(baseDir.toPath()) && file.exists()) ? Optional.of(file) : Optional.empty();
                    }
                    case "parquet" -> {
                        File[] matches = findParquetFiles(baseDir, safeExecId);
                        yield (matches != null && matches.length > 0) ? Optional.of(matches[0]) : Optional.empty();
                    }
                    case "avro" -> {
                        File[] matches = findAvroFiles(baseDir, safeExecId);
                        yield (matches != null && matches.length > 0) ? Optional.of(matches[0]) : Optional.empty();
                    }
                    default -> {
                        File file = new File(baseDir, String.format("summary_%s.json", safeExecId)).getCanonicalFile();
                        yield (file.toPath().startsWith(baseDir.toPath()) && file.exists()) ? Optional.of(file) : Optional.empty();
                    }
                };
                if (found.isPresent()) {
                    return found;
                }
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    private File[] findParquetFiles(File baseDir, String executionId) {
        String safeExecId = sanitizeExecutionId(executionId);
        List<File> result = new ArrayList<>();
        findFilesRecursively(baseDir, safeExecId + ".parquet", result);
        return result.toArray(new File[0]);
    }

    private File[] findAvroFiles(File baseDir, String executionId) {
        String safeExecId = sanitizeExecutionId(executionId);
        List<File> result = new ArrayList<>();
        findFilesRecursively(baseDir, safeExecId + ".avro", result);
        return result.toArray(new File[0]);
    }

    private void findFilesRecursively(File dir, String suffix, List<File> result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        try {
            Path basePath = dir.getCanonicalFile().toPath();
            for (File f : files) {
                File canon = f.getCanonicalFile();
                if (!canon.toPath().startsWith(basePath)) {
                    continue;
                }
                if (canon.isDirectory()) {
                    findFilesRecursively(canon, suffix, result);
                } else if (canon.getName().endsWith(suffix)) {
                    result.add(canon);
                }
            }
        } catch (Exception ignored) {}
    }

    public Set<File> getCandidateSearchDirs() {
        File primary = properties.getStorage().resolveLocalDir();
        Set<File> searchDirs = new LinkedHashSet<>();
        if (primary != null && primary.exists()) {
            searchDirs.add(primary);
        }
        // Only include workspace fallbacks if primary configuration is relative (local development mode)
        File configured = new File(properties.getStorage().getLocalDir());
        if (!configured.isAbsolute()) {
            for (String fallback : List.of("target/reports", "oc-runtime/target/reports", "data/reports", "oc-runtime/data/reports")) {
                File f = new File(fallback);
                if (f.exists() && f.isDirectory()) {
                    searchDirs.add(f);
                }
            }
        }
        return searchDirs;
    }

    public QueryResult executeDuckDbQuery(String sql) throws Exception {
        String cleanSql = normalizeSql(sql);
        validateReadOnlySql(cleanSql);

        File reportsDir = properties.getStorage().resolveLocalDir();

        if (reportsDir != null && reportsDir.exists()) {
            cleanupEmptyParquetFiles(reportsDir);
        }
        for (File dir : getCandidateSearchDirs()) {
            cleanupEmptyParquetFiles(dir);
        }

        Set<String> searchPaths = new LinkedHashSet<>();
        if (reportsDir != null && reportsDir.exists()) {
            searchPaths.add(reportsDir.getAbsolutePath());
            if (reportsDir.getParentFile() != null) {
                searchPaths.add(reportsDir.getParentFile().getAbsolutePath());
            }
        }
        for (File dir : getCandidateSearchDirs()) {
            searchPaths.add(dir.getAbsolutePath());
            if (dir.getParentFile() != null) {
                searchPaths.add(dir.getParentFile().getAbsolutePath());
            }
        }
        searchPaths.add(new File(".").getAbsolutePath());

        String resolvedSql = resolveParquetPathInSql(cleanSql, reportsDir, searchPaths);

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = executeQuerySafely(stmt, resolvedSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }

                return new QueryResult(columns, rows);
            }
        }
    }

    private ResultSet executeQuerySafely(Statement stmt, String query) throws Exception {
        Method executeQueryMethod = Statement.class.getMethod("executeQuery", String.class);
        return (ResultSet) executeQueryMethod.invoke(stmt, query);
    }

    private String resolveParquetPathInSql(String sql, File reportsDir, Set<String> searchPaths) {
        if (sql == null || !sql.toLowerCase().contains("read_parquet")) {
            return sql;
        }

        Pattern pattern = Pattern.compile("(?i)read_parquet\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
        Matcher matcher = pattern.matcher(sql);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String path = matcher.group(1);
            if (path == null || path.isBlank() || path.contains("..") || path.contains("\0")) {
                continue;
            }
            File candidate = new File(path);

            if (!candidate.isAbsolute()) {
                String foundPath = null;
                // 1. Check in reportsDir first
                if (reportsDir != null && reportsDir.exists()) {
                    try {
                        Path baseDirPath = reportsDir.getCanonicalFile().toPath();
                        if (!path.contains("*")) {
                            File f = new File(reportsDir, path).getCanonicalFile();
                            if (f.toPath().startsWith(baseDirPath) && f.exists()) {
                                foundPath = f.getAbsolutePath();
                            }
                        }
                    } catch (Exception ignored) {}
                }
                // 2. Check candidate search paths
                if (foundPath == null && !path.contains("*")) {
                    for (String basePath : searchPaths) {
                        try {
                            File baseDir = new File(basePath).getCanonicalFile();
                            File f = new File(baseDir, path).getCanonicalFile();
                            if (f.toPath().startsWith(baseDir.toPath()) && f.exists()) {
                                foundPath = f.getAbsolutePath();
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                // 3. Fallback: if filename ends with .parquet, find recursively across candidate dirs
                if (foundPath == null && path.endsWith(".parquet") && !path.contains("*")) {
                    String fileName = new File(path).getName();
                    if (SAFE_ID_PATTERN.matcher(fileName.replace(".parquet", "")).matches() && !fileName.contains("..")) {
                        for (String basePath : searchPaths) {
                            try {
                                File base = new File(basePath).getCanonicalFile();
                                if (base.exists() && base.isDirectory()) {
                                    List<File> matches = new ArrayList<>();
                                    findFilesRecursively(base, fileName, matches);
                                    if (!matches.isEmpty()) {
                                        foundPath = matches.get(0).getAbsolutePath();
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                // 4. Wildcard glob pattern resolution: if path contains '*', resolve against reportsDir or searchPaths
                if (foundPath == null && path.contains("*") && !path.contains("..")) {
                    if (reportsDir != null && reportsDir.exists()) {
                        File globCandidate = new File(reportsDir, path);
                        try {
                            if (globCandidate.toPath().normalize().startsWith(reportsDir.toPath().normalize())) {
                                foundPath = globCandidate.getPath();
                            }
                        } catch (Exception ignored) {}
                    }
                    if (foundPath == null) {
                        for (String basePath : searchPaths) {
                            try {
                                File base = new File(basePath).getCanonicalFile();
                                if (base.exists() && base.isDirectory()) {
                                    File globCandidate = new File(base, path);
                                    if (globCandidate.toPath().normalize().startsWith(base.toPath())) {
                                        foundPath = globCandidate.getPath();
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (foundPath != null) {
                    String escaped = foundPath.replace("'", "''");
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("read_parquet('" + escaped + "')"));
                    continue;
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void cleanupEmptyParquetFiles(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                cleanupEmptyParquetFiles(f);
            } else if (f.getName().endsWith(".parquet") && f.length() == 0) {
                log.warn("Removing orphaned 0-byte parquet file: {}", f.getAbsolutePath());
                f.delete();
                File crc = new File(f.getParentFile(), "." + f.getName() + ".crc");
                if (crc.exists()) {
                    crc.delete();
                }
            }
        }
    }

    public List<DocumentLedgerEntry> loadEntriesFromDisk(String executionId) {
        String safeExecId = sanitizeExecutionId(executionId);
        // 1. Try reading compliance CSV manifest
        Optional<File> csvOpt = getExportFile(safeExecId, "csv");
        if (csvOpt.isPresent()) {
            File file = csvOpt.get();
            if (isAllowedReportFile(file)) {
                List<DocumentLedgerEntry> fromCsv = loadEntriesFromCsv(file);
                if (!fromCsv.isEmpty()) {
                    log.info("Loaded {} document ledger entries from CSV manifest {}", fromCsv.size(), file.getName());
                    return fromCsv;
                }
            }
        }

        // 2. Fallback: try DuckDB query on columnar Parquet
        Optional<File> parquetOpt = getExportFile(safeExecId, "parquet");
        if (parquetOpt.isPresent()) {
            File file = parquetOpt.get();
            if (isAllowedReportFile(file)) {
                try {
                    List<DocumentLedgerEntry> fromParquet = loadEntriesFromParquet(file);
                    if (!fromParquet.isEmpty()) {
                        log.info("Loaded {} document ledger entries from Parquet file {}", fromParquet.size(), file.getName());
                        return fromParquet;
                    }
                } catch (Exception e) {
                    log.warn("Failed to load ledger entries from parquet file {}: {}", file.getName(), e.getMessage());
                }
            }
        }

        return Collections.emptyList();
    }

    private List<DocumentLedgerEntry> loadEntriesFromCsv(File file) {
        List<DocumentLedgerEntry> result = new ArrayList<>();
        if (!isAllowedReportFile(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.getCanonicalFile().toPath(), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 8) continue;
                try {
                    String jobId = cols.get(0);
                    String execId = cols.get(1);
                    PipelineMode mode = PipelineMode.parseStrict(cols.get(2));
                    String docId = cols.get(3);
                    String sourceUri = cols.get(4);
                    String connectorSource = cols.get(5);
                    String connectorTarget = cols.get(6);
                    String status = cols.get(7);
                    long durationMs = parseLong(cols.size() > 8 ? cols.get(8) : null, 0L);
                    java.time.Instant timestamp = parseInstant(cols.size() > 9 ? cols.get(9) : null);
                    String errorCode = cols.size() > 10 ? emptyToNull(cols.get(10)) : null;
                    String errorMessage = cols.size() > 11 ? emptyToNull(cols.get(11)) : null;

                    Integer ragChunkCount = cols.size() > 12 ? parseInt(cols.get(12)) : null;
                    Integer ragTokenCount = cols.size() > 13 ? parseInt(cols.get(13)) : null;
                    String ragEmbeddingModel = cols.size() > 14 ? emptyToNull(cols.get(14)) : null;

                    Long migBinarySizeBytes = cols.size() > 15 ? parseLongObj(cols.get(15)) : null;
                    String migSha256Checksum = cols.size() > 16 ? emptyToNull(cols.get(16)) : null;
                    String migTargetKey = cols.size() > 17 ? emptyToNull(cols.get(17)) : null;
                    Boolean migSafePruned = cols.size() > 18 ? parseBoolObj(cols.get(18)) : null;
                    Long migReclaimedBytes = cols.size() > 19 ? parseLongObj(cols.get(19)) : null;

                    result.add(new DocumentLedgerEntry(
                            jobId, execId, mode, docId, sourceUri, connectorSource, connectorTarget,
                            status, durationMs, timestamp, errorCode, errorMessage,
                            ragChunkCount, ragTokenCount, ragEmbeddingModel, 1024, "vector_store",
                            migBinarySizeBytes, migSha256Checksum, null, migTargetKey,
                            null, null, migSafePruned, migReclaimedBytes
                    ));
                } catch (Exception parseEx) {
                    log.debug("Skipping malformed CSV line in {}: {}", file.getName(), parseEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse manifest CSV {}: {}", file.getName(), e.getMessage());
        }
        return result;
    }

    private List<DocumentLedgerEntry> loadEntriesFromParquet(File parquetFile) throws Exception {
        String sql = "SELECT * FROM read_parquet('" + parquetFile.getAbsolutePath().replace("'", "''") + "')";
        QueryResult qr = executeDuckDbQuery(sql);
        List<DocumentLedgerEntry> result = new ArrayList<>();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < qr.columns().size(); i++) {
            colIndex.put(qr.columns().get(i).toLowerCase(), i);
        }

        for (List<Object> row : qr.rows()) {
            try {
                String jobId = getString(row, colIndex, "job_id");
                String execId = getString(row, colIndex, "execution_id");
                PipelineMode mode = PipelineMode.parseStrict(getString(row, colIndex, "pipeline_mode"));
                String docId = getString(row, colIndex, "document_id");
                String sourceUri = getString(row, colIndex, "source_uri");
                String connSource = getString(row, colIndex, "connector_source");
                String connTarget = getString(row, colIndex, "connector_target");
                String status = getString(row, colIndex, "status");
                long duration = getLong(row, colIndex, "duration_ms", 0L);
                java.time.Instant timestamp = parseInstant(getString(row, colIndex, "timestamp_utc"));
                String errCode = getString(row, colIndex, "error_code");
                String errMsg = getString(row, colIndex, "error_message");

                Integer ragChunks = getInt(row, colIndex, "rag_chunk_count");
                Integer ragTokens = getInt(row, colIndex, "rag_token_count");
                String ragModel = getString(row, colIndex, "rag_embedding_model");
                Integer ragDims = getInt(row, colIndex, "rag_embedding_dimensions");
                String ragTable = getString(row, colIndex, "rag_vector_table");

                Long migSize = getLongObj(row, colIndex, "mig_binary_size_bytes");
                String migSha = getString(row, colIndex, "mig_sha256_checksum");
                String migBucket = getString(row, colIndex, "mig_target_bucket");
                String migKey = getString(row, colIndex, "mig_target_key");
                Boolean migSidecar = getBoolObj(row, colIndex, "mig_sidecar_written");
                Integer migHttp = getInt(row, colIndex, "mig_callback_http_status");
                Boolean migPruned = getBoolObj(row, colIndex, "mig_safe_pruned");
                Long migReclaimed = getLongObj(row, colIndex, "mig_reclaimed_bytes");

                result.add(new DocumentLedgerEntry(
                        jobId, execId, mode, docId, sourceUri, connSource, connTarget,
                        status, duration, timestamp, errCode, errMsg,
                        ragChunks, ragTokens, ragModel, ragDims, ragTable,
                        migSize, migSha, migBucket, migKey, migSidecar, migHttp, migPruned, migReclaimed
                ));
            } catch (Exception ex) {
                log.debug("Error mapping parquet row to DocumentLedgerEntry: {}", ex.getMessage());
            }
        }
        return result;
    }

    static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null) return values;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        values.add(sb.toString());
        return values;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static Long parseLongObj(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static Boolean parseBoolObj(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return Boolean.parseBoolean(s.trim());
    }

    private static java.time.Instant parseInstant(String s) {
        if (s == null || s.trim().isEmpty()) return java.time.Instant.now();
        try { return java.time.Instant.parse(s.trim()); } catch (Exception e) { return java.time.Instant.now(); }
    }

    private static String getString(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        return String.valueOf(row.get(idx));
    }

    private static long getLong(List<Object> row, Map<String, Integer> colIndex, String col, long def) {
        Long val = getLongObj(row, colIndex, col);
        return val != null ? val : def;
    }

    private static Long getLongObj(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Number num) return num.longValue();
        try { return Long.parseLong(String.valueOf(obj).trim()); } catch (Exception e) { return null; }
    }

    private static Integer getInt(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Number num) return num.intValue();
        try { return Integer.parseInt(String.valueOf(obj).trim()); } catch (Exception e) { return null; }
    }

    private static Boolean getBoolObj(List<Object> row, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col.toLowerCase());
        if (idx == null || idx >= row.size() || row.get(idx) == null) return null;
        Object obj = row.get(idx);
        if (obj instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(obj).trim());
    }

    public record PaginatedLedgerResponse(
            List<DocumentLedgerEntry> items,
            long total,
            int page,
            int size
    ) {}

    public record QueryResult(
            List<String> columns,
            List<List<Object>> rows
    ) {}
}
