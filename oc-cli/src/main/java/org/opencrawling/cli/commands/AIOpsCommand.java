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
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.JobTraceResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Picocli command group for AIOps diagnostics & OpenTelemetry traces (`oc aiops`).
 */
@Command(
    name = "aiops",
    mixinStandardHelpOptions = true,
    description = "Query OpenTelemetry traces and AI Root Cause Analysis (rca, spans, errors, metrics)",
    subcommands = {
        AIOpsCommand.AIOpsRcaCommand.class,
        AIOpsCommand.AIOpsSpansCommand.class,
        AIOpsCommand.AIOpsErrorsCommand.class,
        AIOpsCommand.AIOpsMetricsCommand.class
    }
)
public class AIOpsCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: rca, spans, errors, metrics"));
    }

    @Command(name = "rca", description = "Fetch latest AI Root Cause Analysis report for a failing job")
    public static class AIOpsRcaCommand implements Callable<Integer> {

        @Option(names = {"--job-id"}, required = true, description = "Target job ID to analyze")
        private String jobId;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                System.out.println(AnsiColors.cyan("Running AI Root Cause Analysis for Job ID: " + jobId + "..."));
                DiagnosticReport report = client.observability().diagnose(jobId);

                System.out.println(AnsiColors.bold("\n--- AI Root Cause Analysis Report ---"));
                System.out.println(AnsiColors.bold("Job Name: ") + report.jobName());
                System.out.println(AnsiColors.bold("Status: ") + report.status());
                System.out.println(AnsiColors.bold("Summary: ") + report.summary());
                System.out.println(AnsiColors.green("\nRoot Cause Analysis:"));
                System.out.println(report.rootCauseAnalysis());

                if (report.bottleneckInsights() != null && !report.bottleneckInsights().isEmpty()) {
                    System.out.println(AnsiColors.yellow("\nBottleneck Insights:"));
                    report.bottleneckInsights().forEach(insight -> System.out.println(" • " + insight));
                }

                if (report.recommendedActions() != null && !report.recommendedActions().isEmpty()) {
                    System.out.println(AnsiColors.cyan("\nRecommended Actions:"));
                    report.recommendedActions().forEach(action -> System.out.println(" • " + action));
                }

                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error running RCA: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "spans", description = "Fetch OpenTelemetry spans and trace details")
    public static class AIOpsSpansCommand implements Callable<Integer> {

        @Option(names = {"--trace-id", "--job-id"}, required = true, description = "Job ID or Trace ID to inspect")
        private String id;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                JobTraceResponse traces = client.observability().getTraces(id);
                ObjectMapper mapper = new ObjectMapper();

                System.out.println(AnsiColors.green("✔ OpenTelemetry Traces for ID: " + id));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(traces));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching spans: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "errors", description = "Fetch correlated error logs for a job within a timeframe window")
    public static class AIOpsErrorsCommand implements Callable<Integer> {

        @Option(names = {"--job-id"}, required = true, description = "Job ID to inspect")
        private String jobId;

        @Option(names = {"--timeframe"}, description = "Timeframe window (e.g. 1h, 24h, all)", defaultValue = "24h")
        private String timeframe;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                var errors = client.observability().getErrors(jobId, timeframe);
                ObjectMapper mapper = new ObjectMapper();

                System.out.println(AnsiColors.green("✔ Error Logs for Job ID: " + jobId + " (" + timeframe + ")"));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(errors));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching error logs: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "metrics", description = "Query connector throughput and virtual thread concurrency metrics")
    public static class AIOpsMetricsCommand implements Callable<Integer> {

        @Option(names = {"--connector-id"}, required = true, description = "Connector ID to query")
        private String connectorId;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                var metrics = client.observability().getMetrics(connectorId);
                ObjectMapper mapper = new ObjectMapper();

                System.out.println(AnsiColors.green("✔ Metrics for Connector ID: " + connectorId));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching connector metrics: " + e.getMessage()));
                return 1;
            }
        }
    }
}
