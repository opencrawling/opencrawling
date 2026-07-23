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
package org.opencrawling.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.copilot.NarrativizationCopilotController;
import org.opencrawling.runtime.api.copilot.SchemaContextRequest;
import org.opencrawling.runtime.api.copilot.TemplateCopilotResponse;
import org.opencrawling.runtime.api.copilot.TemplateGenerationCopilot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class NarrativizationCopilotIT {

    @Test
    public void testCopilotControllerFallback() {
        TemplateGenerationCopilot copilotService = new TemplateGenerationCopilot(java.util.Map.of(), "ollama");
        NarrativizationCopilotController copilotController = new NarrativizationCopilotController(copilotService);

        SchemaContextRequest request = new SchemaContextRequest(
            "iceberg",
            List.of(
                new SchemaContextRequest.FieldDto("id", "STRING", "Primary Identifier"),
                new SchemaContextRequest.FieldDto("amount", "DOUBLE", "Transaction Value")
            )
        );

        ResponseEntity<TemplateCopilotResponse> response = copilotController.generateTemplate(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        
        TemplateCopilotResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.template());
        assertTrue(body.template().contains("id"));
        assertTrue(body.template().contains("amount"));
        assertNotNull(body.mockData());
        assertEquals("Sample id", body.mockData().get("id"));
        assertEquals(123.45, body.mockData().get("amount"));
    }
}
