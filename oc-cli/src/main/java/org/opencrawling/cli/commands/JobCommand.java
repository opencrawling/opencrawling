/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.opencrawling.sdk.v1.ApiProblemException;
import org.opencrawling.sdk.v1.PlatformClient;
import org.opencrawling.sdk.v1.RequestOptions;
import org.opencrawling.sdk.v1.Versioned;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.JobInput;
import org.opencrawling.sdk.v1.model.JobRun;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Picocli command group for managing OpenCrawling ingestion jobs (`oc job`).
 */
@Command(
    name = "job",
    mixinStandardHelpOptions = true,
    description = "Manage data crawling & ingestion jobs (list, start, status, pause, stop, delete, create, schedule)",
    subcommands = {
        JobCommand.JobListCommand.class,
        JobCommand.JobStartCommand.class,
        JobCommand.JobStatusCommand.class,
        JobCommand.JobPauseCommand.class,
        JobCommand.JobStopCommand.class,
        JobCommand.JobDeleteCommand.class,
        JobCommand.JobCreateCommand.class,
        JobScheduleCommand.class
    }
)
public class JobCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: list, start, status, pause, stop, delete, create, schedule"));
    }

    /** Allowed values for {@code --mode}; mirrors {@code org.opencrawling.core.pipeline.PipelineMode}. */
    static final List<String> PIPELINE_MODES = List.of("rag", "migration");

    /**
     * Validates and normalizes a {@code --mode} value.
     *
     * @return the lower-case mode, or {@code null} when not specified (inherit server default)
     * @throws IllegalArgumentException for unknown modes
     */
    static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalized = mode.trim().toLowerCase();
        if (!PIPELINE_MODES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid --mode '" + mode + "' (expected one of: " + String.join(", ", PIPELINE_MODES) + ")");
        }
        return normalized;
    }

    /** Whether a run is still in progress: its resource answers until the run ends. */
    static boolean runInProgress(PlatformClient api, String executionId) {
        try {
            api.jobRuns().get(executionId);
            return true;
        } catch (ApiProblemException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String displayMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "default";
        }
        return "migration".equalsIgnoreCase(mode) ? AnsiColors.cyan("migration") : "rag";
    }

    /** Shell completion candidates for {@code --mode}. */
    static class PipelineModeCandidates implements Iterable<String> {
        @Override
        public java.util.Iterator<String> iterator() {
            return PIPELINE_MODES.iterator();
        }
    }

    @Command(name = "list", description = "List all active and historic ingestion jobs")
    public static class JobListCommand implements Callable<Integer> {

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<Job> jobs = client.platform().jobs().stream(PlatformClient.JobFilter.all()).toList();

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));
                } else {
                    TableFormatter table = new TableFormatter()
                            .setHeaders("ID", "NAME", "CONNECTOR", "MODE", "STATUS", "STAGE", "DOCUMENTS", "LAST RUN");

                    for (Job job : jobs) {
                        String status = job.state() != null ? job.state().status() : "";
                        String statusColor = switch (status.toUpperCase()) {
                            case "RUNNING", "SCANNING", "INGESTING" -> AnsiColors.green(status);
                            case "FAILED", "ERROR" -> AnsiColors.red(status);
                            default -> AnsiColors.yellow(status);
                        };

                        table.addRow(
                                job.id(),
                                job.name(),
                                job.repositoryConnector(),
                                displayMode(job.pipelineMode()),
                                statusColor,
                                job.state() != null ? job.state().stage() : "",
                                String.valueOf(job.state() != null ? job.state().documents() : 0),
                                job.state() != null && job.state().lastRun() != null ? job.state().lastRun() : "N/A"
                        );
                    }
                    System.out.println(table.render());
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing jobs: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "start", description = "Trigger a new crawling job with dynamic parameters")
    public static class JobStartCommand implements Callable<Integer> {

        @Option(names = {"--name"}, required = true, description = "Name of the ingestion job")
        private String name;

        @Option(names = {"--connector"}, description = "Repository connector name", defaultValue = "FileSystem_Local")
        private String connector;

        @Option(names = {"--path"}, description = "Data scan path or URI", defaultValue = "/data")
        private String path;

        @Option(names = {"--output"}, description = "Output connector name", defaultValue = "PGVector_Output")
        private String output;

        @Option(names = {"--wait"}, description = "Wait until the run ends")
        private boolean wait;

        @Option(names = {"--mode"}, description = "Pipeline mode: rag (AI ingestion) or migration (as-is binary + OIS sidecar). Defaults to the server's opencrawling.pipeline.mode",
                completionCandidates = PipelineModeCandidates.class)
        private String mode;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String pipelineMode;
            try {
                pipelineMode = normalizeMode(mode);
            } catch (IllegalArgumentException e) {
                System.err.println(AnsiColors.red(e.getMessage()));
                return 2;
            }
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PlatformClient api = client.platform();
                // The same keys on a retry: a network error cannot create or start the job twice.
                String key = "oc-cli-" + java.util.UUID.randomUUID();
                Job job = api.jobs().create(JobInput.of(name, connector, output, path).withPipelineMode(pipelineMode),
                        RequestOptions.idempotent(key + "-create")).value();
                Versioned<JobRun> run = api.jobs().start(job.id(), RequestOptions.idempotent(key + "-start"));
                System.out.println(AnsiColors.green("✔ Job started successfully! Job ID: ") + AnsiColors.bold(job.id())
                        + (pipelineMode != null ? " (mode: " + pipelineMode + ")" : ""));

                if (wait) {
                    System.out.println("Polling job completion status...");
                    String executionId = run.value().executionId();
                    while (true) {
                        Thread.sleep(1000);
                        Job current = api.jobs().get(job.id()).value();
                        System.out.printf("Status: %s | Stage: %s | Processed Documents: %d%n",
                                current.state().status(), current.state().stage(), current.state().documents());
                        if (!runInProgress(api, executionId)) {
                            Job ended = api.jobs().get(job.id()).value();
                            System.out.printf("Run %s ended: %s | Stage: %s | Processed Documents: %d%n", executionId,
                                    ended.state().status(), ended.state().stage(), ended.state().documents());
                            return "Error".equalsIgnoreCase(ended.state().status()) ? 1 : 0;
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error starting job: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "status", description = "Inspect real-time status of an ingestion job")
    public static class JobStatusCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID to inspect")
        private String id;

        @Option(names = {"--watch"}, description = "Watch status continuously")
        private boolean watch;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                do {
                    Job job;
                    try {
                        job = client.platform().jobs().get(id).value();
                    } catch (ApiProblemException e) {
                        if (e.getStatusCode() == 404) {
                            System.err.println(AnsiColors.red("Job not found: " + id));
                            return 1;
                        }
                        throw e;
                    }
                    System.out.println(AnsiColors.bold("Job ID: ") + job.id());
                    System.out.println(AnsiColors.bold("Name: ") + job.name());
                    System.out.println(AnsiColors.bold("Status: ") + job.state().status());
                    System.out.println(AnsiColors.bold("Pipeline Mode: ") + displayMode(job.pipelineMode()));
                    System.out.println(AnsiColors.bold("Current Stage: ") + job.state().stage());
                    System.out.println(AnsiColors.bold("Processed Documents: ") + job.state().documents());
                    System.out.println(AnsiColors.bold("Last Run: ") + (job.state().lastRun() != null ? job.state().lastRun() : "N/A"));

                    if (watch) {
                        Thread.sleep(2000);
                        System.out.println("---");
                    }
                } while (watch);

                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching job status: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "stop", description = "Terminate a running job")
    public static class JobStopCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID to stop")
        private String id;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                client.platform().jobs().stop(id);
                System.out.println(AnsiColors.yellow("Stopped job ID: " + id));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error stopping job: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "delete", description = "Delete a job definition")
    public static class JobDeleteCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID to delete")
        private String id;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                // The job as it is now, so the delete also works when the runtime requires If-Match.
                String version = client.platform().jobs().get(id).etag();
                client.platform().jobs().delete(id, RequestOptions.ifMatch(version));
                System.out.println(AnsiColors.green("✔ Job deleted: " + id));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error deleting job: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "pause", description = "Pause execution of a running job")
    public static class JobPauseCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID to pause")
        private String id;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                client.platform().jobs().pause(id);
                System.out.println(AnsiColors.yellow("Paused job ID: " + id));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error pausing job: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "create", description = "Create a job definition from a JSON file")
    public static class JobCreateCommand implements Callable<Integer> {

        @Option(names = {"--file"}, required = true, description = "Path to job JSON file")
        private String filePath;

        @Option(names = {"--mode"}, description = "Override the job file's pipelineMode: rag or migration",
                completionCandidates = PipelineModeCandidates.class)
        private String mode;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                // A job definition (the Platform API's JobInput); state fields of older job files are ignored.
                ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                JobInput fromFile = mapper.readValue(new java.io.File(filePath), JobInput.class);
                String pipelineMode = normalizeMode(mode != null ? mode : fromFile.pipelineMode());
                JobInput request = fromFile.withPipelineMode(pipelineMode)
                        .withAuthorityConnector(blankToNull(fromFile.authorityConnector()));
                Job response = client.platform().jobs().create(request,
                        RequestOptions.idempotent("oc-cli-" + java.util.UUID.randomUUID())).value();
                System.out.println(AnsiColors.green("✔ Job created successfully! Job ID: ") + AnsiColors.bold(response.id())
                        + (pipelineMode != null ? " (mode: " + pipelineMode + ")" : ""));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error creating job: " + e.getMessage()));
                return 1;
            }
        }
    }
}
