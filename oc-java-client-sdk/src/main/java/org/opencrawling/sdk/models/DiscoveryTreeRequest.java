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
 * Request parameters for exploring a repository tree.
 *
 * @param start folder ID or path to start from (defaults to connector root when omitted)
 * @param depth levels below start folder to explore (1 = immediate children)
 * @param maxNodes maximum items to return
 * @param includeDocuments whether to include document nodes (default true)
 * @param userPrincipal asserted caller user principal (dev/test only)
 * @param userRoles asserted comma-separated caller groups/roles (dev/test only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryTreeRequest(
    String start,
    Integer depth,
    Integer maxNodes,
    Boolean includeDocuments,
    String userPrincipal,
    String userRoles
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String start;
        private Integer depth;
        private Integer maxNodes;
        private Boolean includeDocuments;
        private String userPrincipal;
        private String userRoles;

        public Builder start(String start) {
            this.start = start;
            return this;
        }

        public Builder depth(Integer depth) {
            this.depth = depth;
            return this;
        }

        public Builder maxNodes(Integer maxNodes) {
            this.maxNodes = maxNodes;
            return this;
        }

        public Builder includeDocuments(Boolean includeDocuments) {
            this.includeDocuments = includeDocuments;
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

        public DiscoveryTreeRequest build() {
            return new DiscoveryTreeRequest(start, depth, maxNodes, includeDocuments, userPrincipal, userRoles);
        }
    }
}
