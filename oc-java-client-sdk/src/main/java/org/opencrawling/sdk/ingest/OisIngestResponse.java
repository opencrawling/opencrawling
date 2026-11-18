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
package org.opencrawling.sdk.ingest;

/**
 * Outcome of pushing an {@link OisDocument} to {@code POST /api/v1/ingest/ois/{jobId}}.
 *
 * @param jobId      the target RAG job identifier
 * @param documentId the ingested or deleted document identifier
 * @param action     {@code UPSERT} or {@code DELETE}
 * @param status     HTTP status code ({@code 202} when accepted)
 */
public record OisIngestResponse(String jobId, String documentId, String action, int status) {

    public boolean isAccepted() {
        return status == 202;
    }
}
