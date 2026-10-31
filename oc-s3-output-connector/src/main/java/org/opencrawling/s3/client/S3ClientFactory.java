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
package org.opencrawling.s3.client;

import org.opencrawling.s3.config.S3OutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.net.URI;

/**
 * Factory responsible for initializing AWS S3 and STS clients based on enterprise configuration.
 */
public class S3ClientFactory {

    private static final Logger log = LoggerFactory.getLogger(S3ClientFactory.class);

    private final S3OutputProperties properties;
    private StsClient stsClient;

    public S3ClientFactory(S3OutputProperties properties) {
        this.properties = properties != null ? properties : new S3OutputProperties();
    }

    public S3Client createS3Client() {
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider();

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentialsProvider);

        if (properties.getEndpointOverride() != null && !properties.getEndpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpointOverride()));
        }

        builder.forcePathStyle(properties.isPathStyleAccess());

        S3Client client = builder.build();

        if (properties.isAutoCreateBucket()) {
            ensureBucketExists(client, properties.getBucket());
        }

        return client;
    }

    public AwsCredentialsProvider resolveCredentialsProvider() {
        String strategy = properties.getCredentialsStrategy() != null
                ? properties.getCredentialsStrategy().trim().toUpperCase()
                : "DEFAULT_CHAIN";

        switch (strategy) {
            case "STATIC":
                log.info("Configuring S3 static credentials provider (accessKey: {})", maskKey(properties.getAccessKey()));
                if (properties.getSessionToken() != null && !properties.getSessionToken().isBlank()) {
                    return StaticCredentialsProvider.create(AwsSessionCredentials.create(
                            properties.getAccessKey(),
                            properties.getSecretKey(),
                            properties.getSessionToken()
                    ));
                }
                return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.getAccessKey(),
                        properties.getSecretKey()
                ));

            case "ASSUME_ROLE":
                log.info("Configuring S3 STS AssumeRole credentials provider for Role ARN: {}", properties.getAssumeRoleArn());
                AwsCredentialsProvider baseProvider;
                if (properties.getAccessKey() != null && !properties.getAccessKey().isBlank()) {
                    baseProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            properties.getAccessKey(),
                            properties.getSecretKey()
                    ));
                } else {
                    baseProvider = DefaultCredentialsProvider.create();
                }

                StsClientBuilder stsBuilder = StsClient.builder()
                        .region(Region.of(properties.getRegion()))
                        .credentialsProvider(baseProvider);

                if (properties.getEndpointOverride() != null && !properties.getEndpointOverride().isBlank()) {
                    stsBuilder.endpointOverride(URI.create(properties.getEndpointOverride()));
                }

                this.stsClient = stsBuilder.build();

                AssumeRoleRequest.Builder assumeBuilder = AssumeRoleRequest.builder()
                        .roleArn(properties.getAssumeRoleArn())
                        .roleSessionName(properties.getAssumeRoleSessionName() != null && !properties.getAssumeRoleSessionName().isBlank()
                                ? properties.getAssumeRoleSessionName()
                                : "OpenCrawlingMigrationSession");

                if (properties.getAssumeRoleExternalId() != null && !properties.getAssumeRoleExternalId().isBlank()) {
                    assumeBuilder.externalId(properties.getAssumeRoleExternalId());
                }

                return StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(this.stsClient)
                        .refreshRequest(assumeBuilder.build())
                        .build();

            case "DEFAULT_CHAIN":
            default:
                log.info("Configuring S3 DefaultCredentialsProvider chain (IRSA / IAM / Env / Profile)");
                return DefaultCredentialsProvider.create();
        }
    }

    public void ensureBucketExists(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Target S3 bucket '{}' verified.", bucket);
        } catch (NoSuchBucketException e) {
            log.info("Target S3 bucket '{}' does not exist. Creating bucket...", bucket);
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created S3 bucket '{}' successfully.", bucket);
            } catch (Exception createEx) {
                log.warn("Failed to create S3 bucket '{}': {}", bucket, createEx.getMessage());
            }
        } catch (S3Exception s3Ex) {
            if (s3Ex.statusCode() == 404) {
                log.info("Target S3 bucket '{}' not found (404). Creating bucket...", bucket);
                try {
                    client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created S3 bucket '{}' successfully.", bucket);
                } catch (Exception createEx) {
                    log.warn("Failed to create S3 bucket '{}': {}", bucket, createEx.getMessage());
                }
            } else {
                log.debug("HeadBucket returned status {}: {}", s3Ex.statusCode(), s3Ex.getMessage());
            }
        } catch (Exception ex) {
            log.debug("HeadBucket check failed: {}", ex.getMessage());
        }
    }

    public void close() {
        if (stsClient != null) {
            try {
                stsClient.close();
            } catch (Exception ignored) {}
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 4) return "****";
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }
}
