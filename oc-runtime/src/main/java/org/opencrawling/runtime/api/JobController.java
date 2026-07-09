package org.opencrawling.runtime.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<JobDTO> jobs;

    public JobController() {
        // Initial mock data defaults
        List<JobDTO> defaults = new ArrayList<>();
        defaults.add(new JobDTO("1", "WebCrawler_Sito_A", "FileSystem_Local", "Ollama_Output", "LDAP", "/var/www/site_a", "Running", "Scanning", 12450, LocalDateTime.now().minusHours(1).format(formatter)));
        defaults.add(new JobDTO("2", "FS_Sync_Docs", "FileSystem_Local", "Ollama_Output", "", "/Users/docs", "Paused", "Paused", 890, LocalDateTime.now().minusDays(1).format(formatter)));
        defaults.add(new JobDTO("3", "SharePoint_Cloud", "FileSystem_Local", "Ollama_Output", "Active Directory", "/sharepoint/cloud", "Error", "Failed", 0, LocalDateTime.now().minusHours(2).format(formatter)));
        defaults.add(new JobDTO("4", "Slack_History", "FileSystem_Local", "Ollama_Output", "", "/slack/backup", "Finished", "Completed", 56200, LocalDateTime.now().minusDays(1).format(formatter)));
        defaults.add(new JobDTO("5", "Jira_Tickets", "FileSystem_Local", "Ollama_Output", "LDAP", "/jira/export", "Ready", "Idle", 0, "N/A"));
        
        // Load persisted list
        this.jobs = new CopyOnWriteArrayList<>(PersistenceHelper.loadList("jobs.json", JobDTO.class, defaults));
    }

    @GetMapping
    public List<JobDTO> getAllJobs() {
        boolean updated = false;
        // Simulate running job progress
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if ("Running".equals(job.status())) {
                String nextStage = job.currentStage();
                String nextStatus = "Running";
                long nextDocs = job.documents();
                
                switch (job.currentStage()) {
                    case "Scanning" -> {
                        nextStage = "Extracting";
                        nextDocs += 15;
                    }
                    case "Extracting" -> {
                        nextStage = "Chunking";
                        nextDocs += 45;
                    }
                    case "Chunking" -> {
                        nextStage = "Embedding";
                        nextDocs += 120;
                    }
                    case "Embedding" -> {
                        nextStage = "Indexing";
                        nextDocs += 120;
                    }
                    case "Indexing" -> {
                        nextStage = "Completed";
                        nextStatus = "Finished";
                    }
                    default -> {
                        nextStage = "Scanning";
                    }
                }
                
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    nextStatus,
                    nextStage,
                    nextDocs,
                    job.lastRun()
                ));
                updated = true;
            }
        }
        if (updated) {
            PersistenceHelper.save("jobs.json", jobs);
        }
        return jobs;
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDTO> getJob(@PathVariable String id) {
        return jobs.stream()
                .filter(j -> j.id().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Void> saveJob(@RequestBody JobDTO job) {
        System.out.println("Saving job: " + job.name());
        if (job.id() == null || job.id().isBlank() || job.id().equals("new")) {
            // Generate unique ID based on timestamp
            String newId = String.valueOf(System.currentTimeMillis());
            JobDTO newJob = new JobDTO(
                newId,
                job.name(),
                job.repositoryConnector(),
                job.outputConnector(),
                job.authorityConnector(),
                job.path(),
                "Ready",
                "Idle",
                0,
                "N/A"
            );
            jobs.add(newJob);
        } else {
            // Edit/Update existing job
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).id().equals(job.id())) {
                    JobDTO existing = jobs.get(i);
                    jobs.set(i, new JobDTO(
                        job.id(),
                        job.name(),
                        job.repositoryConnector(),
                        job.outputConnector(),
                        job.authorityConnector(),
                        job.path(),
                        job.status() != null ? job.status() : existing.status(),
                        job.currentStage() != null ? job.currentStage() : existing.currentStage(),
                        existing.documents(),
                        existing.lastRun()
                    ));
                    break;
                }
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable String id) {
        jobs.removeIf(j -> j.id().equals(id));
        PersistenceHelper.save("jobs.json", jobs);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> startJob(@PathVariable String id) {
        System.out.println("Starting job " + id);
        updateJobStatus(id, "Running");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stopJob(@PathVariable String id) {
        System.out.println("Stopping job " + id);
        updateJobStatus(id, "Finished");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseJob(@PathVariable String id) {
        System.out.println("Pausing job " + id);
        updateJobStatus(id, "Paused");
        return ResponseEntity.ok().build();
    }

    private void updateJobStatus(String id, String status) {
        for (int i = 0; i < jobs.size(); i++) {
            JobDTO job = jobs.get(i);
            if (job.id().equals(id)) {
                String lastRun = status.equals("Running") ? LocalDateTime.now().format(formatter) : job.lastRun();
                long docCount = job.documents();
                String stage = "Idle";
                if (status.equals("Running")) {
                    stage = "Scanning";
                    docCount += 10;
                } else if (status.equals("Paused")) {
                    stage = "Paused";
                } else if (status.equals("Finished")) {
                    stage = "Completed";
                } else if (status.equals("Error")) {
                    stage = "Failed";
                }
                jobs.set(i, new JobDTO(
                    job.id(),
                    job.name(),
                    job.repositoryConnector(),
                    job.outputConnector(),
                    job.authorityConnector(),
                    job.path(),
                    status,
                    stage,
                    docCount,
                    lastRun
                ));
                break;
            }
        }
        PersistenceHelper.save("jobs.json", jobs);
    }

    public static record JobDTO(
        String id,
        String name,
        String repositoryConnector,
        String outputConnector,
        String authorityConnector,
        String path,
        String status,
        String currentStage,
        long documents,
        String lastRun
    ) {}
}
