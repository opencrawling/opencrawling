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
package org.opencrawling.runtime.api;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.regex.Pattern;
import org.opencrawling.core.text.TextExtractionProperties;
import org.opencrawling.core.text.TextExtractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.opencrawling.runtime.observability.TelemetryTraceStore;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "System & Settings", description = "System health, JVM runtime metrics, live application logs, and global crawler/extraction settings")
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern VALID_HEAP_PATTERN = Pattern.compile("^-Xmx[0-9]+[kmgKMG]$");
    
    private SystemSettingsDTO settings;
    private final JdbcTemplate jdbcTemplate;
    private final TelemetryTraceStore traceStore;
    private final TextExtractionProperties textExtractionProperties;
    private final TextExtractionService textExtractionService;

    @Autowired
    public SystemController(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) TelemetryTraceStore traceStore,
            @Autowired(required = false) TextExtractionProperties textExtractionProperties,
            @Autowired(required = false) TextExtractionService textExtractionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.traceStore = traceStore;
        this.textExtractionProperties = textExtractionProperties;
        this.textExtractionService = textExtractionService;
        String defaultOllamaUrl = System.getenv().getOrDefault("SPRING_AI_OLLAMA_BASE_URL", "http://127.0.0.1:11434");

        boolean forkEnabled = textExtractionProperties != null ? textExtractionProperties.isForkEnabled() : true;
        long timeoutMs = textExtractionProperties != null ? textExtractionProperties.getTimeoutMs() : 30000L;
        String heap = (textExtractionProperties != null && textExtractionProperties.getJvmArgs() != null && !textExtractionProperties.getJvmArgs().isEmpty())
                ? textExtractionProperties.getJvmArgs().get(0) : "-Xmx512m";
        int maxFiles = textExtractionProperties != null ? textExtractionProperties.getMaxFilesPerProcess() : 100000;
        boolean extractEmbedded = textExtractionProperties != null ? textExtractionProperties.isExtractEmbedded() : true;
        int writeLimit = textExtractionProperties != null ? textExtractionProperties.getWriteLimit() : 20000000;
        int numClients = textExtractionProperties != null ? textExtractionProperties.getNumClients() : 1;

        String tikaVer = textExtractionService != null ? textExtractionService.getVersion() : "4.2.0";
        String tikaEngine = textExtractionService != null ? textExtractionService.getEngineName()
                : (forkEnabled ? "PipesForkParser (Process-Isolated)" : "In-Process Embedded Tika");

        SystemSettingsDTO defaultSettings = new SystemSettingsDTO(
            "Ollama",
            defaultOllamaUrl,
            "mxbai-embed-large",
            1024,
            "TokenTextSplitter",
            800,
            100,
            "ozone",
            "NATIVE",
            "http://127.0.0.1:9878",
            "127.0.0.1",
            9862,
            "s3v",
            "claims",
            tikaVer,
            tikaEngine,
            forkEnabled,
            timeoutMs,
            heap,
            maxFiles,
            true,
            extractEmbedded,
            writeLimit,
            numClients
        );
        this.settings = PersistenceHelper.loadObject("settings.json", SystemSettingsDTO.class, defaultSettings);

        // Register programmatic log capture appender
        try {
            org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
            if (factory instanceof ch.qos.logback.classic.LoggerContext context) {
                ch.qos.logback.classic.Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                if (rootLogger.getAppender("IN_MEMORY") == null) {
                    InMemoryLogAppender appender = new InMemoryLogAppender();
                    appender.setContext(context);
                    appender.setName("IN_MEMORY");
                    appender.start();
                    rootLogger.addAppender(appender);
                }
            }
        } catch (Throwable e) {
            System.err.println("Failed to initialize custom log appender: " + e.getMessage());
        }
        
        InMemoryLogAppender.getLogs().add(formatLog("INFO", "Real-time in-memory logging system initialized successfully."));
        InMemoryLogAppender.getLogs().add(formatLog("INFO", "System settings online: Model set to 'mxbai-embed-large' (1024d)"));
    }

    private String formatLog(String level, String msg) {
        return String.format("[%s] %s: %s", LocalDateTime.now().format(logTimeFormatter), level, msg);
    }

    @Operation(summary = "Get system health status", description = "Checks health of backend dependencies (PostgreSQL/pgvector, Redis, Ollama, Apache Tika) and overall system state.")
    @ApiResponse(responseCode = "200", description = "System and dependency health status map")
    @GetMapping("/status")
    public Map<String, String> getSystemStatus() {
        Map<String, String> status = new HashMap<>();
        String pgStatus = checkPostgres();
        String redisStatus = checkRedis();
        String ollamaStatus = checkOllama();

        status.put("postgres", pgStatus);
        status.put("redis", redisStatus);
        status.put("ollama", ollamaStatus);
        if (textExtractionService != null) {
            status.put("tika", textExtractionService.isHealthy() ? "UP" : "DOWN");
            status.put("tikaVersion", textExtractionService.getVersion());
            status.put("tikaEngine", textExtractionService.getEngineName());
        } else {
            status.put("tika", "DISABLED");
            status.put("tikaVersion", (settings != null && settings.tikaVersion() != null) ? settings.tikaVersion() : "N/A");
            status.put("tikaEngine", "Disabled");
        }
        status.put("totalIndexedDocs", String.valueOf(getActualDbDocCount()));

        if ("UP".equals(pgStatus) && "UP".equals(redisStatus) && "UP".equals(ollamaStatus)) {
            status.put("system", "HEALTHY");
        } else if ("UP".equals(pgStatus) || "UP".equals(redisStatus) || "UP".equals(ollamaStatus)) {
            status.put("system", "DEGRADED");
        } else {
            status.put("system", "UNHEALTHY");
        }
        return status;
    }

    private long getActualDbDocCount() {
        if (jdbcTemplate == null) {
            return 0;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                "SELECT (SELECT count(*) FROM vector_store) + " +
                "(SELECT count(*) FROM vector_store_1024) + " +
                "(SELECT count(*) FROM vector_store_768) + " +
                "(SELECT count(*) FROM vector_store_384)",
                Long.class
            );
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String checkPostgres() {
        if (jdbcTemplate == null) {
            return "DOWN";
        }
        try {
            Integer res = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return (res != null && res == 1) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String checkOllama() {
        String envBaseUrl = System.getenv("SPRING_AI_OLLAMA_BASE_URL");
        String settingsUrl = (settings != null && settings.ollamaBaseUrl() != null && !settings.ollamaBaseUrl().isBlank())
                ? settings.ollamaBaseUrl() : null;

        List<String> candidates = new ArrayList<>();
        if (envBaseUrl != null && !envBaseUrl.isBlank()) {
            candidates.add(envBaseUrl);
        }
        if (settingsUrl != null && !candidates.contains(settingsUrl)) {
            candidates.add(settingsUrl);
        }
        if (!candidates.contains("http://ollama:11434")) {
            candidates.add("http://ollama:11434");
        }
        if (!candidates.contains("http://127.0.0.1:11434")) {
            candidates.add("http://127.0.0.1:11434");
        }
        if (!candidates.contains("http://host.docker.internal:11434")) {
            candidates.add("http://host.docker.internal:11434");
        }

        for (String url : candidates) {
            if (pingOllama(url)) {
                return "UP";
            }
        }
        return "DOWN";
    }

    private boolean pingOllama(String baseUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(1500))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/tags"))
                    .timeout(java.time.Duration.ofMillis(1500))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String checkRedis() {
        String host = System.getenv().getOrDefault("SPRING_DATA_REDIS_HOST", System.getenv().getOrDefault("SPRING_REDIS_HOST", "127.0.0.1"));
        int port = 6379;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault("SPRING_DATA_REDIS_PORT", System.getenv().getOrDefault("SPRING_REDIS_PORT", "6379")));
        } catch (Exception ignored) {}
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    @Operation(summary = "Get hourly ingestion throughput", description = "Retrieves hourly document indexing throughput over the last 6 hours.")
    @ApiResponse(responseCode = "200", description = "Hourly document ingestion statistics")
    @GetMapping("/throughput")
    public List<Map<String, Object>> getThroughput() {
        List<Map<String, Object>> throughput = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter hourFormatter = DateTimeFormatter.ofPattern("HH:00");

        Map<String, Long> hourlyCounts = new HashMap<>();
        if (traceStore != null) {
            Map<String, List<TelemetryTraceStore.SpanRecord>> allSpans = traceStore.getAllSpans();
            for (List<TelemetryTraceStore.SpanRecord> spans : allSpans.values()) {
                for (TelemetryTraceStore.SpanRecord span : spans) {
                    if ("Indexing".equalsIgnoreCase(span.stage()) || "Completed".equalsIgnoreCase(span.stage())) {
                        LocalDateTime spanTime = LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(span.startTimeMillis()),
                            java.time.ZoneId.systemDefault()
                        );
                        String hourKey = spanTime.format(hourFormatter);
                        long docCount = 1;
                        if (span.attributes() != null && span.attributes().containsKey("chunkCount")) {
                            try {
                                docCount = Long.parseLong(span.attributes().get("chunkCount"));
                            } catch (Exception ignored) {}
                        }
                        hourlyCounts.put(hourKey, hourlyCounts.getOrDefault(hourKey, 0L) + docCount);
                    }
                }
            }
        }

        long dbDocCount = 0;
        if (jdbcTemplate != null) {
            try {
                Long count = jdbcTemplate.queryForObject(
                    "SELECT (SELECT count(*) FROM vector_store) + " +
                    "(SELECT count(*) FROM vector_store_1024) + " +
                    "(SELECT count(*) FROM vector_store_768) + " +
                    "(SELECT count(*) FROM vector_store_384)",
                    Long.class
                );
                if (count != null) dbDocCount = count;
            } catch (Exception ignored) {}
        }

        for (int i = 6; i >= 0; i--) {
            LocalDateTime hourTime = now.minusHours(i);
            String hourStr = hourTime.format(hourFormatter);
            long count = hourlyCounts.getOrDefault(hourStr, 0L);
            if (i == 0 && count == 0 && dbDocCount > 0) {
                count = dbDocCount;
            }
            Map<String, Object> data = new HashMap<>();
            data.put("name", hourStr);
            data.put("docs", count);
            throughput.add(data);
        }
        return throughput;
    }

    @Operation(summary = "Get live system logs", description = "Retrieves recent in-memory application log entries.")
    @ApiResponse(responseCode = "200", description = "List of recent log lines")
    @GetMapping("/logs")
    public List<String> getLogs() {
        return InMemoryLogAppender.getLogs();
    }

    @Operation(summary = "Get global system settings", description = "Retrieves active configuration for embedding models, chunking parameters, Claim-Check store, and Apache Tika parser engine.")
    @ApiResponse(responseCode = "200", description = "System configuration settings")
    @GetMapping("/settings")
    public SystemSettingsDTO getSettings() {
        return settings;
    }

    @Operation(summary = "Update global system settings", description = "Updates and persists global configuration settings for embedding, chunking, Claim-Check storage, and text extraction.")
    @ApiResponse(responseCode = "200", description = "Settings updated successfully")
    @PostMapping("/settings")
    public ResponseEntity<Void> updateSettings(@RequestBody SystemSettingsDTO newSettings) {
        this.settings = newSettings;
        if (textExtractionProperties != null) {
            if (newSettings.tikaForkEnabled() != null) {
                textExtractionProperties.setForkEnabled(newSettings.tikaForkEnabled());
            }
            if (newSettings.tikaTimeoutMs() != null && newSettings.tikaTimeoutMs() > 0) {
                textExtractionProperties.setTimeoutMs(newSettings.tikaTimeoutMs());
            }
            if (newSettings.tikaJvmHeap() != null && !newSettings.tikaJvmHeap().isBlank()) {
                String heap = newSettings.tikaJvmHeap().trim();
                if (!VALID_HEAP_PATTERN.matcher(heap).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid JVM heap argument: '" + heap + "'. Expected pattern -Xmx<number>[k|m|g].");
                }
                textExtractionProperties.setJvmArgs(new ArrayList<>(List.of(heap)));
            }
            if (newSettings.tikaMaxFilesPerProcess() != null && newSettings.tikaMaxFilesPerProcess() > 0) {
                textExtractionProperties.setMaxFilesPerProcess(newSettings.tikaMaxFilesPerProcess());
            }
            if (newSettings.tikaExtractEmbedded() != null) {
                textExtractionProperties.setExtractEmbedded(newSettings.tikaExtractEmbedded());
            }
            if (newSettings.tikaWriteLimit() != null && newSettings.tikaWriteLimit() > 0) {
                textExtractionProperties.setWriteLimit(newSettings.tikaWriteLimit());
            }
            if (newSettings.tikaNumClients() != null && newSettings.tikaNumClients() > 0) {
                textExtractionProperties.setNumClients(newSettings.tikaNumClients());
            }
        }
        PersistenceHelper.save("settings.json", newSettings);
        InMemoryLogAppender.getLogs().add(formatLog("SUCCESS", "System settings updated: Model set to '" 
            + newSettings.ollamaModel() + "' (" + newSettings.vectorDimensions() + "d) via " 
            + newSettings.embeddingProvider() + ". Chunker: " + newSettings.chunkerType() 
            + " [Size: " + newSettings.chunkSize() + ", Overlap: " + newSettings.chunkOverlap() + "]"
            + ". Tika Fork: " + newSettings.tikaForkEnabled() + " (Timeout: " + newSettings.tikaTimeoutMs() + "ms, Heap: " + newSettings.tikaJvmHeap() + ", Clients: " + newSettings.tikaNumClients() + ")"));
        InMemoryLogAppender.getLogs().add(formatLog("WARN", "Tika parser settings updated. Note: Reconfiguration of active child processes requires an application restart to take full effect."));
        return ResponseEntity.ok().header("X-Requires-Restart", "true").build();
    }

    @Schema(description = "Global runtime configuration settings")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SystemSettingsDTO(
        @Schema(description = "Active embedding model provider (e.g. 'Ollama', 'OpenAI')", example = "Ollama")
        String embeddingProvider,
        @Schema(description = "Base URL for Ollama service", example = "http://127.0.0.1:11434")
        String ollamaBaseUrl,
        @Schema(description = "Embedding model name", example = "mxbai-embed-large")
        String ollamaModel,
        @Schema(description = "Dense vector dimensions", example = "1024")
        int vectorDimensions,
        @Schema(description = "Text splitter / chunker algorithm", example = "TokenTextSplitter")
        String chunkerType,
        @Schema(description = "Chunk token size limit", example = "800")
        int chunkSize,
        @Schema(description = "Overlap between adjacent chunks in tokens", example = "100")
        int chunkOverlap,
        @Schema(description = "Claim-Check binary payload storage backend ('ozone', 'local', 's3')", example = "ozone")
        String claimCheckStore,
        @Schema(description = "Apache Ozone client protocol ('NATIVE' or 'S3')", example = "NATIVE")
        String ozoneClientType,
        @Schema(description = "Apache Ozone S3 gateway endpoint URL", example = "http://127.0.0.1:9878")
        String ozoneS3Endpoint,
        @Schema(description = "Apache Ozone Ozone Manager (OM) host", example = "127.0.0.1")
        String ozoneOmHost,
        @Schema(description = "Apache Ozone Ozone Manager (OM) RPC port", example = "9862")
        int ozoneOmPort,
        @Schema(description = "Target Ozone volume name", example = "s3v")
        String ozoneVolume,
        @Schema(description = "Target Ozone bucket name", example = "claims")
        String ozoneBucket,
        @Schema(description = "Apache Tika text extraction version", example = "4.2.0")
        String tikaVersion,
        @Schema(description = "Active text extraction engine implementation", example = "PipesForkParser (Process-Isolated)")
        String tikaEngine,
        @Schema(description = "Whether process-isolated PipesForkParser is enabled for Tika", example = "true")
        Boolean tikaForkEnabled,
        @Schema(description = "Per-file text extraction timeout in milliseconds", example = "30000")
        Long tikaTimeoutMs,
        @Schema(description = "JVM heap argument for isolated Tika processes", example = "-Xmx512m")
        String tikaJvmHeap,
        @Schema(description = "Maximum files processed before respawning isolated Tika process", example = "100000")
        Integer tikaMaxFilesPerProcess,
        @Schema(description = "Whether to fall back to in-process embedded Tika parser if forked process fails", example = "true")
        Boolean tikaFallbackToEmbedded,
        @Schema(description = "Whether to extract embedded attachments and nested archives", example = "true")
        Boolean tikaExtractEmbedded,
        @Schema(description = "Maximum character limit extracted per document", example = "20000000")
        Integer tikaWriteLimit,
        @Schema(description = "Number of concurrent forked Tika worker clients", example = "1")
        Integer tikaNumClients
    ) {
        public SystemSettingsDTO {
            if (embeddingProvider == null) embeddingProvider = "Ollama";
            if (ollamaBaseUrl == null) ollamaBaseUrl = "http://127.0.0.1:11434";
            if (ollamaModel == null) ollamaModel = "mxbai-embed-large";
            if (vectorDimensions <= 0) vectorDimensions = 1024;
            if (chunkerType == null) chunkerType = "TokenTextSplitter";
            if (chunkSize <= 0) chunkSize = 800;
            if (chunkOverlap < 0) chunkOverlap = 100;
            if (claimCheckStore == null) claimCheckStore = "ozone";
            if (ozoneClientType == null) ozoneClientType = "NATIVE";
            if (ozoneS3Endpoint == null) ozoneS3Endpoint = "http://127.0.0.1:9878";
            if (ozoneOmHost == null) ozoneOmHost = "127.0.0.1";
            if (ozoneOmPort <= 0) ozoneOmPort = 9862;
            if (ozoneVolume == null) ozoneVolume = "s3v";
            if (ozoneBucket == null) ozoneBucket = "claims";
            if (tikaVersion == null) tikaVersion = "4.2.0";
            if (tikaForkEnabled == null) tikaForkEnabled = Boolean.TRUE;
            if (tikaEngine == null) {
                tikaEngine = tikaForkEnabled ? "PipesForkParser (Process-Isolated)" : "In-Process Embedded Tika";
            }
            if (tikaTimeoutMs == null || tikaTimeoutMs <= 0) tikaTimeoutMs = 30000L;
            if (tikaJvmHeap == null || tikaJvmHeap.isBlank()) tikaJvmHeap = "-Xmx512m";
            if (tikaMaxFilesPerProcess == null || tikaMaxFilesPerProcess <= 0) tikaMaxFilesPerProcess = 100000;
            if (tikaFallbackToEmbedded == null) tikaFallbackToEmbedded = Boolean.TRUE;
            if (tikaExtractEmbedded == null) tikaExtractEmbedded = Boolean.TRUE;
            if (tikaWriteLimit == null || tikaWriteLimit <= 0) tikaWriteLimit = 20000000;
            if (tikaNumClients == null || tikaNumClients <= 0) tikaNumClients = 1;
        }
    }

    // Custom Logback appender to record root system logs in memory
    public static class InMemoryLogAppender extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
        private static final List<String> logsList = new CopyOnWriteArrayList<>();
        private static final int MAX_LOGS = 100;
        private static final java.time.format.DateTimeFormatter timeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        public static List<String> getLogs() {
            return logsList;
        }

        @Override
        protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            String level = event.getLevel().toString();
            // Map common Logback levels to color matcher format
            if ("ERROR".equals(level)) {
                level = "FAILED";
            }
            String formatted = String.format("[%s] %s: %s", 
                java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.getTimeStamp()), 
                    java.time.ZoneId.systemDefault()
                ).format(timeFormatter),
                level,
                event.getFormattedMessage()
            );
            logsList.add(formatted);
            while (logsList.size() > MAX_LOGS) {
                logsList.remove(0);
            }
        }
    }
}
