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
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Picocli command group for managing OpenCrawling ingestion jobs (`oc job`).
 */
@Command(
    name = "job",
    mixinStandardHelpOptions = true,
    description = "Manage data crawling & ingestion jobs (list, start, status, pause, stop, delete, create)",
    subcommands = {
        JobCommand.JobListCommand.class,
        JobCommand.JobStartCommand.class,
        JobCommand.JobStatusCommand.class,
        JobCommand.JobPauseCommand.class,
        JobCommand.JobStopCommand.class,
        JobCommand.JobDeleteCommand.class,
        JobCommand.JobCreateCommand.class
    }
)
public class JobCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: list, start, status, pause, stop, delete, create"));
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
                List<JobResponse> jobs = client.jobs().list();

                if ("json".equalsIgnoreCase(format)) {
                    ObjectMapper mapper = new ObjectMapper();
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jobs));
                } else {
                    TableFormatter table = new TableFormatter()
                            .setHeaders("ID", "NAME", "CONNECTOR", "STATUS", "STAGE", "DOCUMENTS", "LAST RUN");

                    for (JobResponse job : jobs) {
                        String statusColor = switch (job.status().toUpperCase()) {
                            case "RUNNING", "SCANNING", "INGESTING" -> AnsiColors.green(job.status());
                            case "FAILED", "ERROR" -> AnsiColors.red(job.status());
                            default -> AnsiColors.yellow(job.status());
                        };

                        table.addRow(
                                job.id(),
                                job.name(),
                                job.repositoryConnector(),
                                statusColor,
                                job.currentStage(),
                                String.valueOf(job.documents()),
                                job.lastRun()
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

        @Option(names = {"--wait"}, description = "Wait for job completion")
        private boolean wait;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                JobRequest request = JobRequest.builder()
                        .name(name)
                        .repositoryConnector(connector)
                        .path(path)
                        .build();

                JobResponse response = client.jobs().create(request);
                client.jobs().start(response.id());
                System.out.println(AnsiColors.green("✔ Job started successfully! Job ID: ") + AnsiColors.bold(response.id()));

                if (wait) {
                    System.out.println("Polling job completion status...");
                    while (true) {
                        Thread.sleep(1000);
                        Optional<JobResponse> statusOpt = client.jobs().get(response.id());
                        if (statusOpt.isPresent()) {
                            JobResponse current = statusOpt.get();
                            System.out.printf("Status: %s | Stage: %s | Processed Documents: %d\n",
                                    current.status(), current.currentStage(), current.documents());
                            if ("Completed".equalsIgnoreCase(current.status()) || "Failed".equalsIgnoreCase(current.status())) {
                                break;
                            }
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
                    Optional<JobResponse> jobOpt = client.jobs().get(id);
                    if (jobOpt.isEmpty()) {
                        System.err.println(AnsiColors.red("Job not found: " + id));
                        return 1;
                    }
                    JobResponse job = jobOpt.get();
                    System.out.println(AnsiColors.bold("Job ID: ") + job.id());
                    System.out.println(AnsiColors.bold("Name: ") + job.name());
                    System.out.println(AnsiColors.bold("Status: ") + job.status());
                    System.out.println(AnsiColors.bold("Current Stage: ") + job.currentStage());
                    System.out.println(AnsiColors.bold("Processed Documents: ") + job.documents());
                    System.out.println(AnsiColors.bold("Last Run: ") + job.lastRun());

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
                client.jobs().stop(id);
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
                client.jobs().delete(id);
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
                client.jobs().pause(id);
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

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                ObjectMapper mapper = new ObjectMapper();
                JobRequest request = mapper.readValue(new java.io.File(filePath), JobRequest.class);
                JobResponse response = client.jobs().create(request);
                System.out.println(AnsiColors.green("✔ Job created successfully! Job ID: ") + AnsiColors.bold(response.id()));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error creating job: " + e.getMessage()));
                return 1;
            }
        }
    }
}
