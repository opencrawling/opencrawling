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
package org.opencrawling.core.claimcheck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the S3 Gateway claim-check transport with a mocked, in-memory {@link S3Client}.
 * Upload bodies are read twice inside the mock (as the AWS SDK does for checksums and retries), so a
 * non-repeatable request body fails here instead of only against a live Ozone S3 Gateway.
 */
class OzoneS3GatewayClientStrategyTest {

    private static final String BUCKET = "claims";

    /** bucket + "/" + key -> content */
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> deleted = new ArrayList<>();
    private S3Client s3;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            byte[] first = read(body);
            byte[] second = read(body);
            assertThat(second).as("S3 request body must be repeatable").isEqualTo(first);
            objects.put(req.bucket() + "/" + req.key(), first);
            return PutObjectResponse.builder().build();
        });
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            byte[] data = objects.get(req.bucket() + "/" + req.key());
            if (data == null) {
                throw NoSuchKeyException.builder().message("missing").build();
            }
            return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream(data)));
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            DeleteObjectRequest req = inv.getArgument(0);
            objects.remove(req.bucket() + "/" + req.key());
            deleted.add(req.bucket() + "/" + req.key());
            return null;
        });
    }

    private static byte[] read(RequestBody body) throws Exception {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    private static InputStream nonMarkable(byte[] data) {
        return new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override public boolean markSupported() { return false; }
        };
    }

    @Test
    void putWithNonMarkableStreamIsRepeatableAndSanitizesKey() throws Exception {
        byte[] raw = new byte[200 * 1024];
        new java.util.Random(11).nextBytes(raw);
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);

        URI uri = strategy.put("/crawl/a b/report.pdf", nonMarkable(raw), raw.length, "application/pdf");

        assertThat(uri).isEqualTo(URI.create("s3://claims/_crawl_a_b_report.pdf"));
        assertThat(objects.get("claims/_crawl_a_b_report.pdf")).isEqualTo(raw);
    }

    @Test
    void putWithInMemoryStreamAndWithUnknownLength() throws Exception {
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);
        byte[] raw = "claim".getBytes(StandardCharsets.UTF_8);

        strategy.put("in-memory", new ByteArrayInputStream(raw), raw.length, null);
        strategy.put("unknown", nonMarkable(raw), -1, null);

        assertThat(objects.get("claims/in-memory")).isEqualTo(raw);
        assertThat(objects.get("claims/unknown")).isEqualTo(raw);
    }

    @Test
    void getAndDeleteResolveBucketFromUriOrDefault() throws Exception {
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);
        URI stored = strategy.put("doc-1", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, null);
        objects.put("other/doc-2", "y".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = strategy.get(stored)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("x");
        }
        try (InputStream in = strategy.get(URI.create("s3://other/doc-2"))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("y");
        }

        strategy.delete(stored);
        assertThat(deleted).containsExactly("claims/doc-1");
        assertThatThrownBy(() -> strategy.get(stored)).isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void uploadFailureIsPropagated() {
        reset(s3);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("Unable to allocate a container").build());
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);

        assertThatThrownBy(() -> strategy.put("doc", nonMarkable(new byte[] {1, 2, 3}), 3, null))
                .isInstanceOf(S3Exception.class)
                .hasMessageContaining("Unable to allocate a container");
    }

    @Test
    void bucketIsVerifiedOrCreatedOnlyOnce() throws Exception {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("no bucket").build());
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, true);

        strategy.put("a", new ByteArrayInputStream(new byte[] {1}), 1, null);
        strategy.put("b", new ByteArrayInputStream(new byte[] {2}), 1, null);

        verify(s3, times(1)).headBucket(any(HeadBucketRequest.class));
        verify(s3, times(1)).createBucket(argThat((CreateBucketRequest r) -> BUCKET.equals(r.bucket())));
    }

    @Test
    void deleteExpiredRemovesOnlyObjectsOlderThanMaxAge() throws Exception {
        Instant now = Instant.now();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("old").lastModified(now.minus(Duration.ofHours(48))).build(),
                        S3Object.builder().key("fresh").lastModified(now.minus(Duration.ofMinutes(5))).build())
                .build());
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);

        int removed = strategy.deleteExpired(Duration.ofHours(24));

        assertThat(removed).isEqualTo(1);
        assertThat(deleted).containsExactly("claims/old");
    }

    @Test
    void supportsOnlyS3Scheme() {
        OzoneS3GatewayClientStrategy strategy = new OzoneS3GatewayClientStrategy(s3, BUCKET, false);
        assertThat(strategy.supports(URI.create("s3://claims/x"))).isTrue();
        assertThat(strategy.supports(URI.create("ofs://s3v/claims/x"))).isFalse();
        assertThat(strategy.supports(URI.create("file:///data/x"))).isFalse();
        assertThat(strategy.supports(null)).isFalse();
    }
}
