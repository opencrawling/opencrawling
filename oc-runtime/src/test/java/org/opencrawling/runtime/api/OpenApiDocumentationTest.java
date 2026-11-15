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
package org.opencrawling.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.copilot.NarrativizationCopilotController;
import org.opencrawling.runtime.config.OpenApiConfig;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

class OpenApiDocumentationTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            JobController.class,
            ConnectorController.class,
            OisIngestController.class,
            ReportController.class,
            ObservabilityController.class,
            SystemController.class,
            TransportSettingsController.class,
            McfMigrationController.class,
            VespaInsightsController.class,
            NarrativizationCopilotController.class
    );

    @Test
    @DisplayName("OpenApiConfig creates OpenAPI metadata, security scheme, and all required tags")
    void testOpenApiConfigBean() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI openApi = config.openCrawlingOpenAPI();

        assertThat(openApi).isNotNull();
        assertThat(openApi.getInfo()).isNotNull();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("OpenCrawling Runtime REST API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openApi.getInfo().getLicense()).isNotNull();
        assertThat(openApi.getInfo().getLicense().getName()).isEqualTo("Apache License 2.0");

        // Verify Security Scheme for OIS Ingestion
        assertThat(openApi.getComponents()).isNotNull();
        assertThat(openApi.getComponents().getSecuritySchemes()).containsKey(OpenApiConfig.OIS_TOKEN_AUTH);
        SecurityScheme oisAuth = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.OIS_TOKEN_AUTH);
        assertThat(oisAuth.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(oisAuth.getScheme()).isEqualTo("bearer");

        // Verify logical tags
        List<String> tagNames = openApi.getTags().stream().map(io.swagger.v3.oas.models.tags.Tag::getName).toList();
        assertThat(tagNames).contains(
                "Pipeline & Jobs",
                "Connectors",
                "OIS Direct Ingestion",
                "Reports & Lakehouse Audit",
                "Observability & AIOps",
                "System & Settings",
                "Transport Settings",
                "ManifoldCF Migration",
                "Vespa Insights",
                "Auto-Narrativization Copilot"
        );
    }

    @Test
    @DisplayName("All REST controllers have @Tag annotation with non-empty name and description")
    void testAllControllersHaveTagAnnotation() {
        for (Class<?> controller : CONTROLLERS) {
            assertThat(controller.isAnnotationPresent(RestController.class))
                    .as("Controller %s must be annotated with @RestController", controller.getSimpleName())
                    .isTrue();

            Tag tag = controller.getAnnotation(Tag.class);
            assertThat(tag)
                    .as("Controller %s must have @Tag annotation", controller.getSimpleName())
                    .isNotNull();
            assertThat(tag.name())
                    .as("Controller %s @Tag.name must not be blank", controller.getSimpleName())
                    .isNotBlank();
            assertThat(tag.description())
                    .as("Controller %s @Tag.description must not be blank", controller.getSimpleName())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("All endpoint handler methods in controllers have @Operation and response annotations")
    void testAllEndpointsHaveOperationAnnotation() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                boolean isMapping = method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(RequestMapping.class);

                if (isMapping) {
                    Operation op = method.getAnnotation(Operation.class);
                    assertThat(op)
                            .as("Method %s.%s must have @Operation annotation", controller.getSimpleName(), method.getName())
                            .isNotNull();
                    assertThat(op.summary())
                            .as("Method %s.%s @Operation summary must not be blank", controller.getSimpleName(), method.getName())
                            .isNotBlank();

                    boolean hasApiResponse = method.isAnnotationPresent(ApiResponse.class)
                            || method.isAnnotationPresent(ApiResponses.class);
                    assertThat(hasApiResponse)
                            .as("Method %s.%s must have @ApiResponse or @ApiResponses annotation", controller.getSimpleName(), method.getName())
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Core API DTO records are annotated with @Schema")
    void testDtoRecordsHaveSchemaAnnotation() {
        List<Class<?>> dtos = List.of(
                JobController.JobDTO.class,
                JobController.NarrativizationConfig.class,
                ConnectorController.ConnectorDTO.class,
                SystemController.SystemSettingsDTO.class,
                ReportController.QueryRequest.class,
                VespaInsightsController.VespaQueryRequest.class,
                VespaInsightsController.DeployBundledRequest.class,
                McfMigrationController.MigrationRequest.class,
                McfMigrationController.MigrationResponse.class
        );

        for (Class<?> dto : dtos) {
            Schema schema = dto.getAnnotation(Schema.class);
            assertThat(schema)
                    .as("DTO %s must have @Schema annotation", dto.getSimpleName())
                    .isNotNull();
            assertThat(schema.description())
                    .as("DTO %s @Schema.description must not be blank", dto.getSimpleName())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Swagger ModelConverters successfully resolves DTO schemas without runtime reflection errors")
    void testModelConvertersResolution() {
        io.swagger.v3.core.converter.ModelConverters converters = io.swagger.v3.core.converter.ModelConverters.getInstance();
        java.util.Map<String, io.swagger.v3.oas.models.media.Schema> schemas = converters.readAll(JobController.JobDTO.class);
        assertThat(schemas).isNotEmpty();
        assertThat(schemas).containsKey("JobDTO");
    }
}
