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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

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

                String actionVal = null;
                if (root.has("action")) {
                    actionVal = root.get("action").asText();
                    if (!"UPSERT".equalsIgnoreCase(actionVal) && !"DELETE".equalsIgnoreCase(actionVal)) {
                        System.out.println(AnsiColors.red("✖ Invalid OIS action field value: " + actionVal + " (must be UPSERT or DELETE)"));
                        return 1;
                    }
                    if ("DELETE".equalsIgnoreCase(actionVal)) {
                        System.out.println(AnsiColors.cyan("ℹ OIS Document Tombstone DELETE action payload detected."));
                    }
                }

                if (root.has("pipelineMode")) {
                    String mode = root.get("pipelineMode").asText();
                    if (!mode.isBlank() && !"rag".equalsIgnoreCase(mode) && !"migration".equalsIgnoreCase(mode)) {
                        System.out.println(AnsiColors.red("✖ Invalid job pipelineMode: " + mode + " (must be rag or migration)"));
                        valid = false;
                    }
                }

                if (root.has("contentRef")) {
                    System.out.println(AnsiColors.cyan("ℹ OIS Migration Mode document payload detected (contains contentRef binary locator)."));
                    List<String> errors = validateMigrationEnvelope(root, actionVal);
                    for (String error : errors) {
                        System.out.println(AnsiColors.red("✖ " + error));
                    }
                    valid &= errors.isEmpty();
                }

                if (valid) {
                    System.out.println(AnsiColors.green("✔ OIS JSON Schema structure is valid!"));
                    return 0;
                }
                System.out.println(AnsiColors.red("✖ OIS JSON Schema validation failed."));
                return 1;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("✖ Invalid OIS JSON Schema: " + e.getMessage()));
                return 1;
            }
        }

        /**
         * Validates an OIS Migration Mode envelope (binary sidecar) as produced by migration-mode
         * output connectors such as the Apache Ozone output connector.
         */
        static List<String> validateMigrationEnvelope(JsonNode root, String action) {
            List<String> errors = new ArrayList<>();
            if (!root.hasNonNull("id") || root.get("id").asText().isBlank()) {
                errors.add("OIS migration envelope requires a non-empty 'id'");
            }

            JsonNode contentRef = root.get("contentRef");
            if ("DELETE".equalsIgnoreCase(action)) {
                if (contentRef != null && !contentRef.isNull()) {
                    errors.add("DELETE tombstones must not carry a 'contentRef' (no content stream)");
                }
            } else if (contentRef != null && !contentRef.isNull()) {
                if (!contentRef.isObject()) {
                    errors.add("'contentRef' must be an object");
                } else {
                    String sha = contentRef.path("checksumSha256").asText("");
                    if (!SHA256_HEX.matcher(sha).matches()) {
                        errors.add("'contentRef.checksumSha256' must be a 64-character hex SHA-256 digest");
                    }
                    JsonNode length = contentRef.get("contentLength");
                    if (length == null || !length.canConvertToLong() || length.asLong() < 0) {
                        errors.add("'contentRef.contentLength' must be a non-negative integer");
                    }
                    if (contentRef.path("mimeType").asText("").isBlank()) {
                        errors.add("'contentRef.mimeType' is required");
                    }
                    if (contentRef.path("key").asText("").isBlank() && contentRef.path("claimCheckUri").asText("").isBlank()) {
                        errors.add("'contentRef' requires a binary locator ('key' or 'claimCheckUri')");
                    }
                }
            }

            JsonNode security = root.get("security");
            if (security != null && !security.isNull()) {
                if (security.has("inheritanceEnabled") && !security.get("inheritanceEnabled").isBoolean()) {
                    errors.add("'security.inheritanceEnabled' must be a boolean");
                }
                JsonNode permissions = security.get("permissions");
                if (permissions != null && !permissions.isNull()) {
                    if (!permissions.isArray()) {
                        errors.add("'security.permissions' must be an array");
                    } else {
                        for (int i = 0; i < permissions.size(); i++) {
                            JsonNode p = permissions.get(i);
                            // Accept both the core SecurityConfig field names and the RFC/issue variant
                            String identity = p.path("identity").asText(p.path("principalId").asText(""));
                            String access = p.path("access").asText(p.path("accessType").asText(""));
                            if (identity.isBlank()) {
                                errors.add("'security.permissions[" + i + "]' requires 'identity' (or 'principalId')");
                            }
                            if (access.isBlank()) {
                                errors.add("'security.permissions[" + i + "]' requires 'access' (or 'accessType')");
                            }
                        }
                    }
                }
            }
            return errors;
        }

        private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
    }
}
