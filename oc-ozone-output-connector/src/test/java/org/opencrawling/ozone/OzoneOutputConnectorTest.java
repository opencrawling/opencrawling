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
package org.opencrawling.ozone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.ozone.client.OzoneNativeStorageClient;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.opencrawling.ozone.model.OisMigrationDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OzoneOutputConnectorTest {

    private OzoneOutputProperties properties;
    private OzoneNativeStorageClient client;
    private PipelineProperties pipelineProperties;
    private ObjectMapper objectMapper;
    private OzoneOutputConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        properties = new OzoneOutputProperties();
        properties.setVolume("test-vol");
        properties.setBucket("test-bucket");
        properties.setClientType("NATIVE");
        properties.setSidecarSuffix(".ois.json");

        client = new OzoneNativeStorageClient(properties);
        client.connect();

        pipelineProperties = new PipelineProperties();
        pipelineProperties.setMode(PipelineMode.MIGRATION);

        objectMapper = new ObjectMapper();
        connector = new OzoneOutputConnector(properties, client, pipelineProperties, objectMapper);
    }

    @Test
    void testRejectsRagModeWithIllegalStateException() {
        connector.setPipelineMode(PipelineMode.RAG);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-1",
                "file:///data/report.pdf",
                new ByteArrayInputStream("test binary".getBytes(StandardCharsets.UTF_8)),
                Map.of("filename", List.of("report.pdf")),
                "read:all",
                Instant.now()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            connector.send(doc).block();
        });

        assertTrue(ex.getMessage().contains("OzoneOutputConnector only supports MIGRATION mode"));
        assertTrue(ex.getMessage().contains("RAG mode (narrativization and embedding) is not supported"));
    }

    @Test
    void testAcceptsMigrationModeAndUploadsBinaryWithOisZeroTrustSidecar() throws Exception {
        connector.setPipelineMode(PipelineMode.MIGRATION);

        byte[] rawBytes = "Confidential Financial Contract 2026".getBytes(StandardCharsets.UTF_8);

        SecurityConfig zeroTrustSecurity = new SecurityConfig(false, List.of(
                new PermissionRule("ROLE_LEGAL", "ROLE", "Legal Team", "READ"),
                new PermissionRule("alovelace", "USER", "Ada Lovelace", "READ_WRITE"),
                new PermissionRule("ROLE_PUBLIC", "ROLE", "Public", "DENY")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "contract-101",
                "http://repo.internal/contracts/contract-101.pdf",
                new ByteArrayInputStream(rawBytes),
                Map.of(
                        "filename", List.of("contract-101.pdf"),
                        "mimeType", List.of("application/pdf"),
                        "author", List.of("Ada Lovelace"),
                        "status", List.of("EXECUTED")
                ),
                "ALLOWED:ROLE_LEGAL;ALLOWED:alovelace;DENIED:ROLE_PUBLIC",
                zeroTrustSecurity,
                Instant.parse("2026-10-05T20:00:00Z"),
                DocumentAction.UPSERT
        );

        connector.send(doc).block();

        // 1. Verify binary key exists and has identical raw bytes
        String expectedBinaryKey = "contract-101_contract-101.pdf";
        assertTrue(client.exists(expectedBinaryKey));
        assertArrayEquals(rawBytes, client.getStorage().get(expectedBinaryKey));

        // 2. Verify companion OIS JSON sidecar exists
        String expectedSidecarKey = expectedBinaryKey + ".ois.json";
        assertTrue(client.exists(expectedSidecarKey));

        byte[] sidecarBytes = client.getStorage().get(expectedSidecarKey);
        assertNotNull(sidecarBytes);

        // 3. Parse and validate companion OIS JSON payload
        OisMigrationDocument oisDoc = objectMapper.readValue(sidecarBytes, OisMigrationDocument.class);
        assertEquals("contract-101", oisDoc.id());
        assertEquals("UPSERT", oisDoc.action());
        assertEquals("ofs://test-vol/test-bucket/" + expectedBinaryKey, oisDoc.uri());
        assertEquals("2026-10-05T20:00:00Z", oisDoc.lastModified());

        // Validate contentRef integrity
        assertNotNull(oisDoc.contentRef());
        assertEquals(expectedBinaryKey, oisDoc.contentRef().key());
        assertEquals("test-vol", oisDoc.contentRef().volume());
        assertEquals("test-bucket", oisDoc.contentRef().bucket());
        assertEquals("application/pdf", oisDoc.contentRef().mimeType());
        assertEquals(rawBytes.length, oisDoc.contentRef().contentLength());
        assertNotNull(oisDoc.contentRef().checksumSha256());
        assertFalse(oisDoc.contentRef().checksumSha256().isBlank());

        // Validate metadata properties
        assertEquals(List.of("contract-101.pdf"), oisDoc.metadata().get("filename"));
        assertEquals(List.of("EXECUTED"), oisDoc.metadata().get("status"));

        // Validate Zero-Trust security model
        assertNotNull(oisDoc.security());
        assertFalse(oisDoc.security().inheritanceEnabled());
        assertEquals(3, oisDoc.security().permissions().size());
        assertEquals("ROLE_LEGAL", oisDoc.security().permissions().get(0).identity());
        assertEquals("READ", oisDoc.security().permissions().get(0).access());
        assertEquals("DENY", oisDoc.security().permissions().get(2).access());
    }

    @Test
    void testHandlesDeleteTombstoneAction() {
        connector.setPipelineMode(PipelineMode.MIGRATION);

        String key = "doc-del_deleted.pdf";
        String sidecarKey = key + ".ois.json";

        // Seed storage with existing item
        client.getStorage().put(key, "data".getBytes(StandardCharsets.UTF_8));
        client.getStorage().put(sidecarKey, "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(client.exists(key));
        assertTrue(client.exists(sidecarKey));

        RepositoryDocument tombstone = new RepositoryDocument(
                "doc-del",
                "file:///data/deleted.pdf",
                null,
                Map.of("filename", List.of("deleted.pdf")),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        connector.send(tombstone).block();

        // Verify keys have been purged
        assertFalse(client.exists(key));
        assertFalse(client.exists(sidecarKey));
    }
}
