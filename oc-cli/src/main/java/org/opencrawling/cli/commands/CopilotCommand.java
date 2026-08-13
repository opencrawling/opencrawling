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
import org.opencrawling.sdk.models.CopilotRequest;
import org.opencrawling.sdk.models.CopilotResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Picocli command group for Auto-Narrativization Copilot operations (`oc copilot`).
 */
@Command(
    name = "copilot",
    mixinStandardHelpOptions = true,
    description = "Execute Auto-Narrativization Copilot tasks (narrativize, mock)",
    subcommands = {
        CopilotCommand.CopilotNarrativizeCommand.class,
        CopilotCommand.CopilotMockCommand.class
    }
)
public class CopilotCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: narrativize, mock"));
    }

    @Command(name = "narrativize", description = "Generate natural language Mustache template from a JSON schema file")
    public static class CopilotNarrativizeCommand implements Callable<Integer> {

        @Option(names = {"--schema"}, required = true, description = "Path to JSON schema file or connector type")
        private String schemaPath;

        @Option(names = {"--engine"}, description = "LLM engine (ollama, openai)", defaultValue = "ollama")
        private String engine;

        @Option(names = {"--model"}, description = "LLM model name", defaultValue = "llama3.2")
        private String model;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                System.out.println(AnsiColors.cyan("Generating Mustache template using engine [" + engine + "] and model [" + model + "]..."));

                CopilotRequest request = CopilotRequest.builder()
                        .connectorType(schemaPath)
                        .addField("title", "string", "Document Title")
                        .addField("author", "string", "Document Author")
                        .addField("content", "string", "Main Body Content")
                        .build();

                CopilotResponse response = client.narrativization().generateTemplate(request);

                System.out.println(AnsiColors.green("✔ Generated Template:"));
                System.out.println(AnsiColors.bold(response.template()));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error executing copilot narrativize: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "mock", description = "Generate mock JSON metadata output from a schema")
    public static class CopilotMockCommand implements Callable<Integer> {

        @Option(names = {"--schema"}, required = true, description = "Path to schema or connector type")
        private String schemaPath;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                CopilotRequest request = CopilotRequest.builder()
                        .connectorType(schemaPath)
                        .addField("id", "string", "Unique ID")
                        .addField("summary", "string", "Summary")
                        .build();

                CopilotResponse response = client.narrativization().generateTemplate(request);

                ObjectMapper mapper = new ObjectMapper();
                System.out.println(AnsiColors.green("✔ Mock Data Output:"));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response.mockData()));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error generating mock data: " + e.getMessage()));
                return 1;
            }
        }
    }
}
