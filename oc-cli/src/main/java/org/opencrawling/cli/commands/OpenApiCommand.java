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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.opencrawling.cli.config.CliConfig;
import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.cli.util.TableFormatter;
import org.opencrawling.sdk.DefaultOpenApiClient;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.OpenApiEndpointSummary;
import org.opencrawling.sdk.models.OpenApiStatusSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Picocli command group for OpenAPI 3.1 contract management, introspection, governance validation,
 * contract diffing, and AI agent tool export ({@code oc openapi}).
 */
@Command(
    name = "openapi",
    mixinStandardHelpOptions = true,
    description = "OpenAPI 3.1 contract management, introspection, validation, diffing, and AI tool export (status, export, endpoints, schema, validate, diff, tools, ui)",
    subcommands = {
        OpenApiCommand.OpenApiStatusCommand.class,
        OpenApiCommand.OpenApiExportCommand.class,
        OpenApiCommand.OpenApiEndpointsCommand.class,
        OpenApiCommand.OpenApiSchemaCommand.class,
        OpenApiCommand.OpenApiValidateCommand.class,
        OpenApiCommand.OpenApiDiffCommand.class,
        OpenApiCommand.OpenApiToolsCommand.class,
        OpenApiCommand.OpenApiUiCommand.class
    }
)
public class OpenApiCommand implements Runnable {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            new YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    );

    private static final List<String> HTTP_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "options", "head", "trace"
    );

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: status, export, endpoints, schema, validate, diff, tools, ui"));
    }

    static JsonNode loadSpec(File localFile, String serverUrl, String apiKey) throws Exception {
        if (localFile != null) {
            if (!localFile.exists() || !localFile.isFile()) {
                throw new IllegalArgumentException("OpenAPI specification file not found: " + localFile.getAbsolutePath());
            }
            return parseFileOrString(Files.readString(localFile.toPath()), localFile.getName());
        }
        try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
            return client.openApi().fetchSpecification();
        }
    }

    static JsonNode loadSpecFromPathOrUrl(String pathOrUrl, String serverUrl, String apiKey) throws Exception {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return loadSpec(null, serverUrl, apiKey);
        }
        String trimmed = pathOrUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.endsWith("/v3/api-docs") || trimmed.endsWith(".json") || trimmed.endsWith(".yaml") || trimmed.endsWith(".yml")) {
                java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(trimmed)).GET().build();
                java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 400) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + " fetching OpenAPI spec from " + trimmed);
                }
                return parseFileOrString(resp.body(), trimmed);
            }
            try (OpenCrawlingClient client = CliConfigService.createClient(trimmed, apiKey)) {
                return client.openApi().fetchSpecification();
            }
        }
        return loadSpec(new File(trimmed), serverUrl, apiKey);
    }

    static JsonNode parseFileOrString(String content, String hintName) throws Exception {
        String trimmed = content != null ? content.trim() : "";
        boolean looksJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean hintYaml = hintName != null && (hintName.endsWith(".yaml") || hintName.endsWith(".yml"));
        if (hintYaml && !looksJson) {
            return YAML_MAPPER.readTree(content);
        }
        try {
            return JSON_MAPPER.readTree(content);
        } catch (Exception jsonEx) {
            return YAML_MAPPER.readTree(content);
        }
    }

    private static String colorHttpMethod(String method) {
        if (method == null) return "-";
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET" -> AnsiColors.cyan("GET");
            case "POST" -> AnsiColors.green("POST");
            case "PUT", "PATCH" -> AnsiColors.yellow(method.toUpperCase(Locale.ROOT));
            case "DELETE" -> AnsiColors.red("DELETE");
            default -> method.toUpperCase(Locale.ROOT);
        };
    }

    // =========================================================================
    // 1. oc openapi status
    // =========================================================================
    @Command(name = "status", mixinStandardHelpOptions = true, description = "Check OpenAPI 3.1 (/v3/api-docs) and Swagger UI (/swagger-ui.html) availability and metadata")
    public static class OpenApiStatusCommand implements Callable<Integer> {

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try (OpenCrawlingClient client = CliConfigService.createClient(serverUrl, apiKey)) {
                OpenApiStatusSummary status = client.openApi().checkStatus();

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(status));
                    return 0;
                }

                String specState = status.specReachable()
                        ? AnsiColors.green("UP") + " (" + status.specLatencyMs() + "ms)"
                        : AnsiColors.red("DOWN");
                String uiState = status.swaggerUiReachable()
                        ? AnsiColors.green("UP")
                        : AnsiColors.yellow("UNREACHABLE");
                String secSchemes = status.securitySchemes() != null && !status.securitySchemes().isEmpty()
                        ? String.join(", ", status.securitySchemes())
                        : "None";
                String tagSummary = (status.tags() != null ? status.tags().size() : 0) + " tags";

                TableFormatter table = new TableFormatter()
                        .setHeaders("PROPERTY", "VALUE")
                        .addRow("Title", status.title())
                        .addRow("API Version", status.apiVersion() + " (OpenAPI " + status.openApiVersion() + ")")
                        .addRow("License", status.licenseName() != null ? status.licenseName() : "-")
                        .addRow("Spec Endpoint", status.specEndpointUrl() + " (" + specState + ")")
                        .addRow("Swagger UI", status.swaggerUiUrl() + " (" + uiState + ")")
                        .addRow("Documented Paths / Operations", status.pathCount() + " paths / " + status.operationCount() + " operations")
                        .addRow("Component Schemas", status.schemaCount() + " schemas")
                        .addRow("Security Schemes", secSchemes)
                        .addRow("Domain Tags", tagSummary);

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error checking OpenAPI status: " + e.getMessage()));
                return 1;
            }
        }
    }

    // =========================================================================
    // 2. oc openapi export
    // =========================================================================
    @Command(name = "export", mixinStandardHelpOptions = true, description = "Download and export the OpenAPI 3.1 specification to JSON or YAML")
    public static class OpenApiExportCommand implements Callable<Integer> {

        @Option(names = {"--output", "-o"}, description = "Output file path (prints to stdout if omitted)")
        private Path output;

        @Option(names = {"--format"}, description = "Output format: json, yaml (auto-detected from file extension or defaults to json)")
        private String format;

        @Option(names = {"--pretty"}, description = "Pretty-print the exported specification", defaultValue = "true")
        private boolean pretty;

        @Option(names = {"--tag", "-t"}, description = "Optional filter to export only paths belonging to a specific domain tag")
        private String tagFilter;

        @Option(names = {"--file", "-f"}, description = "Optional local OpenAPI JSON/YAML file to transform/export instead of fetching live")
        private File inputFile;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try {
                JsonNode specNode = loadSpec(inputFile, serverUrl, apiKey);
                if (tagFilter != null && !tagFilter.isBlank() && specNode.isObject()) {
                    specNode = filterSpecByTag((ObjectNode) specNode.deepCopy(), tagFilter.trim());
                }

                String resolvedFormat = resolveFormat(format, output);
                String serialized;
                if ("yaml".equalsIgnoreCase(resolvedFormat) || "yml".equalsIgnoreCase(resolvedFormat)) {
                    serialized = YAML_MAPPER.writeValueAsString(specNode);
                } else {
                    serialized = pretty
                            ? JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(specNode)
                            : JSON_MAPPER.writeValueAsString(specNode);
                }

                if (output != null) {
                    if (output.getParent() != null) {
                        Files.createDirectories(output.getParent());
                    }
                    Files.writeString(output, serialized);
                    List<OpenApiEndpointSummary> ops = DefaultOpenApiClient.extractEndpoints(specNode, null, null);
                    int schemaCount = specNode.path("components").path("schemas").size();
                    String version = specNode.path("openapi").asText("3.1.0");
                    System.out.println(AnsiColors.green("✔ Exported OpenAPI " + version + " specification ("
                            + ops.size() + " operations, " + schemaCount + " schemas) to " + output));
                } else {
                    System.out.println(serialized);
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error exporting OpenAPI specification: " + e.getMessage()));
                return 1;
            }
        }

        private static String resolveFormat(String explicitFormat, Path output) {
            if (explicitFormat != null && !explicitFormat.isBlank()) {
                return explicitFormat.trim().toLowerCase(Locale.ROOT);
            }
            if (output != null) {
                String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                    return "yaml";
                }
            }
            return "json";
        }

        private static ObjectNode filterSpecByTag(ObjectNode root, String tagFilter) {
            String needle = tagFilter.toLowerCase(Locale.ROOT);
            JsonNode paths = root.path("paths");
            if (paths.isObject()) {
                ObjectNode filteredPaths = JSON_MAPPER.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
                while (pathIter.hasNext()) {
                    Map.Entry<String, JsonNode> pathEntry = pathIter.next();
                    if (!pathEntry.getValue().isObject()) continue;
                    ObjectNode filteredOps = JSON_MAPPER.createObjectNode();
                    Iterator<Map.Entry<String, JsonNode>> opIter = pathEntry.getValue().fields();
                    while (opIter.hasNext()) {
                        Map.Entry<String, JsonNode> opEntry = opIter.next();
                        JsonNode opNode = opEntry.getValue();
                        if (opNode.isObject() && hasMatchingTag(opNode.path("tags"), needle)) {
                            filteredOps.set(opEntry.getKey(), opNode);
                        }
                    }
                    if (!filteredOps.isEmpty()) {
                        filteredPaths.set(pathEntry.getKey(), filteredOps);
                    }
                }
                root.set("paths", filteredPaths);
            }
            return root;
        }

        private static boolean hasMatchingTag(JsonNode tagsNode, String needle) {
            if (!tagsNode.isArray()) return false;
            for (JsonNode t : tagsNode) {
                if (t.asText("").toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    // =========================================================================
    // 3. oc openapi endpoints
    // =========================================================================
    @Command(name = "endpoints", aliases = {"ops"}, mixinStandardHelpOptions = true, description = "List and filter all documented REST endpoints by domain tag, HTTP method, or path pattern")
    public static class OpenApiEndpointsCommand implements Callable<Integer> {

        @Option(names = {"--tag", "-t"}, description = "Filter by OpenAPI domain tag (case-insensitive substring)")
        private String tag;

        @Option(names = {"--method", "-m"}, description = "Filter by HTTP verb: GET, POST, PUT, DELETE, PATCH")
        private String method;

        @Option(names = {"--search", "-s"}, description = "Filter by path, operationId, or summary keyword")
        private String search;

        @Option(names = {"--file", "-f"}, description = "Read from a local OpenAPI JSON/YAML file instead of a live server")
        private File file;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try {
                JsonNode spec = loadSpec(file, serverUrl, apiKey);
                List<OpenApiEndpointSummary> endpoints = DefaultOpenApiClient.extractEndpoints(spec, tag, method);

                if (search != null && !search.isBlank()) {
                    String q = search.trim().toLowerCase(Locale.ROOT);
                    endpoints = endpoints.stream()
                            .filter(ep -> (ep.path() != null && ep.path().toLowerCase(Locale.ROOT).contains(q))
                                    || (ep.operationId() != null && ep.operationId().toLowerCase(Locale.ROOT).contains(q))
                                    || (ep.summary() != null && ep.summary().toLowerCase(Locale.ROOT).contains(q)))
                            .toList();
                }

                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(endpoints));
                    return 0;
                }

                if (endpoints.isEmpty()) {
                    System.out.println(AnsiColors.yellow("No OpenAPI endpoints matched the specified criteria."));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("METHOD", "PATH", "OPERATION ID", "DOMAIN TAG", "SUMMARY", "SECURITY");

                for (OpenApiEndpointSummary ep : endpoints) {
                    String tagStr = ep.tags() != null && !ep.tags().isEmpty() ? String.join(", ", ep.tags()) : "-";
                    String secStr = ep.security() != null && !ep.security().isEmpty() ? String.join(", ", ep.security()) : "-";
                    String summary = ep.summary() != null && !ep.summary().isBlank() ? ep.summary() : "-";
                    if (summary.length() > 48) {
                        summary = summary.substring(0, 45) + "...";
                    }
                    table.addRow(
                            colorHttpMethod(ep.method()),
                            ep.path(),
                            ep.operationId() != null && !ep.operationId().isBlank() ? ep.operationId() : "-",
                            tagStr,
                            summary,
                            secStr
                    );
                }

                System.out.println(table.render());
                System.out.println(AnsiColors.bold("Total Matching Operations: " + endpoints.size()));
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error listing OpenAPI endpoints: " + e.getMessage()));
                return 1;
            }
        }
    }

    // =========================================================================
    // 4. oc openapi schema
    // =========================================================================
    @Command(name = "schema", aliases = {"schemas"}, mixinStandardHelpOptions = true, description = "Inspect a specific component schema (e.g., JobDTO, ConnectorDTO) or list all schemas")
    public static class OpenApiSchemaCommand implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Optional schema name (e.g. JobDTO)")
        private String positionalName;

        @Option(names = {"--name", "-n"}, description = "Name of the component schema to inspect")
        private String schemaNameOpt;

        @Option(names = {"--list", "-l"}, description = "List all available component schemas")
        private boolean listAll;

        @Option(names = {"--file", "-f"}, description = "Read from a local OpenAPI JSON/YAML file instead of a live server")
        private File file;

        @Option(names = {"--format"}, description = "Output format: table, json", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            String targetName = schemaNameOpt != null && !schemaNameOpt.isBlank() ? schemaNameOpt : positionalName;
            try {
                JsonNode spec = loadSpec(file, serverUrl, apiKey);
                JsonNode schemasNode = spec.path("components").path("schemas");

                if (listAll || targetName == null || targetName.isBlank()) {
                    List<String> names = DefaultOpenApiClient.extractComponentSchemaNames(spec);
                    if ("json".equalsIgnoreCase(format)) {
                        System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(names));
                        return 0;
                    }
                    if (names.isEmpty()) {
                        System.out.println(AnsiColors.yellow("No component schemas found in specification."));
                        return 0;
                    }
                    TableFormatter table = new TableFormatter()
                            .setHeaders("SCHEMA NAME", "TYPE", "PROPERTIES", "DESCRIPTION");
                    for (String name : names) {
                        JsonNode s = schemasNode.path(name);
                        String type = resolveSchemaType(s);
                        int propCount = s.path("properties").isObject() ? s.path("properties").size() : 0;
                        String desc = s.path("description").asText("-");
                        if (desc.length() > 60) {
                            desc = desc.substring(0, 57) + "...";
                        }
                        table.addRow(name, type, String.valueOf(propCount), desc);
                    }
                    System.out.println(table.render());
                    System.out.println(AnsiColors.bold("Total Component Schemas: " + names.size()));
                    return 0;
                }

                Optional<JsonNode> schemaOpt = DefaultOpenApiClient.findComponentSchema(spec, targetName);
                if (schemaOpt.isEmpty()) {
                    System.err.println(AnsiColors.red("Component schema '" + targetName + "' not found."));
                    return 1;
                }

                JsonNode schema = schemaOpt.get();
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
                    return 0;
                }

                System.out.println(AnsiColors.bold("\n--- Schema: " + targetName + " ---"));
                String desc = schema.path("description").asText("");
                if (!desc.isBlank()) {
                    System.out.println(AnsiColors.cyan(desc));
                }

                Set<String> requiredSet = new HashSet<>();
                JsonNode reqNode = schema.path("required");
                if (reqNode.isArray()) {
                    reqNode.forEach(r -> requiredSet.add(r.asText()));
                }

                JsonNode propsNode = schema.path("properties");
                if (!propsNode.isObject() || propsNode.isEmpty()) {
                    System.out.println(AnsiColors.yellow("No object properties defined on schema " + targetName));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("PROPERTY", "TYPE", "REQUIRED", "DESCRIPTION", "EXAMPLE");

                Iterator<Map.Entry<String, JsonNode>> fields = propsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> prop = fields.next();
                    String propName = prop.getKey();
                    JsonNode propSchema = prop.getValue();
                    String propType = resolveSchemaType(propSchema);
                    String reqLabel = requiredSet.contains(propName) ? AnsiColors.green("YES") : "no";
                    String propDesc = propSchema.path("description").asText("-");
                    if (propDesc.length() > 50) {
                        propDesc = propDesc.substring(0, 47) + "...";
                    }
                    String example = propSchema.has("example") ? propSchema.get("example").asText("-") : "-";
                    table.addRow(propName, propType, reqLabel, propDesc, example);
                }

                System.out.println(table.render());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error inspecting OpenAPI schema: " + e.getMessage()));
                return 1;
            }
        }

        static String resolveSchemaType(JsonNode propSchema) {
            if (propSchema == null || !propSchema.isObject()) return "any";
            if (propSchema.has("$ref")) {
                String ref = propSchema.get("$ref").asText();
                return ref.substring(ref.lastIndexOf('/') + 1);
            }
            JsonNode typeNode = propSchema.get("type");
            String baseType = "object";
            if (typeNode != null) {
                if (typeNode.isArray()) {
                    List<String> types = new ArrayList<>();
                    typeNode.forEach(t -> types.add(t.asText()));
                    baseType = String.join("|", types);
                } else {
                    baseType = typeNode.asText("object");
                }
            }
            if ("array".equals(baseType) && propSchema.has("items")) {
                return "array<" + resolveSchemaType(propSchema.get("items")) + ">";
            }
            if (propSchema.has("format")) {
                return baseType + " (" + propSchema.get("format").asText() + ")";
            }
            return baseType;
        }
    }

    // =========================================================================
    // 5. oc openapi validate
    // =========================================================================
    @Command(name = "validate", mixinStandardHelpOptions = true, description = "Validate an OpenAPI 3.1 specification (live or local file) for completeness and governance rules")
    public static class OpenApiValidateCommand implements Callable<Integer> {

        @Option(names = {"--file", "-f"}, description = "Validate a local JSON/YAML OpenAPI file (validates live /v3/api-docs if omitted)")
        private File file;

        @Option(names = {"--strict"}, description = "Fail with exit code 1 if any governance warnings (missing operationId, tags, or schema descriptions) are found")
        private boolean strict;

        @Option(names = {"--format"}, description = "Output format: text, json", defaultValue = "text")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try {
                JsonNode spec = loadSpec(file, serverUrl, apiKey);
                List<String> errors = new ArrayList<>();
                List<String> warnings = new ArrayList<>();

                String openApiVer = spec.path("openapi").asText("");
                if (openApiVer.isBlank()) {
                    errors.add("Missing required root field 'openapi' (expected 3.1.x)");
                } else if (!openApiVer.startsWith("3.1") && !openApiVer.startsWith("3.0")) {
                    errors.add("Unsupported OpenAPI version '" + openApiVer + "' (expected 3.1.x)");
                } else if (!openApiVer.startsWith("3.1")) {
                    warnings.add("OpenAPI version is '" + openApiVer + "' (OpenAPI 3.1.x is recommended)");
                }

                JsonNode info = spec.path("info");
                if (!info.isObject()) {
                    errors.add("Missing required 'info' object");
                } else {
                    if (info.path("title").asText("").isBlank()) {
                        errors.add("info.title must not be blank");
                    }
                    if (info.path("version").asText("").isBlank()) {
                        errors.add("info.version must not be blank");
                    }
                    if (info.path("license").path("name").asText("").isBlank()) {
                        warnings.add("info.license.name is not specified");
                    }
                }

                JsonNode paths = spec.path("paths");
                int operationCount = 0;
                if (!paths.isObject() || paths.isEmpty()) {
                    errors.add("Specification must define at least one path in 'paths'");
                } else {
                    Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
                    while (pathIter.hasNext()) {
                        Map.Entry<String, JsonNode> pathEntry = pathIter.next();
                        String path = pathEntry.getKey();
                        JsonNode pathItem = pathEntry.getValue();
                        if (!pathItem.isObject()) continue;

                        for (String method : HTTP_METHODS) {
                            JsonNode op = pathItem.get(method);
                            if (op == null || !op.isObject()) continue;
                            operationCount++;
                            String opLabel = method.toUpperCase(Locale.ROOT) + " " + path;

                            if (op.path("summary").asText("").isBlank()) {
                                errors.add(opLabel + " is missing a non-blank 'summary'");
                            }
                            if (op.path("operationId").asText("").isBlank()) {
                                warnings.add(opLabel + " is missing 'operationId'");
                            }
                            if (!op.path("tags").isArray() || op.path("tags").isEmpty()) {
                                warnings.add(opLabel + " has no domain 'tags' assigned");
                            }
                            if (!op.path("responses").isObject() || op.path("responses").isEmpty()) {
                                errors.add(opLabel + " must define at least one HTTP response in 'responses'");
                            }
                        }
                    }

                    if (paths.has("/api/v1/ingest/ois/{jobId}")) {
                        JsonNode secSchemes = spec.path("components").path("securitySchemes");
                        if (!secSchemes.has("OisTokenAuth")) {
                            errors.add("Path '/api/v1/ingest/ois/{jobId}' is present but 'components.securitySchemes.OisTokenAuth' is missing");
                        }
                    }
                }

                int schemaCount = 0;
                JsonNode schemas = spec.path("components").path("schemas");
                if (schemas.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> schemaIter = schemas.fields();
                    while (schemaIter.hasNext()) {
                        Map.Entry<String, JsonNode> entry = schemaIter.next();
                        schemaCount++;
                        if (entry.getValue().path("description").asText("").isBlank()) {
                            warnings.add("Component schema '" + entry.getKey() + "' is missing a 'description'");
                        }
                    }
                }

                boolean valid = errors.isEmpty() && (!strict || warnings.isEmpty());

                if ("json".equalsIgnoreCase(format)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("valid", valid);
                    result.put("openApiVersion", openApiVer.isBlank() ? "unknown" : openApiVer);
                    result.put("operations", operationCount);
                    result.put("schemas", schemaCount);
                    result.put("errors", errors);
                    result.put("warnings", warnings);
                    System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    return valid ? 0 : 1;
                }

                if (!warnings.isEmpty()) {
                    for (String w : warnings) {
                        System.out.println(AnsiColors.yellow("  [WARN]  " + w));
                    }
                }
                if (!errors.isEmpty()) {
                    for (String err : errors) {
                        System.out.println(AnsiColors.red("  [ERROR] " + err));
                    }
                }

                if (valid) {
                    System.out.println(AnsiColors.green("✔ OpenAPI " + (openApiVer.isBlank() ? "3.1.0" : openApiVer)
                            + " Specification is valid! (" + errors.size() + " errors, " + warnings.size()
                            + " warnings across " + operationCount + " operations and " + schemaCount + " schemas)"));
                    return 0;
                } else {
                    System.out.println(AnsiColors.red("✖ OpenAPI Specification validation failed ("
                            + errors.size() + " errors, " + warnings.size() + " warnings"
                            + (strict ? ", --strict mode enabled" : "") + ")"));
                    return 1;
                }
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error validating OpenAPI specification: " + e.getMessage()));
                return 1;
            }
        }
    }

    // =========================================================================
    // 6. oc openapi diff
    // =========================================================================
    @Command(name = "diff", mixinStandardHelpOptions = true, description = "Compare two OpenAPI specifications (baseline vs. target) for breaking and non-breaking changes")
    public static class OpenApiDiffCommand implements Callable<Integer> {

        @Option(names = {"--base", "-b"}, required = true, description = "Baseline OpenAPI specification (file path or URL)")
        private String baseSource;

        @Option(names = {"--target", "-t"}, description = "Target OpenAPI specification (file path or URL; defaults to live /v3/api-docs)")
        private String targetSource;

        @Option(names = {"--fail-on-breaking"}, description = "Exit with code 1 if breaking API changes are detected")
        private boolean failOnBreaking;

        @Option(names = {"--format"}, description = "Output format: table, json, markdown", defaultValue = "table")
        private String format;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        public record DiffChange(String severity, String changeType, String category, String item, String details) {}

        @Override
        public Integer call() {
            try {
                JsonNode baseSpec = loadSpecFromPathOrUrl(baseSource, serverUrl, apiKey);
                JsonNode targetSpec = loadSpecFromPathOrUrl(targetSource, serverUrl, apiKey);

                List<DiffChange> changes = computeDiff(baseSpec, targetSpec);
                long breakingCount = changes.stream().filter(c -> "BREAKING".equals(c.severity())).count();
                long nonBreakingCount = changes.size() - breakingCount;

                if ("json".equalsIgnoreCase(format)) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("breakingChanges", breakingCount);
                    out.put("nonBreakingChanges", nonBreakingCount);
                    out.put("changes", changes);
                    System.out.println(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
                    return (failOnBreaking && breakingCount > 0) ? 1 : 0;
                }

                if ("markdown".equalsIgnoreCase(format)) {
                    System.out.println("### OpenAPI Contract Diff Summary");
                    System.out.println("- **Breaking Changes**: " + breakingCount);
                    System.out.println("- **Non-Breaking Changes**: " + nonBreakingCount + "\n");
                    if (!changes.isEmpty()) {
                        System.out.println("| Severity | Type | Category | Item | Details |");
                        System.out.println("|---|---|---|---|---|");
                        for (DiffChange c : changes) {
                            System.out.printf("| %s | %s | %s | `%s` | %s |%n",
                                     c.severity(), c.changeType(), c.category(), c.item(), c.details());
                        }
                    }
                    return (failOnBreaking && breakingCount > 0) ? 1 : 0;
                }

                if (changes.isEmpty()) {
                    System.out.println(AnsiColors.green("✔ No contract differences detected between baseline and target specifications."));
                    return 0;
                }

                TableFormatter table = new TableFormatter()
                        .setHeaders("SEVERITY", "CHANGE", "CATEGORY", "ITEM", "DETAILS");
                for (DiffChange c : changes) {
                    String sev = "BREAKING".equals(c.severity())
                            ? AnsiColors.red("BREAKING")
                            : AnsiColors.green("NON-BREAKING");
                    table.addRow(sev, c.changeType(), c.category(), c.item(), c.details());
                }
                System.out.println(table.render());
                System.out.printf("Summary: %s breaking, %s non-breaking changes.%n",
                        breakingCount > 0 ? AnsiColors.red(String.valueOf(breakingCount)) : "0",
                        AnsiColors.cyan(String.valueOf(nonBreakingCount)));

                if (failOnBreaking && breakingCount > 0) {
                    return 1;
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error comparing OpenAPI specifications: " + e.getMessage()));
                return 1;
            }
        }

        static List<DiffChange> computeDiff(JsonNode baseSpec, JsonNode targetSpec) {
            List<DiffChange> diffs = new ArrayList<>();

            Map<String, OpenApiEndpointSummary> baseOps = indexEndpoints(DefaultOpenApiClient.extractEndpoints(baseSpec, null, null));
            Map<String, OpenApiEndpointSummary> targetOps = indexEndpoints(DefaultOpenApiClient.extractEndpoints(targetSpec, null, null));

            for (Map.Entry<String, OpenApiEndpointSummary> baseEntry : baseOps.entrySet()) {
                if (!targetOps.containsKey(baseEntry.getKey())) {
                    diffs.add(new DiffChange("BREAKING", "REMOVED", "ENDPOINT", baseEntry.getKey(),
                            "Endpoint removed (" + baseEntry.getValue().summary() + ")"));
                }
            }
            for (Map.Entry<String, OpenApiEndpointSummary> targetEntry : targetOps.entrySet()) {
                if (!baseOps.containsKey(targetEntry.getKey())) {
                    diffs.add(new DiffChange("NON-BREAKING", "ADDED", "ENDPOINT", targetEntry.getKey(),
                            "New endpoint added (" + targetEntry.getValue().summary() + ")"));
                }
            }

            JsonNode baseSchemas = baseSpec.path("components").path("schemas");
            JsonNode targetSchemas = targetSpec.path("components").path("schemas");

            Set<String> baseSchemaNames = new LinkedHashSet<>(DefaultOpenApiClient.extractComponentSchemaNames(baseSpec));
            Set<String> targetSchemaNames = new LinkedHashSet<>(DefaultOpenApiClient.extractComponentSchemaNames(targetSpec));

            for (String sName : baseSchemaNames) {
                if (!targetSchemaNames.contains(sName)) {
                    diffs.add(new DiffChange("BREAKING", "REMOVED", "SCHEMA", sName, "Component schema removed"));
                } else {
                    JsonNode bSchema = baseSchemas.path(sName);
                    JsonNode tSchema = targetSchemas.path(sName);
                    compareSchemaProperties(sName, bSchema, tSchema, diffs);
                }
            }
            for (String sName : targetSchemaNames) {
                if (!baseSchemaNames.contains(sName)) {
                    diffs.add(new DiffChange("NON-BREAKING", "ADDED", "SCHEMA", sName, "New component schema added"));
                }
            }

            return diffs;
        }

        private static void compareSchemaProperties(String schemaName, JsonNode baseSchema, JsonNode targetSchema, List<DiffChange> diffs) {
            Set<String> baseReq = extractSet(baseSchema.path("required"));
            Set<String> targetReq = extractSet(targetSchema.path("required"));

            for (String reqProp : targetReq) {
                if (!baseReq.contains(reqProp)) {
                    diffs.add(new DiffChange("BREAKING", "MODIFIED", "SCHEMA_REQUIRED",
                            schemaName + "." + reqProp, "Property became required in target specification"));
                }
            }

            JsonNode baseProps = baseSchema.path("properties");
            JsonNode targetProps = targetSchema.path("properties");
            if (baseProps.isObject() && targetProps.isObject()) {
                Iterator<String> bFields = baseProps.fieldNames();
                while (bFields.hasNext()) {
                    String field = bFields.next();
                    if (!targetProps.has(field)) {
                        diffs.add(new DiffChange("BREAKING", "REMOVED", "SCHEMA_PROPERTY",
                                schemaName + "." + field, "Schema property removed"));
                    }
                }
                Iterator<String> tFields = targetProps.fieldNames();
                while (tFields.hasNext()) {
                    String field = tFields.next();
                    if (!baseProps.has(field) && !targetReq.contains(field)) {
                        diffs.add(new DiffChange("NON-BREAKING", "ADDED", "SCHEMA_PROPERTY",
                                schemaName + "." + field, "Optional schema property added"));
                    }
                }
            }
        }

        private static Set<String> extractSet(JsonNode arr) {
            Set<String> set = new HashSet<>();
            if (arr != null && arr.isArray()) {
                arr.forEach(n -> set.add(n.asText()));
            }
            return set;
        }

        private static Map<String, OpenApiEndpointSummary> indexEndpoints(List<OpenApiEndpointSummary> list) {
            Map<String, OpenApiEndpointSummary> map = new LinkedHashMap<>();
            for (OpenApiEndpointSummary ep : list) {
                map.put(ep.method() + " " + ep.path(), ep);
            }
            return map;
        }
    }

    // =========================================================================
    // 7. oc openapi tools
    // =========================================================================
    @Command(name = "tools", mixinStandardHelpOptions = true, description = "Export OpenAPI operations as LLM function-calling tool definitions (OpenAI, Gemini, Anthropic, MCP)")
    public static class OpenApiToolsCommand implements Callable<Integer> {

        @Option(names = {"--provider", "-p"}, description = "Target AI tool format: openai, gemini, anthropic, mcp", defaultValue = "openai")
        private String provider;

        @Option(names = {"--tag", "-t"}, description = "Filter exported tools by OpenAPI domain tag")
        private String tag;

        @Option(names = {"--file", "-f"}, description = "Read from a local OpenAPI JSON/YAML file instead of a live server")
        private File file;

        @Option(names = {"--output", "-o"}, description = "Write tool declarations JSON to file (prints to stdout if omitted)")
        private Path output;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Option(names = {"--api-key"}, description = "Override API key")
        private String apiKey;

        @Override
        public Integer call() {
            try {
                JsonNode spec = loadSpec(file, serverUrl, apiKey);
                ArrayNode toolsArray = buildToolsArray(spec, provider, tag);
                String json = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toolsArray);

                if (output != null) {
                    if (output.getParent() != null) {
                        Files.createDirectories(output.getParent());
                    }
                    Files.writeString(output, json);
                    System.out.println(AnsiColors.green("✔ Exported " + toolsArray.size() + " AI tool declarations ("
                            + provider.toLowerCase(Locale.ROOT) + " format) to " + output));
                } else {
                    System.out.println(json);
                }
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error exporting OpenAPI AI tools: " + e.getMessage()));
                return 1;
            }
        }

        static ArrayNode buildToolsArray(JsonNode spec, String providerName, String tagFilter) {
            ArrayNode tools = JSON_MAPPER.createArrayNode();
            JsonNode paths = spec.path("paths");
            if (!paths.isObject()) {
                return tools;
            }

            String normalizedTag = tagFilter != null && !tagFilter.isBlank() ? tagFilter.trim().toLowerCase(Locale.ROOT) : null;
            String mode = providerName != null ? providerName.trim().toLowerCase(Locale.ROOT) : "openai";

            Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
            while (pathIter.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathIter.next();
                String path = pathEntry.getKey();
                JsonNode pathItem = pathEntry.getValue();
                if (!pathItem.isObject()) continue;

                for (String method : HTTP_METHODS) {
                    JsonNode op = pathItem.get(method);
                    if (op == null || !op.isObject()) continue;

                    if (normalizedTag != null) {
                        boolean match = false;
                        JsonNode tagsArr = op.path("tags");
                        if (tagsArr.isArray()) {
                            for (JsonNode t : tagsArr) {
                                if (t.asText("").toLowerCase(Locale.ROOT).contains(normalizedTag)) {
                                    match = true;
                                    break;
                                }
                            }
                        }
                        if (!match) continue;
                    }

                    String opId = op.path("operationId").asText("");
                    if (opId.isBlank()) {
                        opId = (method + "_" + path.replaceAll("[^a-zA-Z0-9]+", "_")).replaceAll("^_|_$", "");
                    }
                    String summary = op.path("summary").asText("");
                    String description = op.path("description").asText("");
                    String fullDesc = (!summary.isBlank() ? summary : opId)
                            + " [" + method.toUpperCase(Locale.ROOT) + " " + path + "]"
                            + (!description.isBlank() ? " - " + description : "");

                    ObjectNode paramSchema = buildParameterSchema(spec, op, "gemini".equals(mode));

                    switch (mode) {
                        case "anthropic" -> {
                            ObjectNode tool = JSON_MAPPER.createObjectNode();
                            tool.put("name", opId);
                            tool.put("description", fullDesc);
                            tool.set("input_schema", paramSchema);
                            tools.add(tool);
                        }
                        case "mcp" -> {
                            ObjectNode tool = JSON_MAPPER.createObjectNode();
                            tool.put("name", opId);
                            tool.put("description", fullDesc);
                            tool.set("inputSchema", paramSchema);
                            tools.add(tool);
                        }
                        case "gemini" -> {
                            ObjectNode tool = JSON_MAPPER.createObjectNode();
                            tool.put("name", opId);
                            tool.put("description", fullDesc);
                            tool.set("parameters", paramSchema);
                            tools.add(tool);
                        }
                        default -> {
                            ObjectNode fn = JSON_MAPPER.createObjectNode();
                            fn.put("name", opId);
                            fn.put("description", fullDesc);
                            fn.set("parameters", paramSchema);

                            ObjectNode wrapper = JSON_MAPPER.createObjectNode();
                            wrapper.put("type", "function");
                            wrapper.set("function", fn);
                            tools.add(wrapper);
                        }
                    }
                }
            }
            return tools;
        }

        private static ObjectNode buildParameterSchema(JsonNode spec, JsonNode op, boolean uppercaseTypes) {
            ObjectNode schema = JSON_MAPPER.createObjectNode();
            schema.put("type", uppercaseTypes ? "OBJECT" : "object");
            ObjectNode properties = JSON_MAPPER.createObjectNode();
            ArrayNode required = JSON_MAPPER.createArrayNode();
            Set<String> requiredNames = new LinkedHashSet<>();

            JsonNode params = op.path("parameters");
            if (params.isArray()) {
                for (JsonNode p : params) {
                    String pName = p.path("name").asText("");
                    if (pName.isBlank()) continue;
                    ObjectNode propNode = JSON_MAPPER.createObjectNode();
                    String pType = p.path("schema").path("type").asText("string");
                    propNode.put("type", uppercaseTypes ? pType.toUpperCase(Locale.ROOT) : pType);
                    String pDesc = p.path("description").asText("");
                    if (!pDesc.isBlank()) {
                        propNode.put("description", pDesc);
                    }
                    properties.set(pName, propNode);
                    if (p.path("required").asBoolean(false)) {
                        requiredNames.add(pName);
                    }
                }
            }

            JsonNode bodySchema = op.path("requestBody").path("content").path("application/json").path("schema");
            if (bodySchema.isObject() && !bodySchema.isEmpty()) {
                JsonNode resolved = resolveRef(spec, bodySchema);
                JsonNode bodyProps = resolved.path("properties");
                if (bodyProps.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = bodyProps.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        ObjectNode propNode = JSON_MAPPER.createObjectNode();
                        String pType = entry.getValue().path("type").asText("string");
                        if ("array".equalsIgnoreCase(pType)) {
                            propNode.put("type", uppercaseTypes ? "ARRAY" : "array");
                            ObjectNode items = JSON_MAPPER.createObjectNode();
                            String itemType = entry.getValue().path("items").path("type").asText("string");
                            items.put("type", uppercaseTypes ? itemType.toUpperCase(Locale.ROOT) : itemType);
                            propNode.set("items", items);
                        } else {
                            propNode.put("type", uppercaseTypes ? pType.toUpperCase(Locale.ROOT) : pType);
                        }
                        String desc = entry.getValue().path("description").asText("");
                        if (!desc.isBlank()) {
                            propNode.put("description", desc);
                        }
                        properties.set(entry.getKey(), propNode);
                    }
                }
                JsonNode bodyReq = resolved.path("required");
                if (bodyReq.isArray()) {
                    bodyReq.forEach(r -> requiredNames.add(r.asText()));
                }
            }

            schema.set("properties", properties);
            for (String req : requiredNames) {
                required.add(req);
            }
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
            return schema;
        }

        private static JsonNode resolveRef(JsonNode spec, JsonNode schemaNode) {
            if (schemaNode.has("$ref")) {
                String ref = schemaNode.get("$ref").asText("");
                String prefix = "#/components/schemas/";
                if (ref.startsWith(prefix)) {
                    String name = ref.substring(prefix.length());
                    return spec.path("components").path("schemas").path(name);
                }
            }
            return schemaNode;
        }
    }

    // =========================================================================
    // 8. oc openapi ui
    // =========================================================================
    @Command(name = "ui", mixinStandardHelpOptions = true, description = "Print or open the interactive Swagger UI URL in the default system browser")
    public static class OpenApiUiCommand implements Callable<Integer> {

        @Option(names = {"--open"}, description = "Launch the Swagger UI URL in the default system browser")
        private boolean openBrowser;

        @Option(names = {"--url"}, description = "Override OpenCrawling server URL")
        private String serverUrl;

        @Override
        public Integer call() {
            CliConfig config = CliConfigService.loadConfig();
            String baseUrl = (serverUrl != null && !serverUrl.isBlank()) ? serverUrl : config.serverUrl();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String swaggerUrl = baseUrl + "/swagger-ui.html";
            String apiDocsUrl = baseUrl + "/v3/api-docs";

            System.out.println(AnsiColors.bold("Swagger UI Explorer : ") + AnsiColors.cyan(swaggerUrl));
            System.out.println(AnsiColors.bold("OpenAPI 3.1 JSON    : ") + apiDocsUrl);

            if (openBrowser) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create(swaggerUrl));
                        System.out.println(AnsiColors.green("✔ Opened Swagger UI in default system browser."));
                    } else {
                        System.out.println(AnsiColors.yellow("Desktop browser launch not supported in this headless environment; open the URL above manually."));
                    }
                } catch (Exception e) {
                    System.out.println(AnsiColors.yellow("Could not launch system browser automatically: " + e.getMessage()));
                }
            }
            return 0;
        }
    }
}
