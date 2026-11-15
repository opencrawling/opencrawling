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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** What a client sends to create, replace or check a connector. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A connector definition")
public record ConnectorInput(
        @NotBlank @Pattern(regexp = NAME_PATTERN, message = "must be 1 to 100 letters, digits, spaces, '.', '_' or '-', not starting or ending with a space")
        @Schema(description = "Unique name; on replace it must match the path or be left out", example = "HR_Share", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Size(max = 500)
        @Schema(description = "Human-readable description", example = "HR file share")
        String description,
        @NotBlank @Pattern(regexp = "repository|output|transformation|authority", message = "must be repository, output, transformation or authority")
        @Schema(description = "Category", example = "repository", allowableValues = {"repository", "output", "transformation", "authority"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @NotBlank @Size(max = 300)
        @Schema(description = "Fully-qualified class of the connector implementation", example = "org.opencrawling.filesystem.FileSystemRepositoryConnector", requiredMode = Schema.RequiredMode.REQUIRED)
        String className,
        @Min(1) @Max(1000)
        @Schema(description = "Maximum concurrent connections", example = "10")
        Integer maxConnections,
        @Schema(description = "Implementation-specific settings. Secret values come back masked as \"" + ConnectorResource.MASK + "\"; send the mask back to keep a stored secret unchanged")
        Map<String, String> configuration) {

    /** Letters, digits, '.', '_', '-' and inner spaces (as ManifoldCF migrations create); never '/' or ':'. */
    public static final String NAME_PATTERN = "[A-Za-z0-9._-](?:[A-Za-z0-9 ._-]{0,98}[A-Za-z0-9._-])?";
}
