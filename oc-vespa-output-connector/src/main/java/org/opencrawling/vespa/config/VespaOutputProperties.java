/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.opencrawling.output.vespa")
public record VespaOutputProperties(
        @DefaultValue("http://localhost:8080") String endpoint,
        @DefaultValue("opencrawling") String namespace,
        @DefaultValue("opencrawling_chunk") String documentType,
        @DefaultValue("1024") int dimensions,
        @DefaultValue("30") int timeoutSeconds,
        @DefaultValue("false") boolean tlsEnabled,
        String tlsCertificate,
        String tlsPrivateKey,
        String tlsCaCertificates,
        @DefaultValue(VespaOutputProperties.DEFAULT_CONTENT_CLUSTER) String contentCluster
) {

    /** The content cluster of the bundled application package ({@code vespa-app/services.xml}). */
    public static final String DEFAULT_CONTENT_CLUSTER = "opencrawling_content";

    /**
     * The bound settings. {@code contentCluster} names the content cluster deletions address (Vespa's document API
     * requires it for a delete by selection).
     */
    @ConstructorBinding
    public VespaOutputProperties {
        // Paths are appended to the endpoint ("/document/v1/...", "/search/"): a trailing slash would double them.
        while (endpoint != null && endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (contentCluster == null || contentCluster.isBlank()) {
            contentCluster = DEFAULT_CONTENT_CLUSTER;
        }
    }

    /** A builder starting from the defaults the bound properties have. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds settings in code (tests, per-job outputs); unset values keep the bound properties' defaults. */
    public static final class Builder {
        private String endpoint = "http://localhost:8080";
        private String namespace = "opencrawling";
        private String documentType = "opencrawling_chunk";
        private int dimensions = 1024;
        private int timeoutSeconds = 30;
        private boolean tlsEnabled;
        private String tlsCertificate;
        private String tlsPrivateKey;
        private String tlsCaCertificates;
        private String contentCluster = DEFAULT_CONTENT_CLUSTER;

        private Builder() {}

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder documentType(String documentType) {
            this.documentType = documentType;
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder tlsEnabled(boolean tlsEnabled) {
            this.tlsEnabled = tlsEnabled;
            return this;
        }

        public Builder tlsCertificate(String tlsCertificate) {
            this.tlsCertificate = tlsCertificate;
            return this;
        }

        public Builder tlsPrivateKey(String tlsPrivateKey) {
            this.tlsPrivateKey = tlsPrivateKey;
            return this;
        }

        public Builder tlsCaCertificates(String tlsCaCertificates) {
            this.tlsCaCertificates = tlsCaCertificates;
            return this;
        }

        public Builder contentCluster(String contentCluster) {
            this.contentCluster = contentCluster;
            return this;
        }

        public VespaOutputProperties build() {
            return new VespaOutputProperties(endpoint, namespace, documentType, dimensions, timeoutSeconds, tlsEnabled,
                    tlsCertificate, tlsPrivateKey, tlsCaCertificates, contentCluster);
        }
    }
}
