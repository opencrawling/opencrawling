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
package org.opencrawling.iceberg.output.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.opencrawling.output.iceberg")
public class IcebergOutputProperties {

    public enum TableStrategy {
        UNIFIED,
        PER_DOCUMENT_TYPE
    }

    public enum PartitionStrategy {
        NONE,
        YEAR_MONTH,
        SOURCE_AND_DATE
    }

    private String catalogType = "in-memory";
    private String catalogUri;
    private String catalogName = "enterprise_lakehouse";
    private String warehouse = "tmp/iceberg-warehouse";
    private String namespace = "opencrawling_ecm";
    private String tableName = "enterprise_documents";
    private TableStrategy tableStrategy = TableStrategy.UNIFIED;
    private boolean autoCreateTable = true;
    private boolean autoSchemaEvolution = true;
    private PartitionStrategy partitionBy = PartitionStrategy.YEAR_MONTH;
    private String writeFormat = "parquet";
    private String compressionCodec = "zstd";
    private int batchSize = 500;
    private long flushIntervalMs = 5000L;
    private String consumerGroup = "opencrawling-iceberg-group";
    private int consumerConcurrency = 3;
    private String ioImpl;

    private S3Properties s3 = new S3Properties();
    private EntityExtractionProperties entityExtraction = new EntityExtractionProperties();

    public String getCatalogType() {
        return catalogType;
    }

    public void setCatalogType(String catalogType) {
        this.catalogType = catalogType;
    }

    public String getCatalogUri() {
        return catalogUri;
    }

    public void setCatalogUri(String catalogUri) {
        this.catalogUri = catalogUri;
    }

    public String getCatalogName() {
        return catalogName;
    }

    public void setCatalogName(String catalogName) {
        this.catalogName = catalogName;
    }

    public String getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(String warehouse) {
        this.warehouse = warehouse;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public TableStrategy getTableStrategy() {
        return tableStrategy;
    }

    public void setTableStrategy(TableStrategy tableStrategy) {
        this.tableStrategy = tableStrategy;
    }

    public boolean isAutoCreateTable() {
        return autoCreateTable;
    }

    public void setAutoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
    }

    public boolean isAutoSchemaEvolution() {
        return autoSchemaEvolution;
    }

    public void setAutoSchemaEvolution(boolean autoSchemaEvolution) {
        this.autoSchemaEvolution = autoSchemaEvolution;
    }

    public PartitionStrategy getPartitionBy() {
        return partitionBy;
    }

    public void setPartitionBy(PartitionStrategy partitionBy) {
        this.partitionBy = partitionBy;
    }

    public String getWriteFormat() {
        return writeFormat;
    }

    public void setWriteFormat(String writeFormat) {
        this.writeFormat = writeFormat;
    }

    public String getCompressionCodec() {
        return compressionCodec;
    }

    public void setCompressionCodec(String compressionCodec) {
        this.compressionCodec = compressionCodec;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
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

    public String getIoImpl() {
        return ioImpl;
    }

    public void setIoImpl(String ioImpl) {
        this.ioImpl = ioImpl;
    }

    public S3Properties getS3() {
        return s3;
    }

    public void setS3(S3Properties s3) {
        this.s3 = s3;
    }

    public EntityExtractionProperties getEntityExtraction() {
        return entityExtraction;
    }

    public void setEntityExtraction(EntityExtractionProperties entityExtraction) {
        this.entityExtraction = entityExtraction;
    }

    public static class S3Properties {
        private String endpoint;
        private boolean pathStyleAccess = true;
        private String accessKeyId;
        private String secretAccessKey;
        private String region = "us-east-1";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public void setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    public static class EntityExtractionProperties {
        private boolean enabled = true;
        private boolean includeCustomMetadata = true;
        private boolean includeOisSecurity = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludeCustomMetadata() {
            return includeCustomMetadata;
        }

        public void setIncludeCustomMetadata(boolean includeCustomMetadata) {
            this.includeCustomMetadata = includeCustomMetadata;
        }

        public boolean isIncludeOisSecurity() {
            return includeOisSecurity;
        }

        public void setIncludeOisSecurity(boolean includeOisSecurity) {
            this.includeOisSecurity = includeOisSecurity;
        }
    }
}
