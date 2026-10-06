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
package org.opencrawling.ozone.config;

import org.opencrawling.core.s3.S3MultipartUploader;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for Apache Ozone Output Connector.
 */
@ConfigurationProperties(prefix = "spring.opencrawling.output.ozone")
public class OzoneOutputProperties {

    /**
     * Client strategy type: NATIVE (ofs/RPC) or S3G (S3 Gateway HTTP).
     */
    private String clientType = "NATIVE";

    /**
     * Ozone target volume name.
     */
    private String volume = "s3v";

    /**
     * Ozone target bucket name.
     */
    private String bucket = "migration";

    /**
     * Ozone Manager host (for NATIVE RPC).
     */
    private String omHost = "localhost";

    /**
     * Ozone Manager port (for NATIVE RPC).
     */
    private int omPort = 9862;

    /**
     * S3 Gateway endpoint URL (for S3G).
     */
    private String s3Endpoint = "http://localhost:9878";

    /**
     * S3 Gateway access key.
     */
    private String accessKey = "any";

    /**
     * S3 Gateway secret key.
     */
    private String secretKey = "any";

    /**
     * Whether to auto-create volume/bucket if missing.
     */
    private boolean autoCreateBucket = true;

    /**
     * Suffix used for companion OIS JSON metadata sidecar files.
     */
    private String sidecarSuffix = ".ois.json";

    /**
     * Action to perform on OIS DELETE tombstone: DELETE_KEY or ARCHIVE_TOMBSTONE.
     */
    private String tombstoneAction = "DELETE_KEY";

    /**
     * Key naming strategy: HIERARCHICAL (preserves source paths) or FLAT (id_name).
     */
    private String keyStrategy = "HIERARCHICAL";

    /**
     * NATIVE client only: buffer objects in memory when the Ozone Manager is unreachable.
     * Intended for local development and unit tests ONLY — in-memory objects are lost on restart,
     * so this must stay {@code false} for real migrations (connection failures then fail the document).
     */
    private boolean allowInMemoryFallback = false;

    public boolean isAllowInMemoryFallback() {
        return allowInMemoryFallback;
    }

    public void setAllowInMemoryFallback(boolean allowInMemoryFallback) {
        this.allowInMemoryFallback = allowInMemoryFallback;
    }

    /**
     * Number of parallel Kafka consumer threads of the decoupled migration writer. Effective
     * parallelism is capped by the partitions of the documents topic
     * ({@code spring.opencrawling.kafka.topic.partitions}). Per-document ordering is preserved.
     */
    private int consumerConcurrency = 3;

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
    }

    /**
     * S3G client only: binaries of at least this size are uploaded with a parallel multipart upload
     * (default {@code 256MB}); {@code 0} disables multipart. The NATIVE client always streams blocks directly.
     */
    private DataSize multipartThreshold = DataSize.ofBytes(S3MultipartUploader.DEFAULT_THRESHOLD);

    /** S3G client only: multipart part size, at least {@code 5MB}. */
    private DataSize multipartPartSize = DataSize.ofBytes(S3MultipartUploader.DEFAULT_PART_SIZE);

    /** S3G client only: parallel part uploads per object. */
    private int multipartConcurrency = S3MultipartUploader.DEFAULT_PART_CONCURRENCY;

    public DataSize getMultipartThreshold() {
        return multipartThreshold;
    }

    public void setMultipartThreshold(DataSize multipartThreshold) {
        this.multipartThreshold = multipartThreshold;
    }

    public DataSize getMultipartPartSize() {
        return multipartPartSize;
    }

    public void setMultipartPartSize(DataSize multipartPartSize) {
        this.multipartPartSize = multipartPartSize;
    }

    public int getMultipartConcurrency() {
        return multipartConcurrency;
    }

    public void setMultipartConcurrency(int multipartConcurrency) {
        this.multipartConcurrency = multipartConcurrency;
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getOmHost() {
        return omHost;
    }

    public void setOmHost(String omHost) {
        this.omHost = omHost;
    }

    public int getOmPort() {
        return omPort;
    }

    public void setOmPort(int omPort) {
        this.omPort = omPort;
    }

    public String getS3Endpoint() {
        return s3Endpoint;
    }

    public void setS3Endpoint(String s3Endpoint) {
        this.s3Endpoint = s3Endpoint;
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

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }

    public String getSidecarSuffix() {
        return sidecarSuffix;
    }

    public void setSidecarSuffix(String sidecarSuffix) {
        this.sidecarSuffix = sidecarSuffix;
    }

    public String getTombstoneAction() {
        return tombstoneAction;
    }

    public void setTombstoneAction(String tombstoneAction) {
        this.tombstoneAction = tombstoneAction;
    }

    public String getKeyStrategy() {
        return keyStrategy;
    }

    public void setKeyStrategy(String keyStrategy) {
        this.keyStrategy = keyStrategy;
    }
}
