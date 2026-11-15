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
package org.opencrawling.runtime.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * OpenAPI 3.1 configuration for OpenCrawling Runtime REST API.
 */
@Configuration
public class OpenApiConfig {

    public static final String OIS_TOKEN_AUTH = "OisTokenAuth";

    @Bean
    public OpenAPI openCrawlingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenCrawling Runtime REST API")
                        .description("Enterprise-grade, high-performance document crawling and ingestion platform "
                                + "built with Java 25 and Spring Boot 4. Provides comprehensive REST APIs for job orchestration, "
                                + "repository scanning, OIS ingestion, Lakehouse audit reporting, AIOps diagnostics, "
                                + "and multi-vector index management.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("OpenCrawling Community")
                                .url("https://github.com/opencrawling/opencrawling")
                                .email("piergiorgio@apache.org"))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(OIS_TOKEN_AUTH, new SecurityScheme()
                                .name(OIS_TOKEN_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("Token")
                                .description("Bearer token authentication for OIS document direct ingestion (/api/v1/ingest/ois/{jobId})")))
                .tags(List.of(
                        new Tag().name("Pipeline & Jobs")
                                .description("Operations for managing, configuring, triggering, and monitoring crawling pipeline jobs"),
                        new Tag().name("Connectors")
                                .description("Management, configuration, and live connectivity tests for repository, output, and transformation connectors"),
                        new Tag().name("OIS Direct Ingestion")
                                .description("Direct ingestion of Open Ingestion Standard (OIS) documents into active RAG jobs"),
                        new Tag().name("Reports & Lakehouse Audit")
                                .description("Execution reports, document ledgers, Parquet/Avro/CSV exports, and serverless DuckDB analytics"),
                        new Tag().name("Observability & AIOps")
                                .description("AIOps root-cause diagnostics, OpenTelemetry distributed traces, error logs, and metrics"),
                        new Tag().name("System & Settings")
                                .description("System health, JVM runtime metrics, live application logs, and global crawler/extraction settings"),
                        new Tag().name("Transport Settings")
                                .description("Configuration and testing of internal communication transport (gRPC vs. REST)"),
                        new Tag().name("ManifoldCF Migration")
                                .description("Planning and automated migration from Apache ManifoldCF configurations to OpenCrawling"),
                        new Tag().name("Vespa Insights")
                                .description("Vespa cluster health, document counts, search query testing, and schema deployments"),
                        new Tag().name("Auto-Narrativization Copilot")
                                .description("LLM-powered generation of narrative prompt templates for metadata-to-text transformation")));
    }
}
