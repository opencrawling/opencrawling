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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Summary of an OpenCrawling runtime's OpenAPI 3.1 contract and Swagger UI availability.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenApiStatusSummary(
        String title,
        String apiVersion,
        String openApiVersion,
        String licenseName,
        String licenseUrl,
        String specEndpointUrl,
        boolean specReachable,
        long specLatencyMs,
        String swaggerUiUrl,
        boolean swaggerUiReachable,
        int pathCount,
        int operationCount,
        int schemaCount,
        List<String> securitySchemes,
        List<String> tags
) {
}
