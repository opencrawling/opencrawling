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

import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.cli.util.TableFormatter;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.ConnectionCheckResponse;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.ConnectorResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Picocli command group for inspecting and testing connectors (`oc connector`).
 */
@Command(
    name = "connector",
    mixinStandardHelpOptions = true,
    description = "Inspect & test repository / output connectors (list, check, create, delete)",
    subcommands = {
        ConnectorCommand.ConnectorListCommand.class,
        ConnectorCommand.ConnectorCheckCommand.class,
        ConnectorCommand.ConnectorCreateCommand.class,
        ConnectorCommand.ConnectorDeleteCommand.class
    }
)
public class ConnectorCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: list, check, create, delete"));
    }

    @Command(name = "list", description = "List all available connectors (repository, output, transformation)")
    public static class ConnectorListCommand implements Callable<Integer> {

        @Option(names = {"--type"}, description = "Filter by type: repository, output, transformation", defaultValue = "repository")
        private String type;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                List<ConnectorResponse> connectors = client.connectors().list(type);

                TableFormatter table = new TableFormatter()
                        .setHeaders("NAME", "TYPE", "CLASS", "MAX CONNECTIONS", "DESCRIPTION");

                for (ConnectorResponse conn : connectors) {
                    table.addRow(
                            conn.name(),
                            conn.type(),
                            conn.className() != null ? conn.className() : "N/A",
                            String.valueOf(conn.maxConnections()),
                            conn.description() != null ? conn.description() : ""
                    );
                }

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing connectors: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "check", description = "Test connection health against a target repository or output connector")
    public static class ConnectorCheckCommand implements Callable<Integer> {

        @Option(names = {"--name"}, required = true, description = "Connector name to check")
        private String name;

        @Option(names = {"--type"}, description = "Connector type: repository, output, transformation", defaultValue = "output")
        private String type;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                ConnectorRequest req = ConnectorRequest.builder()
                        .name(name)
                        .type(type)
                        .build();

                ConnectionCheckResponse check = client.connectors().checkConnection(req);

                if (check.success()) {
                    System.out.println(AnsiColors.green("✔ Connection Successful: ") + check.message());
                } else {
                    System.err.println(AnsiColors.red("✖ Connection Failed: ") + check.message());
                }
                return check.success() ? 0 : 1;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error testing connection: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "create", description = "Create or update a connector configuration from JSON file")
    public static class ConnectorCreateCommand implements Callable<Integer> {

        @Option(names = {"--file"}, required = true, description = "Path to connector JSON file")
        private String filePath;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ConnectorRequest request = mapper.readValue(new java.io.File(filePath), ConnectorRequest.class);
                client.connectors().create(request);
                System.out.println(AnsiColors.green("✔ Connector created/updated successfully!"));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error creating connector: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "delete", description = "Delete a connector configuration by name")
    public static class ConnectorDeleteCommand implements Callable<Integer> {

        @Option(names = {"--name"}, required = true, description = "Connector name to delete")
        private String name;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                client.connectors().delete(name);
                System.out.println(AnsiColors.green("✔ Connector deleted: " + name));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error deleting connector: " + e.getMessage()));
                return 1;
            }
        }
    }
}
