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

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auto-Narrativization Copilot", description = "LLM-powered generation of narrative prompt templates for metadata-to-text transformation")
@RestController
@RequestMapping("/api/transformation/copilot")
@CrossOrigin(origins = "*")
public class NarrativizationCopilotController {

    private final TemplateGenerationCopilot copilotService;

    public NarrativizationCopilotController(TemplateGenerationCopilot copilotService) {
        this.copilotService = copilotService;
    }

    @Operation(summary = "Generate narrativization template", description = "Uses an LLM (Ollama or OpenAI) to generate a Mustache prompt template transforming structured schema fields into rich natural language narrative text.")
    @ApiResponse(responseCode = "200", description = "Generated template with explanation and sample rendering")
    @PostMapping("/generate")
    public ResponseEntity<TemplateCopilotResponse> generateTemplate(
            @RequestBody SchemaContextRequest request
    ) {
        TemplateCopilotResponse response = copilotService.generate(request);
        return ResponseEntity.ok(response);
    }
}
