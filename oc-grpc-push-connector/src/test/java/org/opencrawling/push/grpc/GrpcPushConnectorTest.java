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
package org.opencrawling.push.grpc;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.PushOptions;
import org.opencrawling.push.v1.SecurityConfig;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GrpcPushConnectorTest {

    private ClaimCheckStore claimCheckStore;
    private GrpcPushRepositoryConnector connector;

    @BeforeEach
    void setUp() {
        claimCheckStore = mock(ClaimCheckStore.class);
        connector = new GrpcPushRepositoryConnector(new GrpcPushProperties());
    }

    @Test
    void testConnectorMetadataAndSchema() {
        assertEquals("GrpcPushConnector", connector.getName());
        ConnectorSchema schema = connector.getSchema("");
        assertNotNull(schema);
        assertFalse(schema.fields().isEmpty());
        assertTrue(schema.fields().stream().anyMatch(f -> "id".equals(f.name())));
        assertTrue(schema.fields().stream().anyMatch(f -> "raw_bytes".equals(f.name())));
        assertTrue(schema.fields().stream().anyMatch(f -> "claim_check_uri".equals(f.name())));
        assertTrue(schema.fields().stream().anyMatch(f -> "security".equals(f.name())));
    }

    @Test
    void testTranslateUpsertWithInlineText() throws Exception {
        OisDocumentPayload payload = OisDocumentPayload.newBuilder()
                .setId("doc-123")
                .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                .setMimeType("text/plain")
                .setTimestamp(1700000000000L)
                .setTextContent("Sample text body content for indexing")
                .putMetadata("department", "Engineering")
                .setSecurity(SecurityConfig.newBuilder()
                        .addAllowSids("ROLE_ENGINEERS")
                        .setOwner("alice@corp.com")
                        .build())
                .build();

        PushOptions options = PushOptions.newBuilder()
                .setBypassTikaExtraction(true)
                .build();

        RepositoryDocument doc = OisDocumentTranslator.translate(payload, options, claimCheckStore, 512);

        assertEquals("doc-123", doc.id());
        assertEquals("doc-123", doc.uri());
        assertEquals(DocumentAction.UPSERT, doc.action());
        assertEquals(1700000000000L, doc.lastModified().toEpochMilli());
        assertEquals("ROLE_ENGINEERS", doc.acl());
        assertEquals(List.of("Engineering"), doc.metadata().get("department"));
        assertEquals(List.of("true"), doc.metadata().get("bypass_tika"));
        assertNotNull(doc.contentStream());
        assertEquals("Sample text body content for indexing", new String(doc.contentStream().readAllBytes(), StandardCharsets.UTF_8));
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testTranslateUpsertWithPreUploadedClaimCheckUri() throws Exception {
        OisDocumentPayload payload = OisDocumentPayload.newBuilder()
                .setId("doc-pdf-456")
                .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                .setMimeType("application/pdf")
                .setClaimCheckUri("ozone://bucket/claims/doc-pdf-456.pdf")
                .build();

        RepositoryDocument doc = OisDocumentTranslator.translate(payload, null, claimCheckStore, 512);

        assertEquals("doc-pdf-456", doc.id());
        assertEquals("ozone://bucket/claims/doc-pdf-456.pdf", doc.uri());
        assertNull(doc.contentStream());
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testTranslateUpsertWithRawBytesBelowThreshold() throws Exception {
        byte[] smallContent = "Small binary data".getBytes(StandardCharsets.UTF_8);
        OisDocumentPayload payload = OisDocumentPayload.newBuilder()
                .setId("doc-small")
                .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                .setMimeType("application/octet-stream")
                .setRawBytes(ByteString.copyFrom(smallContent))
                .build();

        RepositoryDocument doc = OisDocumentTranslator.translate(payload, null, claimCheckStore, 512);

        assertEquals("doc-small", doc.id());
        assertNotNull(doc.contentStream());
        assertArrayEquals(smallContent, doc.contentStream().readAllBytes());
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testTranslateUpsertWithRawBytesAboveThresholdOffloadsToClaimCheckStore() throws Exception {
        // Create 600 KB payload exceeding 512 KB threshold
        byte[] largeContent = new byte[600 * 1024];
        java.util.Arrays.fill(largeContent, (byte) 'X');

        URI claimUri = URI.create("file:///claims/doc-large.pdf");
        when(claimCheckStore.put(anyString(), any(InputStream.class), eq((long) largeContent.length), eq("application/pdf")))
                .thenReturn(claimUri);

        OisDocumentPayload payload = OisDocumentPayload.newBuilder()
                .setId("doc-large")
                .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                .setMimeType("application/pdf")
                .setRawBytes(ByteString.copyFrom(largeContent))
                .build();

        RepositoryDocument doc = OisDocumentTranslator.translate(payload, null, claimCheckStore, 512);

        assertEquals("doc-large", doc.id());
        assertEquals(claimUri.toString(), doc.uri());
        assertNull(doc.contentStream(), "Content stream should be cleared after claim-check offload");
        verify(claimCheckStore).put(anyString(), any(InputStream.class), eq((long) largeContent.length), eq("application/pdf"));
    }

    @Test
    void testTranslateDeleteTombstone() throws Exception {
        OisDocumentPayload payload = OisDocumentPayload.newBuilder()
                .setId("doc-deleted-999")
                .setAction(org.opencrawling.push.v1.DocumentAction.DELETE)
                .build();

        RepositoryDocument doc = OisDocumentTranslator.translate(payload, null, claimCheckStore, 512);

        assertEquals("doc-deleted-999", doc.id());
        assertEquals("doc-deleted-999", doc.uri());
        assertEquals(DocumentAction.DELETE, doc.action());
        assertNull(doc.contentStream());
        verifyNoInteractions(claimCheckStore);
    }

    @Test
    void testSecurityAclMapping() {
        SecurityConfig protoSec = SecurityConfig.newBuilder()
                .addAllowSids("ROLE_LEGAL")
                .addAllowSids("alice@corp.com")
                .addDenySids("ROLE_CONTRACTORS")
                .setOwner("owner@corp.com")
                .setTenantId("TENANT_CORP")
                .build();

        org.opencrawling.core.security.SecurityConfig sec = OisDocumentTranslator.translateSecurity(protoSec);
        assertNotNull(sec);
        assertFalse(sec.inheritanceEnabled());
        List<PermissionRule> rules = sec.permissions();
        assertEquals(5, rules.size());

        assertTrue(rules.stream().anyMatch(r -> "ROLE_LEGAL".equals(r.identity()) && "read".equals(r.access())));
        assertTrue(rules.stream().anyMatch(r -> "alice@corp.com".equals(r.identity()) && "read".equals(r.access())));
        assertTrue(rules.stream().anyMatch(r -> "ROLE_CONTRACTORS".equals(r.identity()) && "deny".equals(r.access())));
        assertTrue(rules.stream().anyMatch(r -> "owner@corp.com".equals(r.identity()) && "write".equals(r.access())));
        assertTrue(rules.stream().anyMatch(r -> "TENANT_CORP".equals(r.identity()) && "read".equals(r.access())));

        assertEquals("ROLE_LEGAL", OisDocumentTranslator.extractAcl(protoSec));
    }

    @Test
    void testGrpcPushClientCliAgainstEmbeddedServer() {
        GrpcPushProperties props = new GrpcPushProperties();
        props.setEnabled(true);
        props.setPort(0);
        props.setToken("cli-test-token");

        PushDocumentDispatcher dispatcher = request -> org.opencrawling.push.v1.PushDocumentResponse.newBuilder()
                .setDocumentId(request.getDocument().getId())
                .setStatus(org.opencrawling.push.v1.IngestionStatus.ACCEPTED)
                .setMessage("Queued")
                .setProcessedAt(System.currentTimeMillis())
                .build();

        GrpcPushServerManager manager = new GrpcPushServerManager(
                props,
                new OisPushServiceImpl(dispatcher),
                new GrpcSecurityInterceptor(props),
                new OpenTelemetryServerInterceptor());
        manager.start();
        try {
            String portStr = String.valueOf(manager.getPort());

            assertEquals(0, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr, "--action", "ping"
            }));

            assertEquals(1, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr, "--action", "upsert", "--doc-id", "doc-unauth"
            }));

            assertEquals(0, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr,
                    "--token", "cli-test-token",
                    "--action", "upsert",
                    "--job-id", "1",
                    "--doc-id", "http://test/doc-1",
                    "--content", "Hello from CLI",
                    "--acl-allow", "group:eng,user:alice",
                    "--metadata", "source=cli-test"
            }));

            assertEquals(0, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr,
                    "--token", "cli-test-token",
                    "--action", "delete",
                    "--job-id", "1",
                    "--doc-id", "http://test/doc-1"
            }));

            assertEquals(0, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr,
                    "--token", "cli-test-token",
                    "--action", "stream",
                    "--job-id", "1",
                    "--doc-id", "http://test/doc-2,http://test/doc-3",
                    "--content", "Streamed content"
            }));

            assertEquals(0, GrpcPushClientCli.execute(new String[]{
                    "--host", "127.0.0.1", "--port", portStr,
                    "--token", "cli-test-token",
                    "--action", "bidi",
                    "--job-id", "1",
                    "--doc-id", "http://test/doc-4,http://test/doc-5",
                    "--content", "Bidi content"
            }));
        } finally {
            manager.stop();
        }
    }
}
