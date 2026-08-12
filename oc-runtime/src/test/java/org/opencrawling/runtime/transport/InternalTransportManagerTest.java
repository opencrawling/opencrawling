/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.internal.v1.DocumentPayloadRequest;
import org.opencrawling.internal.v1.PayloadIngestionResponse;

import static org.junit.jupiter.api.Assertions.*;

class InternalTransportManagerTest {

    private InternalTransportManager transportManager;

    @BeforeEach
    void setUp() {
        new java.io.File("data/transport-settings.json").delete();
        new java.io.File("oc-runtime/data/transport-settings.json").delete();
        transportManager = new InternalTransportManager();
        transportManager.init();
    }

    @AfterEach
    void tearDown() {
        if (transportManager != null) {
            transportManager.shutdown();
        }
    }

    @Test
    void testDefaultSettingsLoaded() {
        TransportSettingsDTO settings = transportManager.getSettings();
        assertNotNull(settings);
        assertEquals("AUTO", settings.mode());
        assertTrue(settings.grpcEnabled());
        assertEquals(9095, settings.grpcPort());
        assertTrue(settings.fallbackToRest());
    }

    @Test
    void testGrpcConnectionPingInAutoMode() {
        TestGrpcResponseDTO pingResult = transportManager.testGrpcConnection("127.0.0.1", 9095);
        assertNotNull(pingResult);
        assertTrue(pingResult.grpcAvailable() || pingResult.restFallbackAvailable());
        assertNotNull(pingResult.status());
    }

    @Test
    void testSendPayloadAutoMode() {
        DocumentPayloadRequest request = DocumentPayloadRequest.newBuilder()
                .setTaskId("task-001")
                .setRepositoryId("repo-fs")
                .setDocumentId("doc-123")
                .setRawContent("sample content".getBytes())
                .putMetadata("author", "admin")
                .addSecurityAcl("ROLE_USER")
                .build();

        PayloadIngestionResponse response = transportManager.sendPayload(request, "127.0.0.1", 9095);
        assertNotNull(response);
        assertEquals("doc-123", response.getDocumentId());
        assertEquals(PayloadIngestionResponse.Status.SUCCESS, response.getStatus());
    }

    @Test
    void testSwitchToRestMode() {
        TransportSettingsDTO restConfig = new TransportSettingsDTO(
            "REST",
            false,
            9095,
            32,
            true,
            30000L,
            5000L,
            false,
            "",
            ""
        );
        transportManager.updateSettings(restConfig);
        assertEquals("REST", transportManager.getSettings().mode());

        DocumentPayloadRequest request = DocumentPayloadRequest.newBuilder()
                .setTaskId("task-002")
                .setRepositoryId("repo-fs")
                .setDocumentId("doc-456")
                .build();

        PayloadIngestionResponse response = transportManager.sendPayload(request, "127.0.0.1", 9095);
        assertNotNull(response);
        assertEquals(PayloadIngestionResponse.Status.SUCCESS, response.getStatus());
    }

    @Test
    void testFallbackWhenGrpcPortUnreachable() {
        // Attempting payload against unreachable port (9999) with fallbackToRest = true
        TransportSettingsDTO autoConfig = new TransportSettingsDTO(
            "AUTO",
            true,
            9999,
            32,
            true,
            30000L,
            1000L,
            false,
            "",
            ""
        );
        transportManager.updateSettings(autoConfig);

        DocumentPayloadRequest request = DocumentPayloadRequest.newBuilder()
                .setTaskId("task-003")
                .setRepositoryId("repo-fs")
                .setDocumentId("doc-789")
                .build();

        // Should fall back to REST gracefully
        PayloadIngestionResponse response = transportManager.sendPayload(request, "127.0.0.1", 9999);
        assertNotNull(response);
        assertEquals(PayloadIngestionResponse.Status.SUCCESS, response.getStatus());
    }
}
