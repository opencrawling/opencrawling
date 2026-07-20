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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apache Ozone / S3 Object Storage implementation of ClaimCheckStore.
 * Connects to Apache Ozone via its S3 Gateway (s3g) endpoint or any standard S3 compatible object store.
 */
public class OzoneClaimCheckStore implements ClaimCheckStore {

    private static final Logger log = LoggerFactory.getLogger(OzoneClaimCheckStore.class);

    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);
    private final S3Client s3Client;
    private final String bucket;
    private final boolean autoCreateBucket;

    public OzoneClaimCheckStore(ClaimCheckProperties.Ozone ozoneProps) {
        this(createS3Client(ozoneProps), ozoneProps.getBucket(), ozoneProps.isAutoCreateBucket());
    }

    public OzoneClaimCheckStore(S3Client s3Client, String bucket, boolean autoCreateBucket) {
        this.s3Client = s3Client;
        this.bucket = bucket != null && !bucket.isBlank() ? bucket : "claims";
        this.autoCreateBucket = autoCreateBucket;
    }

    private static S3Client createS3Client(ClaimCheckProperties.Ozone ozoneProps) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(ozoneProps.getRegion() != null ? ozoneProps.getRegion() : "us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                ozoneProps.getAccessKey() != null ? ozoneProps.getAccessKey() : "ozone",
                                ozoneProps.getSecretKey() != null ? ozoneProps.getSecretKey() : "ozone-secret"
                        )))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(ozoneProps.isPathStyleAccess())
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .build());

        if (ozoneProps.getS3Endpoint() != null && !ozoneProps.getS3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(ozoneProps.getS3Endpoint()));
        }

        return builder.build();
    }

    private void ensureBucketInitialized() {
        if (autoCreateBucket && !bucketInitialized.get()) {
            synchronized (this) {
                if (!bucketInitialized.get()) {
                    initBucket();
                    bucketInitialized.set(true);
                }
            }
        }
    }

    private void initBucket() {
        if (s3Client == null) {
            return;
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Verified Apache Ozone / S3 claim check bucket exists: {}", bucket);
        } catch (Throwable e) {
            log.debug("Bucket {} head check failed (Ozone S3 Gateway may be offline): {}", bucket, e.getMessage());
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created Apache Ozone / S3 claim check bucket: {}", bucket);
            } catch (Throwable ex) {
                log.debug("Could not auto-create bucket {}: {}", bucket, ex.getMessage());
            }
        }
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        ensureBucketInitialized();
        String safeKey = id.replaceAll("[^a-zA-Z0-9.-]", "_");
        PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(safeKey);

        if (contentType != null && !contentType.isBlank()) {
            putBuilder.contentType(contentType);
        }

        RequestBody requestBody;
        if (contentLength > 0) {
            requestBody = RequestBody.fromInputStream(content, contentLength);
        } else {
            byte[] bytes = content.readAllBytes();
            requestBody = RequestBody.fromBytes(bytes);
        }

        s3Client.putObject(putBuilder.build(), requestBody);

        URI uri = URI.create("s3://" + bucket + "/" + safeKey);
        log.info("Uploaded claim check object to Apache Ozone / S3: {}", uri);
        return uri;
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        ParsedS3Uri parsed = ParsedS3Uri.parse(claimCheckUri, bucket);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(parsed.bucket())
                .key(parsed.key())
                .build();
        return s3Client.getObject(request);
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        ParsedS3Uri parsed = ParsedS3Uri.parse(claimCheckUri, bucket);
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(parsed.bucket())
                .key(parsed.key())
                .build();
        s3Client.deleteObject(request);
        log.info("Deleted claim check object from Apache Ozone / S3: {}", claimCheckUri);
    }

    @Override
    public boolean supports(URI claimCheckUri) {
        if (claimCheckUri == null) {
            return false;
        }
        String scheme = claimCheckUri.getScheme();
        return "s3".equalsIgnoreCase(scheme) || "ofs".equalsIgnoreCase(scheme);
    }

    private record ParsedS3Uri(String bucket, String key) {
        static ParsedS3Uri parse(URI uri, String defaultBucket) {
            if (uri == null) {
                throw new IllegalArgumentException("Claim check URI cannot be null");
            }
            String host = uri.getHost();
            String path = uri.getPath();

            String bucket = (host != null && !host.isBlank()) ? host : defaultBucket;
            String key = (path != null && path.length() > 1) ? path.substring(1) : path;
            if (key != null && key.startsWith("/")) {
                key = key.substring(1);
            }
            return new ParsedS3Uri(bucket, key);
        }
    }
}
