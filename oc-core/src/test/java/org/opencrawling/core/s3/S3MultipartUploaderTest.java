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
package org.opencrawling.core.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class S3MultipartUploaderTest {

    private static final int MB = 1024 * 1024;

    @TempDir
    Path tmp;

    private S3Client s3;
    /** partNumber -> bytes received (read twice to prove the body is repeatable). */
    private final Map<Integer, byte[]> receivedParts = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                UploadPartRequest req = inv.getArgument(0);
                RequestBody body = inv.getArgument(1);
                byte[] first = readAll(body);
                byte[] second = readAll(body);
                assertArrayEquals(first, second, "part body must be repeatable");
                assertEquals(req.contentLength().longValue(), first.length);
                receivedParts.put(req.partNumber(), first);
                Thread.sleep(20);
                return UploadPartResponse.builder().eTag("etag-" + req.partNumber()).build();
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    @Test
    void testSmallFileUsesSinglePut() throws IOException {
        Path file = randomFile(1 * MB);
        new S3MultipartUploader(s3, 8L * MB, 5L * MB, 4).uploadFile("b", "k", file, "text/plain");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertEquals("text/plain", put.getValue().contentType());
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void testLargeFileIsUploadedInParallelPartsAndReassemblesExactly() throws IOException {
        Path file = randomFile(23 * MB); // 5 parts of 5 MiB: 4 full + 3 MiB tail
        new S3MultipartUploader(s3, 8L * MB, 5L * MB, 3).uploadFile("b", "big.bin", file, "application/pdf");

        ArgumentCaptor<CreateMultipartUploadRequest> create = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(create.capture());
        assertEquals("application/pdf", create.getValue().contentType());

        assertEquals(5, receivedParts.size());
        assertEquals(3 * MB, receivedParts.get(5).length);
        byte[] reassembled = new byte[0];
        for (int i = 1; i <= 5; i++) {
            byte[] part = receivedParts.get(i);
            byte[] joined = Arrays.copyOf(reassembled, reassembled.length + part.length);
            System.arraycopy(part, 0, joined, reassembled.length, part.length);
            reassembled = joined;
        }
        assertArrayEquals(Files.readAllBytes(file), reassembled, "parts must reassemble to the original bytes");

        assertTrue(maxInFlight.get() > 1, "parts should upload in parallel, max was " + maxInFlight.get());
        assertTrue(maxInFlight.get() <= 3, "part concurrency must be bounded, max was " + maxInFlight.get());

        ArgumentCaptor<CompleteMultipartUploadRequest> complete = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(complete.capture());
        List<CompletedPart> parts = complete.getValue().multipartUpload().parts();
        assertEquals(List.of(1, 2, 3, 4, 5), parts.stream().map(CompletedPart::partNumber).toList());
        assertEquals("etag-3", parts.get(2).eTag());
        assertEquals("upload-1", complete.getValue().uploadId());
    }

    @Test
    void testFailedPartAbortsTheUpload() throws IOException {
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("gateway 503"));
        Path file = randomFile(12 * MB);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new S3MultipartUploader(s3, 8L * MB, 5L * MB, 2).uploadFile("b", "k", file, null));
        assertEquals("gateway 503", e.getMessage());

        ArgumentCaptor<AbortMultipartUploadRequest> abort = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3).abortMultipartUpload(abort.capture());
        assertEquals("upload-1", abort.getValue().uploadId());
        verify(s3, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test
    void testSmallStreamIsBufferedInMemory() throws IOException {
        byte[] data = new byte[1000];
        new Random(1).nextBytes(data);
        long n = new S3MultipartUploader(s3, 8L * MB, 5L * MB, 2)
                .uploadStream("b", "k", nonMarkable(data), null);

        assertEquals(1000, n);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(any(PutObjectRequest.class), body.capture());
        assertArrayEquals(data, readAll(body.getValue()));
        assertArrayEquals(data, readAll(body.getValue()), "body must be repeatable");
    }

    @Test
    void testLargeStreamOfUnknownLengthIsSpooledAndUploadedMultipart() throws IOException {
        byte[] data = new byte[20 * MB];
        new Random(2).nextBytes(data);
        long n = new S3MultipartUploader(s3, 16L * MB, 5L * MB, 4)
                .uploadStream("b", "k", nonMarkable(data), null);

        assertEquals(data.length, n);
        assertEquals(4, receivedParts.size());
        assertArrayEquals(Arrays.copyOfRange(data, 15 * MB, 20 * MB), receivedParts.get(4));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testZeroThresholdDisablesMultipart() throws IOException {
        S3MultipartUploader uploader = new S3MultipartUploader(s3, 0, 5L * MB, 4);
        assertFalse(uploader.isMultipart(Long.MAX_VALUE));
        uploader.uploadFile("b", "k", randomFile(12 * MB), null);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    void testPartSizeBelowS3MinimumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new S3MultipartUploader(s3, 8L * MB, 1L * MB, 4));
    }

    private Path randomFile(int size) throws IOException {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return Files.write(tmp.resolve("f-" + size + ".bin"), data);
    }

    private static InputStream nonMarkable(byte[] data) {
        return new InputStream() {
            private final ByteArrayInputStream in = new ByteArrayInputStream(data);

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }
        };
    }

    private static byte[] readAll(RequestBody body) throws IOException {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }
}
