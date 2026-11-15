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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request parameters for executing a crawl dry-run preview.
 *
 * @param scope folder ID/path or read-only query (defaults to connector scope when omitted)
 * @param sampleSize maximum matching documents to return as samples
 * @param maxScan maximum items scanned before returning a lower-bound count
 * @param userPrincipal asserted caller user principal (dev/test only)
 * @param userRoles asserted comma-separated caller groups/roles (dev/test only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryDryRunRequest(
    String scope,
    Integer sampleSize,
    Integer maxScan,
    String userPrincipal,
    String userRoles
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String scope;
        private Integer sampleSize;
        private Integer maxScan;
        private String userPrincipal;
        private String userRoles;

        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        public Builder sampleSize(Integer sampleSize) {
            this.sampleSize = sampleSize;
            return this;
        }

        public Builder maxScan(Integer maxScan) {
            this.maxScan = maxScan;
            return this;
        }

        public Builder userPrincipal(String userPrincipal) {
            this.userPrincipal = userPrincipal;
            return this;
        }

        public Builder userRoles(String userRoles) {
            this.userRoles = userRoles;
            return this;
        }

        public DiscoveryDryRunRequest build() {
            return new DiscoveryDryRunRequest(scope, sampleSize, maxScan, userPrincipal, userRoles);
        }
    }
}
