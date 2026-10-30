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
package org.opencrawling.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.s3.config.S3OutputProperties;
import org.opencrawling.s3.model.OisMigrationDocument;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3OutputConnectorTest {

    private S3OutputProperties properties;
    private S3Client s3Client;
    private PipelineProperties pipelineProperties;
    private ObjectMapper objectMapper;
    private S3OutputConnector connector;

    @BeforeEach
    void setUp() {
        properties = new S3OutputProperties();
        properties.setBucket("my-migration-bucket");
        properties.setRegion("us-east-1");
        properties.setKeyPrefix("migration/v1/");
        properties.setSidecarSuffix(".ois.json");
        properties.setStorageClass("INTELLIGENT_TIERING");
        properties.setServerSideEncryption("SSE_S3");

        s3Client = mock(S3Client.class);
        pipelineProperties = new PipelineProperties();
        pipelineProperties.setMode(PipelineMode.MIGRATION);
        objectMapper = new ObjectMapper();

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"mock-etag-12345\"").build());

        connector = new S3OutputConnector(properties, s3Client, pipelineProperties, objectMapper);
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

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> connector.send(doc).block());

        assertTrue(ex.getMessage().contains("S3OutputConnector only supports MIGRATION mode"));
        assertTrue(ex.getMessage().contains("RAG mode (narrativization and embedding) is not supported"));
    }

    @Test
    void testAcceptsMigrationModeAndUploadsBinaryWithOisZeroTrustSidecar() throws Exception {
        connector.setPipelineMode(PipelineMode.MIGRATION);

        byte[] rawBytes = "Confidential Financial Contract 2026 for S3 Migration".getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));

        SecurityConfig zeroTrustSecurity = new SecurityConfig(false, List.of(
                new PermissionRule("ROLE_LEGAL", "ROLE", "Legal Team", "READ"),
                new PermissionRule("alovelace", "USER", "Ada Lovelace", "READ_WRITE")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-fin-001",
                "file:///contracts/2026/q3_filing.pdf",
                new ByteArrayInputStream(rawBytes),
                new java.util.HashMap<>(Map.of(
                        "filename", List.of("q3_filing.pdf"),
                        "relativePath", List.of("contracts/2026/q3_filing.pdf"),
                        "mimeType", List.of("application/pdf"),
                        "source_type", List.of("filenet")
                )),
                "group:legal_compliance",
                zeroTrustSecurity,
                Instant.parse("2026-10-30T10:00:00Z"),
                DocumentAction.UPSERT
        );

        connector.send(doc).block();

        // Verify 3 putObject invocations: binary, sidecar, index pointer
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(3)).putObject(putCaptor.capture(), bodyCaptor.capture());

        List<PutObjectRequest> requests = putCaptor.getAllValues();

        // 1. Verify Binary Upload
        PutObjectRequest binaryReq = requests.get(0);
        assertEquals("my-migration-bucket", binaryReq.bucket());
        assertEquals("migration/v1/contracts/2026/q3_filing.pdf", binaryReq.key());
        assertEquals("application/pdf", binaryReq.contentType());
        assertEquals((long) rawBytes.length, binaryReq.contentLength());
        assertEquals(StorageClass.INTELLIGENT_TIERING, binaryReq.storageClass());
        assertEquals(ServerSideEncryption.AES256, binaryReq.serverSideEncryption());

        // 2. Verify Companion OIS Sidecar Upload
        PutObjectRequest sidecarReq = requests.get(1);
        assertEquals("my-migration-bucket", sidecarReq.bucket());
        assertEquals("migration/v1/contracts/2026/q3_filing.pdf.ois.json", sidecarReq.key());
        assertEquals("application/json", sidecarReq.contentType());

        // 3. Verify Index Pointer
        PutObjectRequest indexReq = requests.get(2);
        assertEquals("my-migration-bucket", indexReq.bucket());
        assertTrue(indexReq.key().startsWith(S3OutputConnector.INDEX_PREFIX));
        assertEquals("text/plain", indexReq.contentType());

        // Verify document metadata was enriched with SHA-256 and target key for ledger accumulator
        assertEquals(expectedSha256, doc.metadata().get("checksum_sha256").get(0));
        assertEquals("my-migration-bucket", doc.metadata().get("target_bucket").get(0));
        assertEquals("migration/v1/contracts/2026/q3_filing.pdf", doc.metadata().get("target_key").get(0));
        assertEquals(String.valueOf(rawBytes.length), doc.metadata().get("binary_size").get(0));
    }

    @Test
    void testDeleteObjectsRemovesBinarySidecarAndIndex() {
        properties.setTombstoneAction("DELETE_OBJECTS");

        String targetKey = "migration/v1/contracts/2026/to_delete.pdf";
        String indexKey = S3OutputConnector.indexKey("doc-to-delete");

        // Mock index lookup
        ResponseInputStream<GetObjectResponse> mockIn = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(targetKey.getBytes(StandardCharsets.UTF_8)))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockIn);

        RepositoryDocument deleteDoc = new RepositoryDocument(
                "doc-to-delete",
                "file:///contracts/2026/to_delete.pdf",
                null,
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        connector.send(deleteDoc).block();

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(3)).deleteObject(deleteCaptor.capture());

        List<DeleteObjectRequest> deleteReqs = deleteCaptor.getAllValues();
        assertEquals(targetKey, deleteReqs.get(0).key());
        assertEquals(targetKey + ".ois.json", deleteReqs.get(1).key());
        assertEquals(indexKey, deleteReqs.get(2).key());
    }

    @Test
    void testArchiveTombstoneActionWritesOisTombstoneBeforeDelete() {
        properties.setTombstoneAction("ARCHIVE_TOMBSTONE");

        String targetKey = "migration/v1/contracts/archived.pdf";

        ResponseInputStream<GetObjectResponse> mockIn = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(targetKey.getBytes(StandardCharsets.UTF_8)))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockIn);

        RepositoryDocument deleteDoc = new RepositoryDocument(
                "doc-archive-1",
                "file:///contracts/archived.pdf",
                null,
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        connector.send(deleteDoc).block();

        // 1 putObject for tombstone archive
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(putCaptor.capture(), any(RequestBody.class));
        assertEquals(S3OutputConnector.TOMBSTONE_PREFIX + targetKey + ".ois.json", putCaptor.getValue().key());

        // 3 deletes for binary, sidecar, and index
        verify(s3Client, times(3)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void testKeyStrategies() {
        RepositoryDocument doc = new RepositoryDocument(
                "doc:123/abc",
                "file:///data/folder/file.txt",
                null,
                Map.of("filename", List.of("file.txt"), "relativePath", List.of("folder/file.txt")),
                "",
                Instant.now()
        );

        // HIERARCHICAL (default)
        properties.setKeyStrategy("HIERARCHICAL");
        properties.setKeyPrefix("exports/2026/");
        assertEquals("exports/2026/folder/file.txt", connector.resolveKey(doc));

        // FLAT
        properties.setKeyStrategy("FLAT");
        assertEquals("exports/2026/doc_123_abc_file.txt", connector.resolveKey(doc));

        // DATE_PARTITIONED
        properties.setKeyStrategy("DATE_PARTITIONED");
        String dateKey = connector.resolveKey(doc);
        assertTrue(dateKey.startsWith("exports/2026/"));
        assertTrue(dateKey.contains("doc_123_abc_file.txt"));
    }

    @Test
    void testEnterpriseKmsAndObjectLockOptions() {
        properties.setServerSideEncryption("SSE_KMS");
        properties.setKmsKeyId("arn:aws:kms:us-east-1:123456789012:key/my-key-id");
        properties.setBucketKeyEnabled(true);
        properties.setObjectLockEnabled(true);
        properties.setObjectLockMode("COMPLIANCE");
        properties.setObjectLockRetentionDays(180);
        properties.setObjectLockLegalHold(true);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-locked-01",
                "file:///data/doc.pdf",
                new ByteArrayInputStream("protected data".getBytes(StandardCharsets.UTF_8)),
                new java.util.HashMap<>(Map.of("filename", List.of("doc.pdf"))),
                "",
                Instant.now()
        );

        connector.send(doc).block();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, atLeastOnce()).putObject(putCaptor.capture(), any(RequestBody.class));

        PutObjectRequest binaryReq = putCaptor.getAllValues().get(0);
        assertEquals(ServerSideEncryption.AWS_KMS, binaryReq.serverSideEncryption());
        assertEquals("arn:aws:kms:us-east-1:123456789012:key/my-key-id", binaryReq.ssekmsKeyId());
        assertTrue(binaryReq.bucketKeyEnabled());
        assertEquals(ObjectLockMode.COMPLIANCE, binaryReq.objectLockMode());
        assertEquals(ObjectLockLegalHoldStatus.ON, binaryReq.objectLockLegalHoldStatus());
        assertNotNull(binaryReq.objectLockRetainUntilDate());
    }

    @Test
    void testCustomHeadersDecorationOnPutAndSidecar() {
        properties.getCustomHeaders().put("x-emc-namespace", "finance-namespace");
        properties.getCustomHeaders().put("x-emc-metadata-search", "Dept=${metadata.department},Classification=${metadata.classification | Public}");

        RepositoryDocument doc = new RepositoryDocument(
                "doc-custom-hdr",
                "file:///contracts/agreement.pdf",
                new ByteArrayInputStream("agreement-binary".getBytes(StandardCharsets.UTF_8)),
                new java.util.HashMap<>(Map.of(
                        "filename", List.of("agreement.pdf"),
                        "department", List.of("Legal")
                )),
                "",
                Instant.now()
        );

        connector.send(doc).block();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(putCaptor.capture(), any(RequestBody.class));

        List<PutObjectRequest> requests = putCaptor.getAllValues();
        for (PutObjectRequest req : requests) {
            assertTrue(req.overrideConfiguration().isPresent());
            Map<String, List<String>> headers = req.overrideConfiguration().get().headers();
            assertEquals(List.of("finance-namespace"), headers.get("x-emc-namespace"));
            assertEquals(List.of("Dept=Legal,Classification=Public"), headers.get("x-emc-metadata-search"));
        }
    }

    @Test
    void testCustomHeadersDecorationOnDeleteAndIndexLookup() {
        properties.getCustomHeaders().put("x-emc-namespace", "hr-namespace");

        String targetKey = "migration/v1/hr/cv.pdf";
        ResponseInputStream<GetObjectResponse> mockIn = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(targetKey.getBytes(StandardCharsets.UTF_8)))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(mockIn);

        RepositoryDocument deleteDoc = new RepositoryDocument(
                "doc-del-hdr",
                "file:///hr/cv.pdf",
                null,
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        connector.send(deleteDoc).block();

        // Verify index lookup had custom headers
        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(getCaptor.capture());
        assertTrue(getCaptor.getValue().overrideConfiguration().isPresent());
        assertEquals(List.of("hr-namespace"), getCaptor.getValue().overrideConfiguration().get().headers().get("x-emc-namespace"));

        // Verify all 3 delete requests had custom headers
        ArgumentCaptor<DeleteObjectRequest> delCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(3)).deleteObject(delCaptor.capture());
        for (DeleteObjectRequest req : delCaptor.getAllValues()) {
            assertTrue(req.overrideConfiguration().isPresent());
            assertEquals(List.of("hr-namespace"), req.overrideConfiguration().get().headers().get("x-emc-namespace"));
        }
    }
}
