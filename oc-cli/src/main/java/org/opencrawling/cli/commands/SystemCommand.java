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
import org.opencrawling.sdk.models.SystemSettings;
import org.opencrawling.sdk.models.SystemStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Picocli command group for OpenCrawling system operations (`oc system`).
 */
@Command(
    name = "system",
    mixinStandardHelpOptions = true,
    description = "Query runtime system health, throughput metrics, logs, and settings (status, logs, settings, throughput)",
    subcommands = {
        SystemCommand.SystemStatusCommand.class,
        SystemCommand.SystemLogsCommand.class,
        SystemCommand.SystemSettingsCommand.class,
        SystemCommand.SystemThroughputCommand.class
    }
)
public class SystemCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: status, logs, settings, throughput"));
    }

    @Command(name = "status", description = "Query runtime component health status")
    public static class SystemStatusCommand implements Callable<Integer> {

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                SystemStatus status = client.system().getStatus();

                System.out.println(AnsiColors.bold("\n--- OpenCrawling System Health Status ---"));
                TableFormatter table = new TableFormatter().setHeaders("COMPONENT", "STATUS");
                
                table.addRow("PostgreSQL", formatStatus(status.postgres()));
                table.addRow("Redis", formatStatus(status.redis()));
                table.addRow("Ollama", formatStatus(status.ollama()));
                table.addRow("System Overall", formatStatus(status.system()));

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error checking system status: " + e.getMessage()));
                return 1;
            }
        }

        private String formatStatus(String val) {
            if (val == null) return AnsiColors.yellow("UNKNOWN");
            return "UP".equalsIgnoreCase(val) || "CONNECTED".equalsIgnoreCase(val) || "HEALTHY".equalsIgnoreCase(val) || "OK".equalsIgnoreCase(val)
                    ? AnsiColors.green(val) : AnsiColors.red(val);
        }
    }

    @Command(name = "logs", description = "Stream or inspect in-memory system log entries")
    public static class SystemLogsCommand implements Callable<Integer> {

        @Option(names = {"--tail"}, description = "Number of recent log lines to display", defaultValue = "50")
        private int tail;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<String> logs = client.system().getLogs();
                int start = Math.max(0, logs.size() - tail);
                System.out.println(AnsiColors.bold("--- System Logs (Last " + (logs.size() - start) + " lines) ---"));
                for (int i = start; i < logs.size(); i++) {
                    System.out.println(logs.get(i));
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching system logs: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "settings", description = "Query current system settings")
    public static class SystemSettingsCommand implements Callable<Integer> {

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                SystemSettings settings = client.system().getSettings();
                ObjectMapper mapper = new ObjectMapper();
                System.out.println(AnsiColors.green("✔ Current System Settings:"));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching system settings: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "throughput", description = "Query document ingestion throughput over time")
    public static class SystemThroughputCommand implements Callable<Integer> {

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<Map<String, Object>> throughput = client.system().getThroughput();
                ObjectMapper mapper = new ObjectMapper();
                System.out.println(AnsiColors.green("✔ Ingestion Throughput Metrics:"));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(throughput));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error fetching throughput metrics: " + e.getMessage()));
                return 1;
            }
        }
    }
}
