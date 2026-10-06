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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Uploads objects to an S3-compatible endpoint (e.g. the Apache Ozone S3 Gateway), switching to a parallel
 * multipart upload for large objects.
 *
 * <ul>
 *   <li>Objects smaller than {@code threshold} use a single {@code PutObject}.</li>
 *   <li>Larger objects are split into {@code partSize} parts uploaded by up to {@code partConcurrency} threads.
 *       If any part fails the upload is aborted, so no orphan parts are left on the gateway.</li>
 *   <li>Every request body is repeatable (bytes or a file slice re-opened on demand): the AWS SDK re-reads
 *       bodies for checksums and retries, and a consumed {@link InputStream} would be sent as 0 bytes.</li>
 * </ul>
 *
 * <p>Thread-safe: one instance can be shared by concurrent callers.
 */
public class S3MultipartUploader {

    private static final Logger log = LoggerFactory.getLogger(S3MultipartUploader.class);

    /** S3 minimum size of every part except the last one. */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    /** S3 maximum number of parts per upload. */
    public static final int MAX_PARTS = 10_000;
    /** Streams up to this size are buffered in memory; larger ones are spooled to a temp file. */
    static final int MEMORY_BUFFER_LIMIT = 8 * 1024 * 1024;

    /**
     * Multipart is mainly for robustness on very large objects (bounded call duration, per-part retries); for
     * smaller objects a single PUT was faster in measurements, hence the high default.
     */
    public static final long DEFAULT_THRESHOLD = 256L * 1024 * 1024;
    public static final long DEFAULT_PART_SIZE = 16L * 1024 * 1024;
    public static final int DEFAULT_PART_CONCURRENCY = 4;

    private final S3Client s3Client;
    private final long threshold;
    private final long partSize;
    private final int partConcurrency;

    public S3MultipartUploader(S3Client s3Client) {
        this(s3Client, DEFAULT_THRESHOLD, DEFAULT_PART_SIZE, DEFAULT_PART_CONCURRENCY);
    }

    /**
     * @param threshold       object size from which multipart is used; {@code <= 0} disables multipart
     * @param partSize        part size, at least {@link #MIN_PART_SIZE}
     * @param partConcurrency parallel part uploads per object, at least 1
     */
    public S3MultipartUploader(S3Client s3Client, long threshold, long partSize, int partConcurrency) {
        if (partSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("Multipart part size must be at least 5 MiB, was " + partSize);
        }
        this.s3Client = s3Client;
        this.threshold = threshold;
        this.partSize = partSize;
        this.partConcurrency = Math.max(1, partConcurrency);
    }

    public boolean isMultipart(long contentLength) {
        return threshold > 0 && contentLength >= threshold;
    }

    /** Uploads a file: single PUT below the threshold, parallel multipart above it. */
    public void uploadFile(String bucket, String key, Path file, String contentType) throws IOException {
        long length = Files.size(file);
        if (!isMultipart(length)) {
            PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(key).contentLength(length);
            if (contentType != null && !contentType.isBlank()) {
                put.contentType(contentType);
            }
            s3Client.putObject(put.build(), RequestBody.fromFile(file));
            return;
        }
        uploadMultipart(bucket, key, file, length, contentType);
    }

    /**
     * Uploads a stream of unknown length. Small streams are buffered in memory; larger ones are spooled to a temp
     * file (deleted afterwards) and uploaded with {@link #uploadFile}, so memory stays bounded for any object size.
     *
     * @return the number of bytes uploaded
     */
    public long uploadStream(String bucket, String key, InputStream content, String contentType) throws IOException {
        byte[] head = content.readNBytes(MEMORY_BUFFER_LIMIT);
        if (head.length < MEMORY_BUFFER_LIMIT) {
            PutObjectRequest.Builder put = PutObjectRequest.builder().bucket(bucket).key(key).contentLength((long) head.length);
            if (contentType != null && !contentType.isBlank()) {
                put.contentType(contentType);
            }
            s3Client.putObject(put.build(), RequestBody.fromBytes(head));
            return head.length;
        }
        Path spool = Files.createTempFile("oc-s3-upload-", ".bin");
        try {
            try (OutputStream out = Files.newOutputStream(spool, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(head);
                content.transferTo(out);
            }
            uploadFile(bucket, key, spool, contentType);
            return Files.size(spool);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private void uploadMultipart(String bucket, String key, Path file, long length, String contentType) throws IOException {
        // Grow the part size if needed to stay within the S3 limit of 10,000 parts.
        long effectivePartSize = Math.max(partSize, (length + MAX_PARTS - 1) / MAX_PARTS);
        int partCount = (int) ((length + effectivePartSize - 1) / effectivePartSize);

        CreateMultipartUploadRequest.Builder create = CreateMultipartUploadRequest.builder().bucket(bucket).key(key);
        if (contentType != null && !contentType.isBlank()) {
            create.contentType(contentType);
        }
        String uploadId = s3Client.createMultipartUpload(create.build()).uploadId();
        log.info("Multipart upload of s3://{}/{} ({} bytes, {} parts of {} bytes, {} in parallel)",
                bucket, key, length, partCount, effectivePartSize, Math.min(partConcurrency, partCount));

        try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(partConcurrency, partCount),
                Thread.ofVirtual().name("oc-s3-part-", 0).factory())) {
            List<Future<CompletedPart>> futures = new ArrayList<>(partCount);
            for (int i = 0; i < partCount; i++) {
                int partNumber = i + 1;
                long offset = i * effectivePartSize;
                long size = Math.min(effectivePartSize, length - offset);
                futures.add(executor.submit(() -> uploadPart(bucket, key, uploadId, partNumber, file, offset, size)));
            }
            List<CompletedPart> parts = new ArrayList<>(partCount);
            try {
                for (Future<CompletedPart> future : futures) {
                    parts.add(future.get());
                }
            } catch (Exception e) {
                futures.forEach(f -> f.cancel(true)); // stop uploading the remaining parts
                throw e;
            }
            parts.sort(Comparator.comparingInt(CompletedPart::partNumber));
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (Exception e) {
            abort(bucket, key, uploadId);
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("Multipart upload failed for s3://" + bucket + "/" + key, cause);
        }
    }

    private CompletedPart uploadPart(String bucket, String key, String uploadId, int partNumber,
            Path file, long offset, long size) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(size)
                .build();
        // Re-opens the file slice on every read: repeatable for SDK checksums and retries, no part held in memory.
        RequestBody body = RequestBody.fromContentProvider(() -> openSlice(file, offset, size), size,
                "application/octet-stream");
        UploadPartResponse response = s3Client.uploadPart(request, body);
        return CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build();
    }

    static InputStream openSlice(Path file, long offset, long size) {
        try {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            channel.position(offset);
            return new BoundedInputStream(Channels.newInputStream(channel), size);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void abort(String bucket, String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
            log.warn("Aborted multipart upload {} of s3://{}/{}", uploadId, bucket, key);
        } catch (Exception ex) {
            log.warn("Could not abort multipart upload {} of s3://{}/{}: {}", uploadId, bucket, key, ex.getMessage());
        }
    }

    /** Reads at most {@code remaining} bytes from the delegate. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
