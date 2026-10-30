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
package org.opencrawling.s3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AWS S3 Output Connector dedicated to Migration Mode.
 */
@ConfigurationProperties(prefix = "spring.opencrawling.output.s3")
public class S3OutputProperties {

    private String bucket = "enterprise-migration-lake";
    private String region = "us-east-1";
    private String endpointOverride = "";
    private boolean pathStyleAccess = false;

    // Authentication strategies: DEFAULT_CHAIN, STATIC, ASSUME_ROLE
    private String credentialsStrategy = "DEFAULT_CHAIN";
    private String accessKey = "";
    private String secretKey = "";
    private String sessionToken = "";
    private String assumeRoleArn = "";
    private String assumeRoleExternalId = "";
    private String assumeRoleSessionName = "OpenCrawlingMigrationSession";

    // Key layout & naming strategies: HIERARCHICAL, FLAT, DATE_PARTITIONED
    private String keyStrategy = "HIERARCHICAL";
    private String keyPrefix = "migration/v1/";
    private String sidecarSuffix = ".ois.json";

    // Storage class: STANDARD, INTELLIGENT_TIERING, GLACIER_IR, GLACIER, DEEP_ARCHIVE, etc.
    private String storageClass = "INTELLIGENT_TIERING";

    // Server-Side Encryption: NONE, SSE_S3, SSE_KMS
    private String serverSideEncryption = "SSE_S3";
    private String kmsKeyId = "";
    private boolean bucketKeyEnabled = true;

    // S3 Object Lock & Compliance (WORM)
    private boolean objectLockEnabled = false;
    private String objectLockMode = "GOVERNANCE"; // GOVERNANCE or COMPLIANCE
    private int objectLockRetentionDays = 365;
    private boolean objectLockLegalHold = false;

    // Lifecycle & Tombstones: DELETE_OBJECTS, ARCHIVE_TOMBSTONE
    private String tombstoneAction = "DELETE_OBJECTS";
    private boolean autoCreateBucket = false;

    // Performance & Multipart
    private String multipartThreshold = "64MB";
    private String multipartPartSize = "16MB";
    private int multipartConcurrency = 4;
    private String consumerGroup = "opencrawling-s3-migration-group";
    private int consumerConcurrency = 3;

    public S3OutputProperties() {}

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointOverride() {
        return endpointOverride;
    }

    public void setEndpointOverride(String endpointOverride) {
        this.endpointOverride = endpointOverride;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getCredentialsStrategy() {
        return credentialsStrategy;
    }

    public void setCredentialsStrategy(String credentialsStrategy) {
        this.credentialsStrategy = credentialsStrategy;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getAssumeRoleArn() {
        return assumeRoleArn;
    }

    public void setAssumeRoleArn(String assumeRoleArn) {
        this.assumeRoleArn = assumeRoleArn;
    }

    public String getAssumeRoleExternalId() {
        return assumeRoleExternalId;
    }

    public void setAssumeRoleExternalId(String assumeRoleExternalId) {
        this.assumeRoleExternalId = assumeRoleExternalId;
    }

    public String getAssumeRoleSessionName() {
        return assumeRoleSessionName;
    }

    public void setAssumeRoleSessionName(String assumeRoleSessionName) {
        this.assumeRoleSessionName = assumeRoleSessionName;
    }

    public String getKeyStrategy() {
        return keyStrategy;
    }

    public void setKeyStrategy(String keyStrategy) {
        this.keyStrategy = keyStrategy;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getSidecarSuffix() {
        return sidecarSuffix;
    }

    public void setSidecarSuffix(String sidecarSuffix) {
        this.sidecarSuffix = sidecarSuffix;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    public String getServerSideEncryption() {
        return serverSideEncryption;
    }

    public void setServerSideEncryption(String serverSideEncryption) {
        this.serverSideEncryption = serverSideEncryption;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public boolean isBucketKeyEnabled() {
        return bucketKeyEnabled;
    }

    public void setBucketKeyEnabled(boolean bucketKeyEnabled) {
        this.bucketKeyEnabled = bucketKeyEnabled;
    }

    public boolean isObjectLockEnabled() {
        return objectLockEnabled;
    }

    public void setObjectLockEnabled(boolean objectLockEnabled) {
        this.objectLockEnabled = objectLockEnabled;
    }

    public String getObjectLockMode() {
        return objectLockMode;
    }

    public void setObjectLockMode(String objectLockMode) {
        this.objectLockMode = objectLockMode;
    }

    public int getObjectLockRetentionDays() {
        return objectLockRetentionDays;
    }

    public void setObjectLockRetentionDays(int objectLockRetentionDays) {
        this.objectLockRetentionDays = objectLockRetentionDays;
    }

    public boolean isObjectLockLegalHold() {
        return objectLockLegalHold;
    }

    public void setObjectLockLegalHold(boolean objectLockLegalHold) {
        this.objectLockLegalHold = objectLockLegalHold;
    }

    public String getTombstoneAction() {
        return tombstoneAction;
    }

    public void setTombstoneAction(String tombstoneAction) {
        this.tombstoneAction = tombstoneAction;
    }

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }

    public String getMultipartThreshold() {
        return multipartThreshold;
    }

    public void setMultipartThreshold(String multipartThreshold) {
        this.multipartThreshold = multipartThreshold;
    }

    public String getMultipartPartSize() {
        return multipartPartSize;
    }

    public void setMultipartPartSize(String multipartPartSize) {
        this.multipartPartSize = multipartPartSize;
    }

    public int getMultipartConcurrency() {
        return multipartConcurrency;
    }

    public void setMultipartConcurrency(int multipartConcurrency) {
        this.multipartConcurrency = multipartConcurrency;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
    }

    /**
     * Resolves multipart threshold in bytes. Defaults to 64 MiB if unparseable.
     */
    public long resolveMultipartThresholdBytes() {
        return parseBytes(multipartThreshold, 64L * 1024 * 1024);
    }

    /**
     * Resolves multipart part size in bytes. Defaults to 16 MiB (minimum S3 requirement is 5 MiB).
     */
    public long resolveMultipartPartSizeBytes() {
        long size = parseBytes(multipartPartSize, 16L * 1024 * 1024);
        return Math.max(size, 5L * 1024 * 1024); // S3 requires at least 5 MiB
    }

    public static long parseBytes(String val, long fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        String clean = val.trim().toUpperCase();
        try {
            if (clean.endsWith("GB") || clean.endsWith("GIB")) {
                return (long) (Double.parseDouble(clean.replaceAll("[^0-9.]", "")) * 1024 * 1024 * 1024);
            }
            if (clean.endsWith("MB") || clean.endsWith("MIB")) {
                return (long) (Double.parseDouble(clean.replaceAll("[^0-9.]", "")) * 1024 * 1024);
            }
            if (clean.endsWith("KB") || clean.endsWith("KIB")) {
                return (long) (Double.parseDouble(clean.replaceAll("[^0-9.]", "")) * 1024);
            }
            if (clean.endsWith("B")) {
                return Long.parseLong(clean.replaceAll("[^0-9]", ""));
            }
            return Long.parseLong(clean);
        } catch (Exception e) {
            return fallback;
        }
    }
}
