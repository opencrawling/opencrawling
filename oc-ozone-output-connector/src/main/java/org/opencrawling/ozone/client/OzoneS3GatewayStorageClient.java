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
package org.opencrawling.ozone.client;

import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Apache Ozone S3 Gateway HTTP Client strategy using standard AWS S3 SDK.
 */
public class OzoneS3GatewayStorageClient implements OzoneStorageClient {

    private static final Logger log = LoggerFactory.getLogger(OzoneS3GatewayStorageClient.class);

    private final S3Client s3Client;
    private final String bucket;
    private final boolean autoCreateBucket;

    public OzoneS3GatewayStorageClient(OzoneOutputProperties properties) {
        this(createS3Client(properties), properties.getBucket(), properties.isAutoCreateBucket());
    }

    public OzoneS3GatewayStorageClient(S3Client s3Client, String bucket, boolean autoCreateBucket) {
        this.s3Client = s3Client;
        this.bucket = bucket != null && !bucket.isBlank() ? bucket : "migration";
        this.autoCreateBucket = autoCreateBucket;
    }

    private static S3Client createS3Client(OzoneOutputProperties properties) {
        String endpoint = properties.getS3Endpoint() != null ? properties.getS3Endpoint() : "http://localhost:9878";
        String accessKey = properties.getAccessKey() != null ? properties.getAccessKey() : "any";
        String secretKey = properties.getSecretKey() != null ? properties.getSecretKey() : "any";

        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

        return builder.build();
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Apache Ozone via S3 Gateway for bucket '{}'...", bucket);
        if (autoCreateBucket) {
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                log.info("Ozone S3 bucket '{}' verified.", bucket);
            } catch (Exception e) {
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created Ozone S3 bucket '{}'.", bucket);
                } catch (Exception ex) {
                    log.debug("Bucket '{}' check/creation notice: {}", bucket, ex.getMessage());
                }
            }
        }
    }

    @Override
    public URI putObject(String key, InputStream content, long contentLength, String contentType) throws Exception {
        byte[] bytes = content.readAllBytes();
        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key);
        if (contentType != null && !contentType.isBlank()) {
            requestBuilder.contentType(contentType);
        }

        s3Client.putObject(requestBuilder.build(), RequestBody.fromBytes(bytes));
        URI uri = URI.create("s3://" + bucket + "/" + key);
        log.info("Saved binary to Apache Ozone S3 Gateway: {} ({} bytes)", uri, bytes.length);
        return uri;
    }

    @Override
    public void putText(String key, String content, String contentType) throws Exception {
        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/json");

        s3Client.putObject(requestBuilder.build(), RequestBody.fromString(content, StandardCharsets.UTF_8));
        log.info("Saved metadata sidecar to Apache Ozone S3 Gateway: s3://{}/{}", bucket, key);
    }

    @Override
    public InputStream getObject(String key) throws Exception {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deleteObject(String key) throws Exception {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("Deleted object from Apache Ozone S3 Gateway: s3://{}/{}", bucket, key);
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}
