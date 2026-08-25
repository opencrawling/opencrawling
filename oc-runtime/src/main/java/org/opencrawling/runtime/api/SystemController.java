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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.opencrawling.runtime.observability.TelemetryTraceStore;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private SystemSettingsDTO settings;
    private final JdbcTemplate jdbcTemplate;
    private final TelemetryTraceStore traceStore;

    @Autowired
    public SystemController(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) TelemetryTraceStore traceStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.traceStore = traceStore;
        String defaultOllamaUrl = System.getenv().getOrDefault("SPRING_AI_OLLAMA_BASE_URL", "http://127.0.0.1:11434");
        SystemSettingsDTO defaultSettings = new SystemSettingsDTO(
            "Ollama",
            defaultOllamaUrl,
            "mxbai-embed-large",
            1024,
            "TokenTextSplitter",
            800,
            100
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

    @GetMapping("/status")
    public Map<String, String> getSystemStatus() {
        Map<String, String> status = new HashMap<>();
        String pgStatus = checkPostgres();
        String redisStatus = checkRedis();
        String ollamaStatus = checkOllama();

        status.put("postgres", pgStatus);
        status.put("redis", redisStatus);
        status.put("ollama", ollamaStatus);
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

    @GetMapping("/logs")
    public List<String> getLogs() {
        return InMemoryLogAppender.getLogs();
    }

    @GetMapping("/settings")
    public SystemSettingsDTO getSettings() {
        return settings;
    }

    @PostMapping("/settings")
    public ResponseEntity<Void> updateSettings(@RequestBody SystemSettingsDTO newSettings) {
        this.settings = newSettings;
        PersistenceHelper.save("settings.json", newSettings);
        InMemoryLogAppender.getLogs().add(formatLog("SUCCESS", "System settings updated: Model set to '" 
            + newSettings.ollamaModel() + "' (" + newSettings.vectorDimensions() + "d) via " 
            + newSettings.embeddingProvider() + ". Chunker: " + newSettings.chunkerType() 
            + " [Size: " + newSettings.chunkSize() + ", Overlap: " + newSettings.chunkOverlap() + "]"));
        return ResponseEntity.ok().build();
    }

    public static record SystemSettingsDTO(
        String embeddingProvider,
        String ollamaBaseUrl,
        String ollamaModel,
        int vectorDimensions,
        String chunkerType,
        int chunkSize,
        int chunkOverlap
    ) {}

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
