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
        properties.setOmPort(1); // nothing listens here -> deterministic in-memory fallback
        properties.setAllowInMemoryFallback(true);

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
    void testHandlesDeleteTombstoneWithoutMetadataAfterUpsert() {
        connector.setPipelineMode(PipelineMode.MIGRATION);

        // UPSERT through the claim-check path: uri is the claim-check URI, source path in metadata
        RepositoryDocument upsert = new RepositoryDocument(
                "/data/legal/deleted.pdf",
                "s3://claims/abc_deleted.pdf",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
                Map.of("file_name", List.of("deleted.pdf"), "file_path", List.of("/data/legal/deleted.pdf")),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );
        connector.send(upsert).block();

        String key = "data/legal/deleted.pdf";
        String sidecarKey = key + ".ois.json";
        assertTrue(client.exists(key));
        assertTrue(client.exists(sidecarKey));
        assertTrue(client.exists(OzoneOutputConnector.indexKey("/data/legal/deleted.pdf")));

        // Real tombstones carry no metadata at all (RepositoryDocument.createTombstone)
        connector.send(RepositoryDocument.createTombstone("/data/legal/deleted.pdf", "file:///somewhere/else.pdf")).block();

        assertFalse(client.exists(key));
        assertFalse(client.exists(sidecarKey));
        assertFalse(client.exists(OzoneOutputConnector.indexKey("/data/legal/deleted.pdf")));
    }

    @Test
    void testArchiveTombstoneWritesOisDeleteEnvelope() throws Exception {
        properties.setTombstoneAction("ARCHIVE_TOMBSTONE");
        connector.send(new RepositoryDocument(
                "doc-arch", "http://repo/doc-arch", new ByteArrayInputStream(new byte[] {1, 2, 3}),
                Map.of("filename", List.of("a.bin")), "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.UPSERT)).block();

        connector.send(RepositoryDocument.createTombstone("doc-arch", "http://repo/doc-arch")).block();

        byte[] archived = client.getStorage().get(".tombstones/doc-arch_a.bin.ois.json");
        assertNotNull(archived);
        OisMigrationDocument tombstone = objectMapper.readValue(archived, OisMigrationDocument.class);
        assertEquals("DELETE", tombstone.action());
        assertEquals("doc-arch", tombstone.id());
        assertNull(tombstone.contentRef());
        assertFalse(client.exists("doc-arch_a.bin"));
    }

    @Test
    void testChecksumAndContentRefMatchSourceBinary() throws Exception {
        byte[] raw = new byte[256 * 1024];
        new java.util.Random(42).nextBytes(raw);
        String expectedSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(raw));

        connector.send(new RepositoryDocument(
                "bin-1", "s3://claims/bin-1", new ByteArrayInputStream(raw),
                Map.of("name", List.of("photo.png")), "", SecurityConfig.createPublic(),
                Instant.parse("2026-10-05T20:00:00Z"), DocumentAction.UPSERT)).block();

        String key = "bin-1_photo.png";
        assertArrayEquals(raw, client.getStorage().get(key));
        OisMigrationDocument ois = objectMapper.readValue(client.getStorage().get(key + ".ois.json"), OisMigrationDocument.class);
        assertEquals(expectedSha, ois.contentRef().checksumSha256());
        assertEquals(raw.length, ois.contentRef().contentLength());
        assertEquals("photo.png", ois.contentRef().filename());
        assertEquals("image/png", ois.contentRef().mimeType(), "MIME type is inferred from the file name when absent");
        assertEquals("s3://claims/bin-1", ois.contentRef().claimCheckUri());
    }

    @Test
    void testHierarchicalKeySanitizesTraversalSegments() {
        RepositoryDocument doc = new RepositoryDocument(
                "evil", "http://x", null,
                Map.of("relativePath", List.of("../../etc/./passwd")), "", Instant.now());
        assertEquals("etc/passwd", connector.resolveKey(doc));

        assertNull(OzoneOutputConnector.sanitizePath("/../.."));
        assertEquals("a/b/c.txt", OzoneOutputConnector.sanitizePath("\\a\\b\\c.txt"));
    }

    @Test
    void testFlatKeySanitizesDocumentId() {
        properties.setKeyStrategy("FLAT");
        RepositoryDocument doc = new RepositoryDocument(
                "/data/x y.txt", "file:///data/x%20y.txt", null,
                Map.of("file_name", List.of("x y.txt")), "", Instant.now());
        assertEquals("_data_x_y.txt_x_y.txt", connector.resolveKey(doc));
    }

    @Test
    void testUnreachableOzoneFailsWhenFallbackDisabled() {
        OzoneOutputProperties strict = new OzoneOutputProperties();
        strict.setOmPort(1);
        strict.setAllowInMemoryFallback(false);
        OzoneOutputConnector strictConnector = new OzoneOutputConnector(strict, new OzoneNativeStorageClient(strict), pipelineProperties, objectMapper);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-x", "s3://claims/doc-x", new ByteArrayInputStream(new byte[] {1}),
                Map.of(), "", Instant.now());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strictConnector.send(doc).block());
        assertTrue(ex.getMessage().contains("doc-x"));
    }
}
