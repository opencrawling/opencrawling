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

public class PayloadIngestionResponse implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public enum Status {
        SUCCESS,
        PROCESSING,
        FAILED
    }

    private final String documentId;
    private final Status status;
    private final String errorMessage;

    public PayloadIngestionResponse(String documentId, Status status, String errorMessage) {
        this.documentId = documentId;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public String getDocumentId() { return documentId; }
    public Status getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String documentId = "";
        private Status status = Status.SUCCESS;
        private String errorMessage = "";

        public Builder setDocumentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder setStatus(Status status) {
            this.status = status;
            return this;
        }

        public Builder setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public PayloadIngestionResponse build() {
            return new PayloadIngestionResponse(documentId, status, errorMessage);
        }
    }
}
