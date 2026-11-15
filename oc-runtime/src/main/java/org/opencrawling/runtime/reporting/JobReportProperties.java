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
package org.opencrawling.runtime.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Configuration properties for OpenCrawling post-job audit reporting.
 */
@Component
@ConfigurationProperties(prefix = "spring.opencrawling.reporting")
public class JobReportProperties {

    private boolean enabled = true;
    private Formats formats = new Formats();
    private Storage storage = new Storage();
    private Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Formats getFormats() {
        return formats;
    }

    public void setFormats(Formats formats) {
        this.formats = formats;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public static class Formats {
        private Parquet parquet = new Parquet();
        private boolean avro = false;
        private boolean csv = true;
        private boolean json = true;

        public Parquet getParquet() {
            return parquet;
        }

        public void setParquet(Parquet parquet) {
            this.parquet = parquet;
        }

        public boolean isAvro() {
            return avro;
        }

        public void setAvro(boolean avro) {
            this.avro = avro;
        }

        public boolean isCsv() {
            return csv;
        }

        public void setCsv(boolean csv) {
            this.csv = csv;
        }

        public boolean isJson() {
            return json;
        }

        public void setJson(boolean json) {
            this.json = json;
        }
    }

    public static class Parquet {
        private boolean enabled = true;
        private String compression = "ZSTD";
        private long rowGroupSizeBytes = 134217728L; // 128 MB
        private int pageSizeBytes = 1048576;         // 1 MB

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCompression() {
            return compression;
        }

        public void setCompression(String compression) {
            this.compression = compression;
        }

        public long getRowGroupSizeBytes() {
            return rowGroupSizeBytes;
        }

        public void setRowGroupSizeBytes(long rowGroupSizeBytes) {
            this.rowGroupSizeBytes = rowGroupSizeBytes;
        }

        public int getPageSizeBytes() {
            return pageSizeBytes;
        }

        public void setPageSizeBytes(int pageSizeBytes) {
            this.pageSizeBytes = pageSizeBytes;
        }
    }

    public static class Storage {
        private String type = "LOCAL"; // LOCAL, S3, OZONE_S3
        private String localDir = "target/reports";
        private S3 s3 = new S3();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLocalDir() {
            return localDir;
        }

        public void setLocalDir(String localDir) {
            this.localDir = localDir;
        }

        public S3 getS3() {
            return s3;
        }

        public void setS3(S3 s3) {
            this.s3 = s3;
        }

        public File resolveLocalDir() {
            File dir = new File(localDir);
            if (!dir.isAbsolute()) {
                // When running from root of multi-module maven project and oc-runtime module exists
                File moduleTarget = new File("oc-runtime", localDir);
                if (new File("oc-runtime").isDirectory() && !new File("pom.xml").isDirectory()) {
                    dir = moduleTarget;
                }
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
    }

    public static class S3 {
        private String endpoint = "http://localhost:9878";
        private String bucket = "opencrawling-audit-reports";
        private String region = "us-east-1";
        private boolean pathStyleAccess = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

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

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }
    }

    public static class Retention {
        private int maxLocalReports = 100;
        private int retentionDays = 90;

        public int getMaxLocalReports() {
            return maxLocalReports;
        }

        public void setMaxLocalReports(int maxLocalReports) {
            this.maxLocalReports = maxLocalReports;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }
    }
}
