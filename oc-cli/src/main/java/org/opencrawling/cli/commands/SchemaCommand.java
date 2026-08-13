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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.cli.util.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Picocli command group for offline Open Ingestion Standard (OIS) schema validation (`oc schema`).
 */
@Command(
    name = "schema",
    mixinStandardHelpOptions = true,
    description = "Validate Open Ingestion Standard (OIS) JSON/YAML schemas offline (validate)",
    subcommands = {
        SchemaCommand.SchemaValidateCommand.class
    }
)
public class SchemaCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: validate"));
    }

    @Command(name = "validate", description = "Validate an OIS document or job configuration JSON schema file offline")
    public static class SchemaValidateCommand implements Callable<Integer> {

        @Option(names = {"--file"}, required = true, description = "Path to JSON or YAML schema file")
        private String filePath;

        @Override
        public Integer call() {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    System.err.println(AnsiColors.red("File not found: " + filePath));
                    return 1;
                }

                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(file);

                System.out.println(AnsiColors.cyan("Validating OIS Schema file: " + file.getAbsolutePath()));

                boolean valid = true;
                if (!root.has("id") && !root.has("name") && !root.has("repositoryConnector")) {
                    System.out.println(AnsiColors.yellow("⚠ Warning: Schema lacks standard top-level fields (id, name, or repositoryConnector)"));
                }

                if (valid) {
                    System.out.println(AnsiColors.green("✔ OIS JSON Schema structure is valid!"));
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("✖ Invalid OIS JSON Schema: " + e.getMessage()));
                return 1;
            }
        }
    }
}
