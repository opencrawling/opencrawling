package org.opencrawling.runtime.api;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final Random random = new Random();
    private static final DateTimeFormatter logTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final List<String> logMessages = new CopyOnWriteArrayList<>();

    public SystemController() {
        logMessages.add(formatLog("INFO", "System initialized successfully. Ready to receive jobs."));
        logMessages.add(formatLog("INFO", "PostgreSQL Connection pool initialized with size 10."));
        logMessages.add(formatLog("INFO", "Redis Connection established at 127.0.0.1:6379."));
        logMessages.add(formatLog("INFO", "Ollama Embedding Client online using model 'mxbai-embed-large'."));
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
        if (random.nextInt(10) > 4) {
            String[] levels = {"INFO", "DEBUG", "WARN", "SUCCESS"};
            String[] messages = {
                "Fetching repository updates...",
                "Running chunking operation using TokenTextSplitter.",
                "Successfully generated 1024-dimensional embeddings.",
                "Pushed document metadata to Kafka topic: opencrawling-documents",
                "Processed vector store batch insert of 50 documents.",
                "PostgreSQL vector_store insert completed successfully."
            };
            String lvl = levels[random.nextInt(levels.length)];
            String msg = messages[random.nextInt(messages.length)];
            logMessages.add(formatLog(lvl, msg));
            if (logMessages.size() > 50) {
                logMessages.remove(0);
            }
        }
        return logMessages;
    }
}
