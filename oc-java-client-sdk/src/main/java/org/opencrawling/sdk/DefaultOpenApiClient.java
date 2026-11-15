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
package org.opencrawling.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.OpenApiEndpointSummary;
import org.opencrawling.sdk.models.OpenApiStatusSummary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of {@link OpenApiClient} utilizing {@link HttpTransport}.
 */
public class DefaultOpenApiClient implements OpenApiClient {

    private static final List<String> HTTP_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "options", "head", "trace"
    );

    private final HttpTransport transport;

    public DefaultOpenApiClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public JsonNode fetchSpecification() {
        return transport.execute("GET", "/v3/api-docs", null, JsonNode.class);
    }

    @Override
    public String fetchRawSpecification() {
        byte[] bytes = transport.executeBytes("GET", "/v3/api-docs");
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
    }

    @Override
    public OpenApiStatusSummary checkStatus() {
        String baseUrl = transport.getBaseUrl();
        String specUrl = baseUrl + "/v3/api-docs";
        String swaggerUiUrl = baseUrl + "/swagger-ui.html";

        long startNs = System.nanoTime();
        JsonNode spec = fetchSpecification();
        long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

        boolean swaggerReachable = probeSwaggerUi();
        return extractStatusSummary(spec, specUrl, true, latencyMs, swaggerUiUrl, swaggerReachable);
    }

    private boolean probeSwaggerUi() {
        try {
            transport.executeBytes("GET", "/swagger-ui.html");
            return true;
        } catch (Exception first) {
            try {
                transport.executeBytes("GET", "/swagger-ui/index.html");
                return true;
            } catch (Exception second) {
                return false;
            }
        }
    }

    @Override
    public List<OpenApiEndpointSummary> listEndpoints(String tag, String method) {
        JsonNode spec = fetchSpecification();
        return extractEndpoints(spec, tag, method);
    }

    @Override
    public List<String> listComponentSchemas() {
        JsonNode spec = fetchSpecification();
        return extractComponentSchemaNames(spec);
    }

    @Override
    public Optional<JsonNode> getComponentSchema(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return Optional.empty();
        }
        JsonNode spec = fetchSpecification();
        return findComponentSchema(spec, schemaName);
    }

    public static OpenApiStatusSummary extractStatusSummary(
            JsonNode spec,
            String specEndpointUrl,
            boolean specReachable,
            long specLatencyMs,
            String swaggerUiUrl,
            boolean swaggerUiReachable) {
        if (spec == null || !spec.isObject()) {
            return new OpenApiStatusSummary(
                    "Unknown", "Unknown", "Unknown", null, null,
                    specEndpointUrl, false, specLatencyMs, swaggerUiUrl, swaggerUiReachable,
                    0, 0, 0, List.of(), List.of()
            );
        }

        String openApiVersion = spec.path("openapi").asText("3.1.0");
        JsonNode info = spec.path("info");
        String title = info.path("title").asText("OpenCrawling Runtime REST API");
        String apiVersion = info.path("version").asText("1.0.0");
        String licenseName = info.path("license").path("name").asText(null);
        String licenseUrl = info.path("license").path("url").asText(null);

        JsonNode pathsNode = spec.path("paths");
        int pathCount = pathsNode.isObject() ? pathsNode.size() : 0;
        List<OpenApiEndpointSummary> endpoints = extractEndpoints(spec, null, null);
        int operationCount = endpoints.size();

        JsonNode schemasNode = spec.path("components").path("schemas");
        int schemaCount = schemasNode.isObject() ? schemasNode.size() : 0;

        List<String> securitySchemes = new ArrayList<>();
        JsonNode secSchemesNode = spec.path("components").path("securitySchemes");
        if (secSchemesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = secSchemesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                String type = entry.getValue().path("type").asText("");
                String scheme = entry.getValue().path("scheme").asText("");
                if (!scheme.isBlank()) {
                    securitySchemes.add(name + " (" + type.toUpperCase(Locale.ROOT) + " " + scheme + ")");
                } else if (!type.isBlank()) {
                    securitySchemes.add(name + " (" + type.toUpperCase(Locale.ROOT) + ")");
                } else {
                    securitySchemes.add(name);
                }
            }
        }

        Set<String> tagSet = new LinkedHashSet<>();
        JsonNode tagsNode = spec.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode t : tagsNode) {
                String tagName = t.path("name").asText("");
                if (!tagName.isBlank()) {
                    tagSet.add(tagName);
                }
            }
        }
        for (OpenApiEndpointSummary ep : endpoints) {
            if (ep.tags() != null) {
                tagSet.addAll(ep.tags());
            }
        }

        return new OpenApiStatusSummary(
                title,
                apiVersion,
                openApiVersion,
                licenseName,
                licenseUrl,
                specEndpointUrl,
                specReachable,
                specLatencyMs,
                swaggerUiUrl,
                swaggerUiReachable,
                pathCount,
                operationCount,
                schemaCount,
                List.copyOf(securitySchemes),
                List.copyOf(tagSet)
        );
    }

    public static List<OpenApiEndpointSummary> extractEndpoints(JsonNode spec, String tagFilter, String methodFilter) {
        List<OpenApiEndpointSummary> results = new ArrayList<>();
        if (spec == null || !spec.path("paths").isObject()) {
            return results;
        }

        String normalizedTag = tagFilter != null && !tagFilter.isBlank() ? tagFilter.trim().toLowerCase(Locale.ROOT) : null;
        String normalizedMethod = methodFilter != null && !methodFilter.isBlank() ? methodFilter.trim().toUpperCase(Locale.ROOT) : null;

        Iterator<Map.Entry<String, JsonNode>> pathFields = spec.path("paths").fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathFields.next();
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            if (!pathItem.isObject()) {
                continue;
            }

            for (String httpMethod : HTTP_METHODS) {
                JsonNode opNode = pathItem.get(httpMethod);
                if (opNode == null || !opNode.isObject()) {
                    continue;
                }

                String upperMethod = httpMethod.toUpperCase(Locale.ROOT);
                if (normalizedMethod != null && !upperMethod.equals(normalizedMethod)) {
                    continue;
                }

                List<String> tags = new ArrayList<>();
                JsonNode tagsArr = opNode.path("tags");
                if (tagsArr.isArray()) {
                    for (JsonNode t : tagsArr) {
                        if (t.isTextual() && !t.asText().isBlank()) {
                            tags.add(t.asText());
                        }
                    }
                }

                if (normalizedTag != null) {
                    boolean matchesTag = tags.stream()
                            .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(normalizedTag));
                    if (!matchesTag) {
                        continue;
                    }
                }

                String operationId = opNode.path("operationId").asText("");
                String summary = opNode.path("summary").asText("");
                String description = opNode.path("description").asText("");

                List<String> security = new ArrayList<>();
                JsonNode secArr = opNode.path("security");
                if (!secArr.isArray()) {
                    secArr = spec.path("security");
                }
                if (secArr.isArray()) {
                    for (JsonNode secItem : secArr) {
                        if (secItem.isObject()) {
                            secItem.fieldNames().forEachRemaining(security::add);
                        }
                    }
                }

                List<String> responseCodes = new ArrayList<>();
                JsonNode responsesNode = opNode.path("responses");
                if (responsesNode.isObject()) {
                    responsesNode.fieldNames().forEachRemaining(responseCodes::add);
                }

                results.add(new OpenApiEndpointSummary(
                        upperMethod,
                        path,
                        operationId,
                        summary,
                        description,
                        List.copyOf(tags),
                        List.copyOf(security),
                        List.copyOf(responseCodes)
                ));
            }
        }
        return results;
    }

    public static List<String> extractComponentSchemaNames(JsonNode spec) {
        List<String> names = new ArrayList<>();
        if (spec == null) {
            return names;
        }
        JsonNode schemas = spec.path("components").path("schemas");
        if (schemas.isObject()) {
            schemas.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareToIgnoreCase);
        }
        return names;
    }

    public static Optional<JsonNode> findComponentSchema(JsonNode spec, String schemaName) {
        if (spec == null || schemaName == null || schemaName.isBlank()) {
            return Optional.empty();
        }
        JsonNode schemas = spec.path("components").path("schemas");
        if (!schemas.isObject()) {
            return Optional.empty();
        }
        if (schemas.has(schemaName)) {
            return Optional.of(schemas.get(schemaName));
        }
        Iterator<Map.Entry<String, JsonNode>> fields = schemas.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(schemaName.trim())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}
