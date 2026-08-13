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

import org.opencrawling.cli.config.CliConfig;
import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.util.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Picocli command group for CLI context & credential management (`oc config`).
 */
@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = "Manage CLI contexts and active runtime credentials (set, get, context)",
    subcommands = {
        ConfigCommand.ConfigSetCommand.class,
        ConfigCommand.ConfigGetCommand.class,
        ConfigCommand.ConfigContextCommand.class
    }
)
public class ConfigCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: set, get, context"));
    }

    @Command(name = "set", description = "Configure active server URL and API credentials")
    public static class ConfigSetCommand implements Callable<Integer> {

        @Option(names = {"--server-url"}, required = true, description = "Target OpenCrawling runtime REST API URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "API Key for authentication", defaultValue = "")
        private String apiKey;

        @Option(names = {"--bearer-token"}, description = "Bearer token for JWT authentication", defaultValue = "")
        private String bearerToken;

        @Option(names = {"--context"}, description = "Context name", defaultValue = "default")
        private String context;

        @Override
        public Integer call() {
            try {
                CliConfig config = new CliConfig(serverUrl, apiKey, bearerToken, context);
                CliConfigService.saveConfig(config);

                System.out.println(AnsiColors.green("✔ CLI configuration updated successfully!"));
                System.out.println("Server URL: " + serverUrl);
                System.out.println("Context: " + context);
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error saving CLI config: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "get", description = "Get current CLI configuration")
    public static class ConfigGetCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            CliConfig config = CliConfigService.loadConfig();
            System.out.println(AnsiColors.bold("Current CLI Configuration:"));
            System.out.println("  Server URL: " + config.serverUrl());
            System.out.println("  API Key: " + (config.apiKey().isBlank() ? "(none)" : "*****"));
            System.out.println("  Active Context: " + config.activeContext());
            return 0;
        }
    }

    @Command(name = "context", description = "Display active context name")
    public static class ConfigContextCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            CliConfig config = CliConfigService.loadConfig();
            System.out.println(AnsiColors.bold("Active Context: ") + AnsiColors.cyan(config.activeContext()));
            return 0;
        }
    }
}
