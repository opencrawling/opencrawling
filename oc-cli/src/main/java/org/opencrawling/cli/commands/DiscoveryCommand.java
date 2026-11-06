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
import org.opencrawling.sdk.models.DiscoveryConnectorInfo;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunView;
import org.opencrawling.sdk.models.DiscoveryNodeView;
import org.opencrawling.sdk.models.DiscoveryTreeRequest;
import org.opencrawling.sdk.models.DiscoveryTreeView;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Picocli command group for pre-crawl repository discovery and dry-run inspection (`oc discovery`).
 */
@Command(
    name = "discovery",
    mixinStandardHelpOptions = true,
    description = "Pre-crawl repository discovery and dry-run inspection (connectors, tree, dry-run)",
    subcommands = {
        DiscoveryCommand.ConnectorsCommand.class,
        DiscoveryCommand.TreeCommand.class,
        DiscoveryCommand.DryRunCommand.class
    }
)
public class DiscoveryCommand implements Runnable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: connectors, tree, dry-run"));
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null || bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    @Command(name = "connectors", aliases = {"list"}, mixinStandardHelpOptions = true,
            description = "List registered repository connectors that support discovery")
    public static class ConnectorsCommand implements Callable<Integer> {

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Option(names = {"--token"}, description = "Override Bearer token")
        private String token;

        @Option(names = {"--user-principal"}, description = "Asserted user principal (dev/test only)")
        private String userPrincipal;

        @Option(names = {"--user-roles"}, description = "Asserted comma-separated user roles (dev/test only)")
        private String userRoles;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey, token)) {
                List<DiscoveryConnectorInfo> connectors = client.discovery().listConnectors(userPrincipal, userRoles);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(connectors));
                    return 0;
                }

                if (connectors == null || connectors.isEmpty()) {
                    System.out.println(AnsiColors.yellow("No repository connectors supporting discovery were found."));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("NAME", "DESCRIPTION");

                for (DiscoveryConnectorInfo c : connectors) {
                    table.addRow(
                            c.name() != null ? c.name() : "-",
                            c.description() != null ? c.description() : ""
                    );
                }

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing discoverable connectors: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "tree", mixinStandardHelpOptions = true,
            description = "Explore a repository's folder/document hierarchy without downloading content")
    public static class TreeCommand implements Callable<Integer> {

        @Option(names = {"--connector"}, description = "Repository connector name")
        private String connectorOption;

        @Parameters(index = "0", arity = "0..1", description = "Repository connector name as positional parameter")
        private String connectorParam;

        @Option(names = {"--start"}, description = "Folder ID or path to start exploration from (defaults to root)")
        private String start;

        @Option(names = {"--depth"}, description = "Exploration depth limit (1 = children of start)")
        private Integer depth;

        @Option(names = {"--max-nodes"}, description = "Maximum nodes to return")
        private Integer maxNodes;

        @Option(names = {"--no-documents"}, description = "Exclude documents and list folders only")
        private boolean noDocuments;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Option(names = {"--token"}, description = "Override Bearer token")
        private String token;

        @Option(names = {"--user-principal"}, description = "Asserted user principal (dev/test only)")
        private String userPrincipal;

        @Option(names = {"--user-roles"}, description = "Asserted comma-separated user roles (dev/test only)")
        private String userRoles;

        @Option(names = {"--format"}, description = "Output format: tree, table, json", defaultValue = "tree")
        private String format;

        @Override
        public Integer call() {
            String connector = (connectorOption != null && !connectorOption.isBlank()) ? connectorOption : connectorParam;
            if (connector == null || connector.isBlank()) {
                System.err.println(AnsiColors.red("Connector name is required (pass via --connector or positional parameter)"));
                return 1;
            }

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey, token)) {
                DiscoveryTreeRequest request = DiscoveryTreeRequest.builder()
                        .start(start)
                        .depth(depth)
                        .maxNodes(maxNodes)
                        .includeDocuments(!noDocuments)
                        .userPrincipal(userPrincipal)
                        .userRoles(userRoles)
                        .build();

                DiscoveryTreeView result = client.discovery().exploreTree(connector, request);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return 0;
                }

                System.out.println(AnsiColors.bold("Connector: ") + result.connector());
                System.out.println(AnsiColors.bold("Root:      ") + (result.root() != null ? result.root() : "<default root>"));
                System.out.println(AnsiColors.bold("Nodes:     ") + result.nodes().size()
                        + (result.hiddenCount() != null && result.hiddenCount() > 0 ? " (" + result.hiddenCount() + " hidden by ACL)" : ""));

                if (result.truncated()) {
                    System.out.println(AnsiColors.yellow("⚠️  Truncated: exploration stopped early due to " + result.truncationReason()));
                }

                if (result.nodes().isEmpty()) {
                    System.out.println(AnsiColors.yellow("No nodes found within the specified scope."));
                    return 0;
                }

                if ("table".equalsIgnoreCase(format)) {
                    TableFormatter table = new TableFormatter()
                            .setHeaders("DEPTH", "TYPE", "NAME", "PATH", "MIME TYPE", "SIZE");

                    for (DiscoveryNodeView node : result.nodes()) {
                        table.addRow(
                                String.valueOf(node.depth()),
                                node.type() != null ? node.type() : "-",
                                node.name() != null ? node.name() : "-",
                                node.path() != null ? node.path() : "-",
                                node.mimeType() != null ? node.mimeType() : "-",
                                formatBytes(node.sizeBytes())
                        );
                    }
                    System.out.println();
                    System.out.println(table.render());
                } else {
                    // Tree visualization format
                    System.out.println();
                    for (DiscoveryNodeView node : result.nodes()) {
                        String indent = "  ".repeat(Math.max(0, node.depth()));
                        String icon = "FOLDER".equalsIgnoreCase(node.type()) ? "📁 " : "📄 ";
                        String extra = "FOLDER".equalsIgnoreCase(node.type())
                                ? (node.path() != null ? "(" + node.path() + ")" : "")
                                : "(" + (node.mimeType() != null ? node.mimeType() : "unknown")
                                        + (node.sizeBytes() != null ? ", " + formatBytes(node.sizeBytes()) : "") + ")";
                        System.out.println(indent + icon + AnsiColors.bold(node.name()) + (extra.isBlank() ? "" : " " + extra));
                    }
                }

                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error exploring tree for connector '" + connector + "': " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "dry-run", mixinStandardHelpOptions = true,
            description = "Preview a crawl of a folder scope or read-only query (counts and samples)")
    public static class DryRunCommand implements Callable<Integer> {

        @Option(names = {"--connector"}, description = "Repository connector name")
        private String connectorOption;

        @Parameters(index = "0", arity = "0..1", description = "Repository connector name as positional parameter")
        private String connectorParam;

        @Option(names = {"--scope"}, description = "Folder ID/path or read-only query (e.g. CMIS SQL SELECT)")
        private String scope;

        @Option(names = {"--sample-size"}, description = "Maximum matching sample documents to return")
        private Integer sampleSize;

        @Option(names = {"--max-scan"}, description = "Maximum items scanned before returning a lower-bound count")
        private Integer maxScan;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Option(names = {"--token"}, description = "Override Bearer token")
        private String token;

        @Option(names = {"--user-principal"}, description = "Asserted user principal (dev/test only)")
        private String userPrincipal;

        @Option(names = {"--user-roles"}, description = "Asserted comma-separated user roles (dev/test only)")
        private String userRoles;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Override
        public Integer call() {
            String connector = (connectorOption != null && !connectorOption.isBlank()) ? connectorOption : connectorParam;
            if (connector == null || connector.isBlank()) {
                System.err.println(AnsiColors.red("Connector name is required (pass via --connector or positional parameter)"));
                return 1;
            }

            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey, token)) {
                DiscoveryDryRunRequest request = DiscoveryDryRunRequest.builder()
                        .scope(scope)
                        .sampleSize(sampleSize)
                        .maxScan(maxScan)
                        .userPrincipal(userPrincipal)
                        .userRoles(userRoles)
                        .build();

                DiscoveryDryRunView result = client.discovery().dryRun(connector, request);

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return 0;
                }

                System.out.println(AnsiColors.bold("Connector:    ") + result.connector());
                System.out.println(AnsiColors.bold("Scope:        ") + (scope != null ? scope : "<configured default>"));
                System.out.println(AnsiColors.bold("Match Count:  ") + (result.matchCount() != null ? result.matchCount() : "Withheld (query mode)")
                        + (result.countAccuracy() != null ? " [" + result.countAccuracy() + "]" : ""));

                if (result.hiddenCount() != null && result.hiddenCount() > 0) {
                    System.out.println(AnsiColors.bold("Hidden Count: ") + result.hiddenCount() + " items hidden by caller ACLs");
                }

                if (result.truncated()) {
                    System.out.println(AnsiColors.yellow("⚠️  Truncated: preview stopped early due to " + result.truncationReason()));
                }

                if (result.note() != null && !result.note().isBlank()) {
                    System.out.println(AnsiColors.cyan("ℹ️  Note: " + result.note()));
                }

                if (result.samples() != null && !result.samples().isEmpty()) {
                    System.out.println();
                    System.out.println(AnsiColors.bold("Sample Matching Documents (" + result.samples().size() + "):"));
                    TableFormatter table = new TableFormatter()
                            .setHeaders("NAME", "PATH", "MIME TYPE", "SIZE");

                    for (DiscoveryNodeView sample : result.samples()) {
                        table.addRow(
                                sample.name() != null ? sample.name() : "-",
                                sample.path() != null ? sample.path() : "-",
                                sample.mimeType() != null ? sample.mimeType() : "-",
                                formatBytes(sample.sizeBytes())
                        );
                    }
                    System.out.println(table.render());
                } else {
                    System.out.println(AnsiColors.yellow("\nNo sample documents returned."));
                }

                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error executing dry-run for connector '" + connector + "': " + e.getMessage()));
                return 1;
            }
        }
    }
}
