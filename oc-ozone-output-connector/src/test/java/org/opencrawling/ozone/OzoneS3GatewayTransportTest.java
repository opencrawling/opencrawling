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
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.ozone.client.OzoneS3GatewayStorageClient;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.opencrawling.ozone.model.OisMigrationDocument;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the S3 Gateway (S3G) transport of the Ozone output connector, using a mocked
 * {@link S3Client} backed by an in-memory map. Every upload body is read TWICE, as the AWS SDK does
 * for flexible checksums and retries: a non-repeatable body (e.g. a raw non-markable InputStream)
 * makes these tests fail instead of surfacing only against a live S3 Gateway.
 */
class OzoneS3GatewayTransportTest {

    private static final String BUCKET = "migration-target";

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, PutObjectRequest> putRequests = new ConcurrentHashMap<>();
    private final List<String> deletedKeys = new ArrayList<>();
    private S3Client s3;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);

        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            byte[] first = readBody(body);
            byte[] second = readBody(body);
            assertArrayEquals(first, second, "S3 request body must be repeatable (SDK re-reads it for checksums/retries)");
            body.optionalContentLength().ifPresent(len ->
                    assertEquals(len.longValue(), first.length, "declared content length must match the body"));
            objects.put(req.key(), first);
            putRequests.put(req.key(), req);
            return PutObjectResponse.builder().build();
        });

        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            byte[] data = objects.get(req.key());
            if (data == null) {
                throw NoSuchKeyException.builder().message("missing " + req.key()).build();
            }
            return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(data)));
        });

        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            if (!objects.containsKey(req.key())) {
                throw NoSuchKeyException.builder().message("missing " + req.key()).build();
            }
            return HeadObjectResponse.builder().build();
        });

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            DeleteObjectRequest req = inv.getArgument(0);
            objects.remove(req.key());
            deletedKeys.add(req.key());
            return null;
        });
    }

    private static byte[] readBody(RequestBody body) throws Exception {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    // --- Transport-level behaviour -------------------------------------------------------------

    @Test
    void putFileUploadsRepeatableBodyWithLengthAndContentType(@TempDir Path tmp) throws Exception {
        byte[] raw = new byte[128 * 1024];
        new java.util.Random(7).nextBytes(raw);
        Path file = Files.write(tmp.resolve("payload.bin"), raw);

        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, false);
        URI uri = client.putFile("docs/payload.bin", file, raw.length, "application/octet-stream");

        assertEquals(URI.create("s3://migration-target/docs/payload.bin"), uri);
        assertArrayEquals(raw, objects.get("docs/payload.bin"));
        PutObjectRequest req = putRequests.get("docs/payload.bin");
        assertEquals(BUCKET, req.bucket());
        assertEquals(raw.length, req.contentLength());
        assertEquals("application/octet-stream", req.contentType());
    }

    @Test
    void putObjectWithUnknownLengthBuffersContent() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, false);
        byte[] raw = "unknown length".getBytes(StandardCharsets.UTF_8);

        URI uri = client.putObject("k.txt", new ByteArrayInputStream(raw), -1, "text/plain");

        assertEquals(URI.create("s3://migration-target/k.txt"), uri);
        assertArrayEquals(raw, objects.get("k.txt"));
    }

    @Test
    void putObjectWithKnownLengthAndNonMarkableStreamIsRepeatable() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, false);
        byte[] raw = new byte[64 * 1024];
        new java.util.Random(3).nextBytes(raw);
        InputStream nonMarkable = new java.io.FilterInputStream(new ByteArrayInputStream(raw)) {
            @Override public boolean markSupported() { return false; }
        };

        client.putObject("nm.bin", nonMarkable, raw.length, null);

        assertArrayEquals(raw, objects.get("nm.bin"));
        assertEquals(raw.length, putRequests.get("nm.bin").contentLength());
    }

    @Test
    void putTextDefaultsToJsonContentType() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, false);

        client.putText("a.ois.json", "{\"id\":\"a\"}", null);

        assertEquals("{\"id\":\"a\"}", new String(objects.get("a.ois.json"), StandardCharsets.UTF_8));
        assertEquals("application/json", putRequests.get("a.ois.json").contentType());
    }

    @Test
    void getDeleteAndExistsRoundTrip() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, false);
        client.putText("x", "hello", "text/plain");

        assertTrue(client.exists("x"));
        try (InputStream in = client.getObject("x")) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        client.deleteObject("x");
        assertFalse(client.exists("x"));
        assertEquals(List.of("x"), deletedKeys);
    }

    @Test
    void connectCreatesBucketWhenMissingAndAutoCreateEnabled() throws Exception {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("no bucket").build());

        new OzoneS3GatewayStorageClient(s3, BUCKET, true).connect();

        verify(s3).createBucket(argThat((CreateBucketRequest r) -> BUCKET.equals(r.bucket())));
    }

    @Test
    void connectSkipsBucketChecksWhenAutoCreateDisabled() throws Exception {
        new OzoneS3GatewayStorageClient(s3, BUCKET, false).connect();

        verify(s3, never()).headBucket(any(HeadBucketRequest.class));
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void connectVerifiesBucketOnlyOnceAcrossDocumentsAndThreads() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, true);

        // The connector calls connect() for every document, from several consumer threads
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> { client.connect(); return null; }));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        verify(s3, times(1)).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    void connectRetriesBucketCheckAfterFailure() throws Exception {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("gateway not ready").build())
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadBucketResponse.builder().build());
        when(s3.createBucket(any(CreateBucketRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).message("unavailable").build());
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, BUCKET, true);

        client.connect(); // head + create both fail: not cached
        client.connect(); // head succeeds: cached
        client.connect(); // no further calls

        verify(s3, times(2)).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    void blankBucketFallsBackToDefault() throws Exception {
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, " ", false);
        assertEquals(URI.create("s3://migration/k"), client.putObject("k", new ByteArrayInputStream(new byte[] {1}), 1, null));
    }

    // --- Full connector over the S3G transport -------------------------------------------------

    private OzoneOutputConnector connectorOverS3g(OzoneOutputProperties props) {
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setMode(PipelineMode.MIGRATION);
        return new OzoneOutputConnector(props, new OzoneS3GatewayStorageClient(s3, BUCKET, false), pipeline, new ObjectMapper());
    }

    private static OzoneOutputProperties s3gProperties() {
        OzoneOutputProperties props = new OzoneOutputProperties();
        props.setClientType("S3G");
        props.setVolume("s3v");
        props.setBucket(BUCKET);
        return props;
    }

    @Test
    void connectorMigratesBinarySidecarAndIndexOverS3Gateway() throws Exception {
        byte[] raw = new byte[300 * 1024];
        new java.util.Random(42).nextBytes(raw);
        String expectedSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(raw));

        OzoneOutputConnector connector = connectorOverS3g(s3gProperties());
        // Non-markable stream, like a real claim-check download: the transport must not depend on mark/reset
        InputStream nonMarkable = new java.io.FilterInputStream(new ByteArrayInputStream(raw)) {
            @Override public boolean markSupported() { return false; }
        };
        connector.send(new RepositoryDocument(
                "/crawl/contracts/c-1.pdf", "s3://claims/c-1.pdf", nonMarkable,
                Map.of("file_name", List.of("c-1.pdf"), "file_path", List.of("/crawl/contracts/c-1.pdf"),
                        "mimeType", List.of("application/pdf")),
                "", SecurityConfig.createPublic(), Instant.parse("2026-10-06T09:00:00Z"), DocumentAction.UPSERT)).block();

        String key = "crawl/contracts/c-1.pdf";
        assertArrayEquals(raw, objects.get(key));
        assertEquals("application/pdf", putRequests.get(key).contentType());
        assertEquals(raw.length, putRequests.get(key).contentLength());

        OisMigrationDocument ois = new ObjectMapper().readValue(objects.get(key + ".ois.json"), OisMigrationDocument.class);
        assertEquals("s3://migration-target/" + key, ois.uri());
        assertEquals(expectedSha, ois.contentRef().checksumSha256());
        assertEquals(raw.length, ois.contentRef().contentLength());
        assertEquals("s3://claims/c-1.pdf", ois.contentRef().claimCheckUri());

        assertEquals(key, new String(objects.get(OzoneOutputConnector.indexKey("/crawl/contracts/c-1.pdf")), StandardCharsets.UTF_8));
        assertEquals(3, objects.size(), "binary + sidecar + index entry only");
    }

    @Test
    void connectorDeleteTombstoneRemovesAllKeysOverS3Gateway() {
        OzoneOutputConnector connector = connectorOverS3g(s3gProperties());
        connector.send(new RepositoryDocument(
                "/crawl/old.txt", "s3://claims/old.txt", new ByteArrayInputStream("old".getBytes(StandardCharsets.UTF_8)),
                Map.of("file_name", List.of("old.txt"), "file_path", List.of("/crawl/old.txt")),
                "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.UPSERT)).block();
        assertEquals(3, objects.size());

        // Tombstones carry no metadata: the key must be resolved through the index entry
        connector.send(RepositoryDocument.createTombstone("/crawl/old.txt", "file:///elsewhere")).block();

        assertTrue(objects.isEmpty(), "binary, sidecar and index entry must all be deleted, left: " + objects.keySet());
    }

    @Test
    void connectorFailsDocumentWhenS3GatewayRejectsUpload() {
        reset(s3);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(500).message("internal error").build());

        OzoneOutputConnector connector = connectorOverS3g(s3gProperties());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> connector.send(new RepositoryDocument(
                "doc-err", "s3://claims/doc-err", new ByteArrayInputStream(new byte[] {1, 2}),
                Map.of("filename", List.of("e.bin")), "", Instant.now())).block());
        assertTrue(ex.getMessage().contains("doc-err"));
    }
}
