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
package org.opencrawling.runtime.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.opencrawling.runtime.api.JobAccess;
import org.opencrawling.runtime.api.JobController;

import java.util.Map;

/** What a client sends to create or replace a job. Status, stage and document counts are managed by the runtime. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A job definition, as sent to create or replace a job")
public record JobInput(
        @NotBlank @Size(max = 200)
        @Schema(description = "Human-readable name", example = "HR contracts", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @NotBlank @Pattern(regexp = ConnectorInput.NAME_PATTERN, message = "must be a connector name")
        @Schema(description = "Name of a configured repository connector", example = "FileSystem_Local", requiredMode = Schema.RequiredMode.REQUIRED)
        String repositoryConnector,
        @NotBlank @Pattern(regexp = ConnectorInput.NAME_PATTERN, message = "must be a connector name")
        @Schema(description = "Name of a configured output connector", example = "PGVector_Output", requiredMode = Schema.RequiredMode.REQUIRED)
        String outputConnector,
        @Pattern(regexp = ConnectorInput.NAME_PATTERN, message = "must be a connector name")
        @Schema(description = "Name of a configured authority connector, if the job uses one")
        String authorityConnector,
        @Pattern(regexp = ConnectorInput.NAME_PATTERN, message = "must be a connector name")
        @Schema(description = "Name of a configured transformation (embedding) connector; default Ollama_Embedding_Default", example = "Ollama_Embedding_Default")
        String transformationConnector,
        @NotBlank @Size(max = 4096)
        @Schema(description = "Where the repository connector starts: a path, URL or repository location", example = "/data/hr", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "Pipeline mode: rag or migration; default: the runtime's opencrawling.pipeline.mode", example = "rag")
        String pipelineMode,
        @Size(max = 200, message = "may have at most 200 settings")
        @Schema(description = "Connector-specific settings, such as the web crawler's seeds and limits (at most 200)")
        Map<String, Object> crawlSpec,
        @Schema(description = "Auto-narrativization settings")
        JobController.NarrativizationConfig narrativization,
        @Schema(description = "Who may see and run the job when management authorization is on. On create the caller becomes the owner; on replace, leave it out to keep the current access")
        JobAccess access) {

    /** The input that recreates a stored job's definition. */
    public static JobInput of(JobController.JobDTO job) {
        return new JobInput(job.name(), job.repositoryConnector(), job.outputConnector(),
                blankToNull(job.authorityConnector()), job.transformationConnector(), job.path(), job.pipelineMode(),
                job.crawlSpec(), job.narrativization(), job.access());
    }

    /** The legacy job model for this input; {@code id} null for a new job. Status fields stay as the runtime has them. */
    public JobController.JobDTO toJob(String id) {
        return new JobController.JobDTO(id, name, repositoryConnector, outputConnector,
                authorityConnector == null ? "" : authorityConnector, path, null, null, 0, null,
                transformationConnector, narrativization, pipelineMode, crawlSpec, access);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
