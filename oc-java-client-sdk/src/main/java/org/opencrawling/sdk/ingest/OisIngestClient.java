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

import java.util.Map;

/**
 * Client for pushing Open Ingestion Standard (OIS) documents and deletion tombstones directly into an active RAG job
 * via {@code POST /api/v1/ingest/ois/{jobId}}.
 */
public interface OisIngestClient {

    /**
     * Pushes an {@link OisDocument} ({@code UPSERT} or {@code DELETE}) into {@code jobId}'s ingestion pipeline.
     *
     * @param jobId    target RAG job identifier
     * @param document OIS document payload
     * @return the ingestion receipt ({@code 202 Accepted})
     */
    OisIngestResponse push(String jobId, OisDocument document);

    /**
     * Convenience helper that constructs and pushes an {@code UPSERT} {@link OisDocument}.
     */
    default OisIngestResponse upsert(String jobId, String documentId, OisContent content,
                                     Map<String, Object> metadata, SecurityConfig security) {
        return push(jobId, OisDocument.upsert(documentId, content, metadata, security));
    }

    /**
     * Convenience helper that constructs and pushes a {@code DELETE} tombstone {@link OisDocument}.
     */
    default OisIngestResponse delete(String jobId, String documentId) {
        return push(jobId, OisDocument.delete(documentId));
    }

    /**
     * Returns an {@link OisIngestClient} that authenticates requests with {@code Authorization: Bearer <oisToken>},
     * allowing a client configured with OAuth 2.0 management credentials to push OIS documents using the runtime's
     * dedicated {@code opencrawling.ingest.ois.token}.
     */
    OisIngestClient withToken(String oisToken);
}
