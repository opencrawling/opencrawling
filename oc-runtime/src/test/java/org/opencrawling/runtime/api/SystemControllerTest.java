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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.text.TextExtractionProperties;
import org.opencrawling.core.text.TextExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemControllerTest {

    private TextExtractionProperties properties;
    private TextExtractionService extractionService;
    private SystemController controller;

    @BeforeEach
    void setUp() {
        properties = new TextExtractionProperties();
        extractionService = mock(TextExtractionService.class);
        when(extractionService.isHealthy()).thenReturn(true);
        when(extractionService.getVersion()).thenReturn("4.2.0");
        when(extractionService.getEngineName()).thenReturn("PipesForkParser (Process-Isolated)");

        controller = new SystemController(null, null, properties, extractionService);
    }

    @Test
    void testSystemStatusWithHealthyTika() {
        Map<String, String> status = controller.getSystemStatus();
        assertEquals("UP", status.get("tika"));
        assertEquals("4.2.0", status.get("tikaVersion"));
        assertEquals("PipesForkParser (Process-Isolated)", status.get("tikaEngine"));
    }

    @Test
    void testSystemStatusWithUnhealthyTika() {
        when(extractionService.isHealthy()).thenReturn(false);
        Map<String, String> status = controller.getSystemStatus();
        assertEquals("DOWN", status.get("tika"));
    }

    @Test
    void testSystemStatusWhenTikaDisabled() {
        SystemController noTikaController = new SystemController(null, null, properties, null);
        Map<String, String> status = noTikaController.getSystemStatus();
        assertEquals("DISABLED", status.get("tika"));
        assertEquals("Disabled", status.get("tikaEngine"));
    }

    @Test
    void testUpdateSettingsValidHeapAndClients() {
        SystemController.SystemSettingsDTO dto = new SystemController.SystemSettingsDTO(
                "Ollama", "http://127.0.0.1:11434", "mxbai-embed-large", 1024,
                "TokenTextSplitter", 800, 100, "ozone", "NATIVE",
                "http://127.0.0.1:9878", "127.0.0.1", 9862, "s3v", "claims",
                "4.2.0", "PipesForkParser (Process-Isolated)", true, 45000L,
                "-Xmx1g", 5000, true, true, 10000000, 4
        );

        ResponseEntity<Void> response = controller.updateSettings(dto);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("true", response.getHeaders().getFirst("X-Requires-Restart"));

        assertEquals(45000L, properties.getTimeoutMs());
        assertEquals("-Xmx1g", properties.getJvmArgs().get(0));
        assertEquals(5000, properties.getMaxFilesPerProcess());
        assertEquals(4, properties.getNumClients());
        assertTrue(properties.isExtractEmbedded());
        assertEquals(10000000, properties.getWriteLimit());
    }

    @Test
    void testUpdateSettingsRejectsCommandInjectionHeap() {
        SystemController.SystemSettingsDTO dto = new SystemController.SystemSettingsDTO(
                "Ollama", "http://127.0.0.1:11434", "mxbai-embed-large", 1024,
                "TokenTextSplitter", 800, 100, "ozone", "NATIVE",
                "http://127.0.0.1:9878", "127.0.0.1", 9862, "s3v", "claims",
                "4.2.0", "PipesForkParser (Process-Isolated)", true, 30000L,
                "-Xmx512m; rm -rf /", 10000, true, true, 20000000, 1
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.updateSettings(dto));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid JVM heap argument"));
    }

    @Test
    void testUpdateSettingsRejectsArbitraryJvmFlags() {
        SystemController.SystemSettingsDTO dto = new SystemController.SystemSettingsDTO(
                "Ollama", "http://127.0.0.1:11434", "mxbai-embed-large", 1024,
                "TokenTextSplitter", 800, 100, "ozone", "NATIVE",
                "http://127.0.0.1:9878", "127.0.0.1", 9862, "s3v", "claims",
                "4.2.0", "PipesForkParser (Process-Isolated)", true, 30000L,
                "-Dcom.sun.management.jmxremote", 10000, true, true, 20000000, 1
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.updateSettings(dto));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid JVM heap argument"));
    }
}
