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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.cli.util.TableFormatter;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.v1.PlatformClient;
import org.opencrawling.sdk.v1.RequestOptions;
import org.opencrawling.sdk.v1.ApiProblemException;
import org.opencrawling.sdk.v1.model.CronValidationRequest;
import org.opencrawling.sdk.v1.model.CronValidationResult;
import org.opencrawling.sdk.v1.model.JobSchedule;
import org.opencrawling.sdk.v1.model.JobScheduleConfig;
import org.opencrawling.sdk.v1.model.JobScheduleConfig.MisfirePolicy;
import org.opencrawling.sdk.v1.model.JobScheduleConfig.OverlapPolicy;
import org.opencrawling.sdk.v1.model.ScheduleDispatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * CLI command group for managing native job cron schedules ({@code oc job schedule ...}) (#197).
 */
@Command(
        name = "schedule",
        mixinStandardHelpOptions = true,
        description = "Manage recurring cron schedules for jobs (set, get, list, pause, resume, remove, dispatches, validate)",
        subcommands = {
                JobScheduleCommand.SetCommand.class,
                JobScheduleCommand.GetCommand.class,
                JobScheduleCommand.ListCommand.class,
                JobScheduleCommand.PauseCommand.class,
                JobScheduleCommand.ResumeCommand.class,
                JobScheduleCommand.RemoveCommand.class,
                JobScheduleCommand.DispatchesCommand.class,
                JobScheduleCommand.ValidateCommand.class
        }
)
public class JobScheduleCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow(
                "Please specify a subcommand: set, get, list, pause, resume, remove, dispatches, validate"));
    }

    private static ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static String fetchCurrentEtagOrNull(PlatformClient.Schedules schedules, String jobId) {
        try {
            return schedules.get(jobId).etag();
        } catch (ApiProblemException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private static void printScheduleDetails(JobSchedule item, String format) throws Exception {
        if ("json".equalsIgnoreCase(format)) {
            System.out.println(jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(item));
            return;
        }
        JobScheduleConfig cfg = item.schedule();
        var st = item.state();
        System.out.println(AnsiColors.bold("=== Schedule: " + item.jobName() + " (Job ID: " + item.jobId() + ") ==="));
        System.out.println("Cron:            " + (cfg != null ? cfg.cron() : "-"));
        System.out.println("Timezone:        " + (cfg != null && cfg.timezone() != null ? cfg.timezone() : "UTC"));
        System.out.println("Enabled:         " + (st != null ? st.enabled() : (cfg != null && cfg.enabled())));
        System.out.println("Overlap Policy:  " + (cfg != null && cfg.overlapPolicy() != null ? cfg.overlapPolicy() : OverlapPolicy.SKIP));
        System.out.println("Misfire Policy:  " + (cfg != null && cfg.misfirePolicy() != null ? cfg.misfirePolicy() : MisfirePolicy.SKIP));
        if (cfg != null && cfg.jitterSeconds() != null) {
            System.out.println("Jitter (sec):    " + cfg.jitterSeconds());
        }
        if (cfg != null && cfg.maxConsecutiveFailures() != null) {
            System.out.println("Auto-Pause Max:  " + cfg.maxConsecutiveFailures() + " failures");
        }
        if (st != null) {
            System.out.println("Next Fire At:    " + (st.nextFireAt() != null ? st.nextFireAt() : "paused / none"));
            System.out.println("Last Fired At:   " + (st.lastFiredAt() != null ? st.lastFiredAt() : "never"));
            if (st.lastOutcome() != null) {
                System.out.println("Last Outcome:    " + st.lastOutcome() + (st.lastDetail() != null ? " (" + st.lastDetail() + ")" : ""));
            }
            if (st.consecutiveFailures() > 0) {
                System.out.println("Failures Streak: " + st.consecutiveFailures());
            }
        }
        if (!item.nextFireTimes().isEmpty()) {
            System.out.println("Upcoming Runs:");
            for (Instant t : item.nextFireTimes()) {
                System.out.println("  - " + t);
            }
        }
    }

    @Command(name = "set", description = "Create or replace a job's cron schedule")
    public static class SetCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--cron"}, required = true, description = "5-field POSIX cron, 6-field Spring cron, or macro (@hourly, @daily, ...)")
        private String cron;

        @Option(names = {"--timezone"}, description = "IANA timezone ID (default: UTC)", defaultValue = "UTC")
        private String timezone;

        @Option(names = {"--enabled"}, description = "Whether the schedule is active", defaultValue = "true")
        private boolean enabled;

        @Option(names = {"--overlap"}, description = "Overlap policy: SKIP, COALESCE, STOP_PREVIOUS", defaultValue = "SKIP")
        private OverlapPolicy overlap;

        @Option(names = {"--misfire"}, description = "Misfire policy: SKIP, FIRE_ONCE", defaultValue = "SKIP")
        private MisfirePolicy misfire;

        @Option(names = {"--jitter"}, description = "Maximum random delay in seconds (0..3600)")
        private Integer jitterSeconds;

        @Option(names = {"--start-at"}, description = "ISO-8601 earliest execution instant")
        private String startAt;

        @Option(names = {"--end-at"}, description = "ISO-8601 latest execution instant")
        private String endAt;

        @Option(names = {"--max-failures"}, description = "Auto-pause after N consecutive failures (0 disables)")
        private Integer maxFailures;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                JobScheduleConfig config = JobScheduleConfig.builder()
                        .cron(cron)
                        .timezone(timezone)
                        .enabled(enabled)
                        .overlapPolicy(overlap)
                        .misfirePolicy(misfire)
                        .jitterSeconds(jitterSeconds)
                        .startAt(startAt != null && !startAt.isBlank() ? Instant.parse(startAt.strip()) : null)
                        .endAt(endAt != null && !endAt.isBlank() ? Instant.parse(endAt.strip()) : null)
                        .maxConsecutiveFailures(maxFailures)
                        .build();

                PlatformClient.Schedules schedules = client.platform().schedules();
                String etag = fetchCurrentEtagOrNull(schedules, id);
                RequestOptions opts = etag != null ? RequestOptions.ifMatch(etag) : RequestOptions.none();
                JobSchedule saved = schedules.set(id, config, opts).value();
                if (!"json".equalsIgnoreCase(format)) {
                    System.out.println(AnsiColors.green("✔ Schedule saved for job " + id));
                }
                printScheduleDetails(saved, format);
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error setting schedule: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "get", description = "Inspect a job's cron schedule and upcoming fire times")
    public static class GetCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                JobSchedule schedule = client.platform().schedules().get(id).value();
                printScheduleDetails(schedule, format);
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching schedule: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "list", description = "List scheduled jobs")
    public static class ListCommand implements Callable<Integer> {

        @Option(names = {"--enabled"}, description = "Filter by enabled state: true or false")
        private Boolean enabled;

        @Option(names = {"--outcome"}, description = "Filter by last dispatch outcome")
        private String outcome;

        @Option(names = {"-q", "--query"}, description = "Filter by job name substring")
        private String query;

        @Option(names = {"--sort"}, description = "Sort field (nextFireAt, name, lastFiredAt; prefix '-' for desc)")
        private String sort;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PlatformClient.ScheduleFilter filter = new PlatformClient.ScheduleFilter(enabled, outcome, query, sort);
                List<JobSchedule> schedules = client.platform().schedules().stream(filter).toList();
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schedules));
                    return 0;
                }
                TableFormatter table = new TableFormatter()
                        .setHeaders("JOB ID", "NAME", "CRON", "TIMEZONE", "ENABLED", "NEXT FIRE", "LAST OUTCOME");
                for (JobSchedule s : schedules) {
                    JobScheduleConfig cfg = s.schedule();
                    var st = s.state();
                    table.addRow(
                            s.jobId(),
                            s.jobName(),
                            cfg != null ? cfg.cron() : "",
                            cfg != null && cfg.timezone() != null ? cfg.timezone() : "UTC",
                            (st != null ? st.enabled() : (cfg != null && cfg.enabled()))
                                    ? AnsiColors.green("enabled")
                                    : AnsiColors.yellow("paused"),
                            st != null && st.nextFireAt() != null ? st.nextFireAt().toString() : "-",
                            st != null && st.lastOutcome() != null ? st.lastOutcome().name() : "-");
                }
                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing schedules: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "pause", description = "Pause automatic scheduled runs for a job")
    public static class PauseCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PlatformClient.Schedules schedules = client.platform().schedules();
                String etag = fetchCurrentEtagOrNull(schedules, id);
                RequestOptions opts = RequestOptions.idempotent("oc-cli-pause-" + UUID.randomUUID());
                if (etag != null) {
                    opts = opts.withIfMatch(etag);
                }
                JobSchedule paused = schedules.pause(id, opts).value();
                if (!"json".equalsIgnoreCase(format)) {
                    System.out.println(AnsiColors.yellow("Schedule paused for job " + id));
                }
                printScheduleDetails(paused, format);
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error pausing schedule: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "resume", description = "Resume a paused schedule (also clears AUTO_PAUSED state)")
    public static class ResumeCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PlatformClient.Schedules schedules = client.platform().schedules();
                String etag = fetchCurrentEtagOrNull(schedules, id);
                RequestOptions opts = RequestOptions.idempotent("oc-cli-resume-" + UUID.randomUUID());
                if (etag != null) {
                    opts = opts.withIfMatch(etag);
                }
                JobSchedule resumed = schedules.resume(id, opts).value();
                if (!"json".equalsIgnoreCase(format)) {
                    System.out.println(AnsiColors.green("✔ Schedule resumed for job " + id));
                }
                printScheduleDetails(resumed, format);
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error resuming schedule: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "remove", description = "Remove the schedule from a job without deleting the job")
    public static class RemoveCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                PlatformClient.Schedules schedules = client.platform().schedules();
                String etag = schedules.get(id).etag();
                schedules.delete(id, etag != null ? RequestOptions.ifMatch(etag) : RequestOptions.none());
                System.out.println(AnsiColors.green("✔ Schedule removed from job " + id));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error removing schedule: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "dispatches", description = "Show schedule dispatch audit history for a job")
    public static class DispatchesCommand implements Callable<Integer> {

        @Option(names = {"--id"}, required = true, description = "Job ID")
        private String id;

        @Option(names = {"--outcome"}, description = "Filter by outcome (STARTED, SKIPPED_OVERLAP, ...)")
        private String outcome;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<ScheduleDispatch> dispatches = client.platform().schedules().streamDispatches(id, outcome).toList();
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(dispatches));
                    return 0;
                }
                TableFormatter table = new TableFormatter()
                        .setHeaders("ID", "SCHEDULED FOR", "DISPATCHED AT", "RUNTIME", "OUTCOME", "EXECUTION ID", "DETAIL");
                for (ScheduleDispatch d : dispatches) {
                    table.addRow(
                            d.id(),
                            d.scheduledFor() != null ? d.scheduledFor().toString() : "",
                            d.dispatchedAt() != null ? d.dispatchedAt().toString() : "",
                            d.runtimeId() != null ? d.runtimeId() : "",
                            d.outcome() != null ? d.outcome().name() : "",
                            d.executionId() != null ? d.executionId() : "-",
                            d.detail() != null ? d.detail() : "");
                }
                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing dispatches: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "validate", description = "Validate a cron expression and preview its upcoming fire times")
    public static class ValidateCommand implements Callable<Integer> {

        @Option(names = {"--cron"}, required = true, description = "5-field POSIX or 6-field Spring cron expression")
        private String cron;

        @Option(names = {"--timezone"}, description = "IANA timezone ID (default: UTC)", defaultValue = "UTC")
        private String timezone;

        @Option(names = {"--count"}, description = "Number of upcoming runs to preview (1..20)", defaultValue = "5")
        private Integer count;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                CronValidationResult result = client.platform().schedules()
                        .validateCron(new CronValidationRequest(cron, timezone, count));
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(jsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return result.valid() ? 0 : 1;
                }
                if (!result.valid()) {
                    System.err.println(AnsiColors.red("✘ Invalid schedule: " + result.error()));
                    return 1;
                }
                System.out.println(AnsiColors.green("✔ Valid schedule: ") + result.humanDescription());
                System.out.println("Normalized Cron: " + result.normalizedCron() + " (" + result.timezone() + ")");
                for (Instant t : result.nextFireTimes()) {
                    System.out.println("  - " + t);
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error validating cron: " + e.getMessage()));
                return 1;
            }
        }
    }
}
