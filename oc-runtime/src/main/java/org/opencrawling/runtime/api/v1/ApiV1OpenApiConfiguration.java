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
package org.opencrawling.runtime.api.v1;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The Platform API's own OpenAPI document ({@code /v3/api-docs/v1}): its paths only, its title, the bearer scheme, the
 * problem detail schema, and the error responses every operation can give.
 */
@Configuration
@ConditionalOnClass(GroupedOpenApi.class)
public class ApiV1OpenApiConfiguration {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String BEARER = "BearerAuth";

    @Bean
    public GroupedOpenApi platformApiV1() {
        return GroupedOpenApi.builder()
                .group(ApiV1.OPENAPI_GROUP)
                .displayName("Platform API v1")
                .pathsToMatch(ApiV1.BASE, ApiV1.BASE + "/**")
                .pathsToExclude(ApiV1.BASE + "/ingest/**", ApiV1.BASE + "/admin/**")
                .addOpenApiCustomizer(ApiV1OpenApiConfiguration::customize)
                .build();
    }

    /** Everything, as before the groups: keeps {@code /v3/api-docs} complete for existing clients. */
    @Bean
    public GroupedOpenApi allEndpoints() {
        return GroupedOpenApi.builder().group("all").displayName("All endpoints").pathsToMatch("/**").build();
    }

    static void customize(OpenAPI openApi) {
        openApi.info(new Info()
                .title("OpenCrawling Platform API")
                .version(ApiV1.VERSION)
                .description("Every OpenCrawling operation over REST, without the admin UI. Errors are RFC 9457 problem details; "
                        + "collections use cursor paging; single resources have ETags (If-None-Match, If-Match); custom methods are "
                        + "POST .../{id}:action; POST requests accept an Idempotency-Key. See the Platform API page of the wiki.")
                .license(new License().name("Apache License 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
        Components components = openApi.getComponents() != null ? openApi.getComponents() : new Components();
        components.addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .bearerFormat("JWT").description("An access token from the identity provider, when opencrawling.security.identity.protect-api is on"));
        components.addSchemas(PROBLEM_SCHEMA, problemSchema());
        openApi.components(components);
        openApi.addSecurityItem(new SecurityRequirement().addList(BEARER));
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                addErrors(operation, method, path.contains(":") || path.contains("{"))));
    }

    private static void addErrors(Operation operation, PathItem.HttpMethod method, boolean addressesOne) {
        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
        add(responses, "401", "No valid bearer token (when the API requires one)");
        add(responses, "403", "The caller may not do this");
        add(responses, "500", "Unexpected error; the request id identifies it in the log");
        if (addressesOne) {
            add(responses, "404", "No such resource, or not visible to the caller");
        }
        if (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT || method == PathItem.HttpMethod.PATCH
                || method == PathItem.HttpMethod.GET) {
            add(responses, "400", "Invalid request: see detail and errors");
        }
        if (method == PathItem.HttpMethod.PUT || method == PathItem.HttpMethod.PATCH || method == PathItem.HttpMethod.DELETE) {
            add(responses, "412", "If-Match does not match the current ETag");
        }
        if (method == PathItem.HttpMethod.POST) {
            add(responses, "409", "Conflict with the resource's state, or an Idempotency-Key still in progress");
            add(responses, "422", "The Idempotency-Key was used for a different request");
        }
        operation.setResponses(responses);
    }

    private static void add(ApiResponses responses, String code, String description) {
        ApiResponse existing = responses.get(code);
        Content problem = new Content().addMediaType(ProblemResponses.PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)));
        if (existing == null) {
            responses.addApiResponse(code, new ApiResponse().description(description).content(problem));
        } else if (existing.getContent() == null || existing.getContent().isEmpty()
                || existing.getContent().containsKey(ProblemResponses.PROBLEM_JSON)) {
            existing.setContent(problem);
        }
    }

    @SuppressWarnings("rawtypes")
    private static Schema problemSchema() {
        ObjectSchema fieldError = new ObjectSchema();
        fieldError.addProperty("field", new StringSchema().description("The invalid field or parameter"));
        fieldError.addProperty("message", new StringSchema().description("What is wrong with it"));
        ObjectSchema problem = new ObjectSchema();
        problem.description("An RFC 9457 problem detail");
        problem.properties(new java.util.LinkedHashMap<>(Map.of()));
        problem.addProperty("type", new StringSchema().description("URI identifying the problem type").example(ApiV1.PROBLEM_TYPE_BASE + "not-found"));
        problem.addProperty("title", new StringSchema().description("Short summary of the problem type").example("Not found"));
        problem.addProperty("status", new IntegerSchema().description("HTTP status").example(404));
        problem.addProperty("detail", new StringSchema().description("What happened, for a human"));
        problem.addProperty("instance", new StringSchema().description("The request path"));
        problem.addProperty("requestId", new StringSchema().description("Same as the X-Request-Id response header"));
        problem.addProperty("errors", new ArraySchema().items(fieldError).description("Field errors, for validation failures"));
        problem.required(java.util.List.of("type", "title", "status"));
        return problem;
    }
}
