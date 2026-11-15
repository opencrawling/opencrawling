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
package org.opencrawling.runtime.api.copilot;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Generated narrative template response")
public record TemplateCopilotResponse(
    @Schema(description = "Generated Mustache prompt template", example = "# {{title}}\n{{summary}}")
    String template,
    @Schema(description = "Sample mock data values used for preview rendering")
    Map<String, Object> mockData
) {}
