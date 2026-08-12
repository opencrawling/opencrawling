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
package org.opencrawling.internal.v1;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class DocumentPayloadRequest implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private final String taskId;
    private final String repositoryId;
    private final String documentId;
    private final byte[] rawContent;
    private final Map<String, String> metadata;
    private final List<String> securityAcls;
    private final long timestamp;

    public DocumentPayloadRequest(
            String taskId,
            String repositoryId,
            String documentId,
            byte[] rawContent,
            Map<String, String> metadata,
            List<String> securityAcls,
            long timestamp) {
        this.taskId = taskId;
        this.repositoryId = repositoryId;
        this.documentId = documentId;
        this.rawContent = rawContent;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.securityAcls = securityAcls != null ? securityAcls : new ArrayList<>();
        this.timestamp = timestamp;
    }

    public String getTaskId() { return taskId; }
    public String getRepositoryId() { return repositoryId; }
    public String getDocumentId() { return documentId; }
    public byte[] getRawContent() { return rawContent; }
    public Map<String, String> getMetadata() { return metadata; }
    public List<String> getSecurityAcls() { return securityAcls; }
    public long getTimestamp() { return timestamp; }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId = "";
        private String repositoryId = "";
        private String documentId = "";
        private byte[] rawContent = new byte[0];
        private Map<String, String> metadata = new HashMap<>();
        private List<String> securityAcls = new ArrayList<>();
        private long timestamp = System.currentTimeMillis();

        public Builder setTaskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder setRepositoryId(String repositoryId) {
            this.repositoryId = repositoryId;
            return this;
        }

        public Builder setDocumentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder setRawContent(byte[] rawContent) {
            this.rawContent = rawContent;
            return this;
        }

        public Builder putMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder putAllMetadata(Map<String, String> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder addSecurityAcl(String acl) {
            this.securityAcls.add(acl);
            return this;
        }

        public Builder addAllSecurityAcls(List<String> acls) {
            if (acls != null) {
                this.securityAcls.addAll(acls);
            }
            return this;
        }

        public Builder setTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public DocumentPayloadRequest build() {
            return new DocumentPayloadRequest(taskId, repositoryId, documentId, rawContent, metadata, securityAcls, timestamp);
        }
    }
}
