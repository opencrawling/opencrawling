package org.apache.manifoldcf.runtime.api;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final List<JobDTO> jobs = new CopyOnWriteArrayList<>();

    public JobController() {
        jobs.add(new JobDTO("1", "WebCrawler_Sito_A", "Repository", "Running", 12450, LocalDateTime.now().minusHours(1).format(formatter)));
        jobs.add(new JobDTO("2", "FS_Sync_Docs", "Repository", "Paused", 890, LocalDateTime.now().minusDays(1).format(formatter)));
        jobs.add(new JobDTO("3", "SharePoint_Cloud", "Authority", "Error", 0, LocalDateTime.now().minusHours(2).format(formatter)));
        jobs.add(new JobDTO("4", "Slack_History", "Output", "Finished", 56200, LocalDateTime.now().minusDays(1).format(formatter)));
        jobs.add(new JobDTO("5", "Jira_Tickets", "Repository", "Ready", 0, "N/A"));
    }

    @GetMapping
    public List<JobDTO> getAllJobs() {
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
                if (status.equals("Running")) {
                    docCount += 120; // simulate progress
                }
                jobs.set(i, new JobDTO(job.id(), job.name(), job.type(), status, docCount, lastRun));
                break;
            }
        }
    }

    public static record JobDTO(String id, String name, String type, String status, long documents, String lastRun) {}
}
