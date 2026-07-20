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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for OpenCrawling Claim Check Pattern storage.
 */
@ConfigurationProperties(prefix = "spring.opencrawling.claim-check")
public class ClaimCheckProperties {

    /**
     * Active Claim Check store implementation: "local", "ozone", or "s3". Default is "local".
     */
    private String store = "local";

    /**
     * Whether to delete claim check payload upon successful consumption. Default is true.
     */
    private boolean cleanupOnConsume = true;

    private Local local = new Local();
    private Ozone ozone = new Ozone();

    public static class Local {
        private String dir = "target/claims";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class Ozone {
        private String s3Endpoint = "http://localhost:9878";
        private String volume = "s3v";
        private String bucket = "claims";
        private String accessKey = "ozone";
        private String secretKey = "ozone-secret";
        private boolean pathStyleAccess = true;
        private String region = "us-east-1";
        private boolean autoCreateBucket = true;

        public String getS3Endpoint() {
            return s3Endpoint;
        }

        public void setS3Endpoint(String s3Endpoint) {
            this.s3Endpoint = s3Endpoint;
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

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isAutoCreateBucket() {
            return autoCreateBucket;
        }

        public void setAutoCreateBucket(boolean autoCreateBucket) {
            this.autoCreateBucket = autoCreateBucket;
        }
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public boolean isCleanupOnConsume() {
        return cleanupOnConsume;
    }

    public void setCleanupOnConsume(boolean cleanupOnConsume) {
        this.cleanupOnConsume = cleanupOnConsume;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    public Ozone getOzone() {
        return ozone;
    }

    public void setOzone(Ozone ozone) {
        this.ozone = ozone;
    }
}
