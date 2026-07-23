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

@RestController
@RequestMapping("/api/transformation/copilot")
@CrossOrigin(origins = "*")
public class NarrativizationCopilotController {

    private final TemplateGenerationCopilot copilotService;

    public NarrativizationCopilotController(TemplateGenerationCopilot copilotService) {
        this.copilotService = copilotService;
    }

    @PostMapping("/generate")
    public ResponseEntity<TemplateCopilotResponse> generateTemplate(
            @RequestBody SchemaContextRequest request
    ) {
        TemplateCopilotResponse response = copilotService.generate(request);
        return ResponseEntity.ok(response);
    }
}
