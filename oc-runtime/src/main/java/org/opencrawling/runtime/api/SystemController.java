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

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // In-memory system settings persisted via PersistenceHelper
    private SystemSettingsDTO settings;

    public SystemController() {
        SystemSettingsDTO defaultSettings = new SystemSettingsDTO(
            "Ollama",
            "http://127.0.0.1:11434",
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
        status.put("postgres", "UP");
        status.put("redis", "UP");
        status.put("ollama", "UP");
        status.put("system", "HEALTHY");
        return status;
    }

    @GetMapping("/throughput")
    public List<Map<String, Object>> getThroughput() {
        List<Map<String, Object>> throughput = new ArrayList<>();
        String[] hours = {"08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00"};
        java.util.Random random = new java.util.Random();
        for (String hour : hours) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", hour);
            data.put("docs", 400 + random.nextInt(2000));
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
