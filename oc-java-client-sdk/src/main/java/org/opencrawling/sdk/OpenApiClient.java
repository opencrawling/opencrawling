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
import org.opencrawling.sdk.models.OpenApiEndpointSummary;
import org.opencrawling.sdk.models.OpenApiStatusSummary;

import java.util.List;
import java.util.Optional;

/**
 * Client for fetching, introspecting, and probing the OpenCrawling Runtime OpenAPI 3.1 contract
 * ({@code /v3/api-docs}) and interactive Swagger UI ({@code /swagger-ui.html}).
 */
public interface OpenApiClient {

    /**
     * Fetches the live OpenAPI 3.1 contract from {@code /v3/api-docs} as a parsed Jackson {@link JsonNode}.
     */
    JsonNode fetchSpecification();

    /**
     * Fetches the raw OpenAPI 3.1 JSON string from {@code /v3/api-docs}.
     */
    String fetchRawSpecification();

    /**
     * Probes {@code /v3/api-docs} and {@code /swagger-ui.html} and returns contract metadata and reachability metrics.
     */
    OpenApiStatusSummary checkStatus();

    /**
     * Lists all documented REST API operations from the live OpenAPI 3.1 contract.
     */
    default List<OpenApiEndpointSummary> listEndpoints() {
        return listEndpoints(null, null);
    }

    /**
     * Lists documented REST API operations filtered by optional domain tag and HTTP method.
     *
     * @param tag    optional domain tag filter (case-insensitive substring or exact match)
     * @param method optional HTTP verb filter (e.g. GET, POST, PUT, DELETE)
     */
    List<OpenApiEndpointSummary> listEndpoints(String tag, String method);

    /**
     * Lists all schema names defined under {@code components.schemas}.
     */
    List<String> listComponentSchemas();

    /**
     * Retrieves a specific component schema definition from {@code components.schemas}.
     *
     * @param schemaName schema name (e.g. {@code JobDTO}, {@code ConnectorDTO})
     */
    Optional<JsonNode> getComponentSchema(String schemaName);
}
