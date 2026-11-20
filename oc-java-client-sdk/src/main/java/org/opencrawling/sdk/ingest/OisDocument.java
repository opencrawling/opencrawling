/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.Objects;

/**
 * Open Ingestion Standard (OIS) document payload accepted by {@code POST /api/v1/ingest/ois/{jobId}}.
 *
 * @param id       canonical document identifier or URI
 * @param action   {@code UPSERT} or {@code DELETE}
 * @param content  textual content payload (required when {@code action} is {@code UPSERT})
 * @param metadata optional metadata key-value attributes
 * @param security Zero-Trust ACL security configuration (required when {@code action} is {@code UPSERT})
 * @param source   optional source repository provenance
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OisDocument(
        String id,
        String action,
        OisContent content,
        Map<String, Object> metadata,
        SecurityConfig security,
        OisSource source
) {
    public static final String ACTION_UPSERT = "UPSERT";
    public static final String ACTION_DELETE = "DELETE";

    public OisDocument {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (!ACTION_UPSERT.equals(action) && !ACTION_DELETE.equals(action)) {
            throw new IllegalArgumentException("action must be UPSERT or DELETE");
        }
        if (ACTION_UPSERT.equals(action)) {
            Objects.requireNonNull(content, "content is required for UPSERT");
            Objects.requireNonNull(content.text(), "content.text is required for UPSERT");
            Objects.requireNonNull(security, "security is required for UPSERT");
        }
        id = id.trim();
        metadata = metadata == null ? null : Map.copyOf(metadata);
    }

    /** Creates an {@code UPSERT} OIS document with text content, metadata, and Zero-Trust ACL security. */
    public static OisDocument upsert(String id, OisContent content, Map<String, Object> metadata, SecurityConfig security) {
        return new OisDocument(id, ACTION_UPSERT, content, metadata, security, null);
    }

    /** Creates an {@code UPSERT} OIS document including source repository provenance. */
    public static OisDocument upsert(String id, OisContent content, Map<String, Object> metadata, SecurityConfig security,
                                     OisSource source) {
        return new OisDocument(id, ACTION_UPSERT, content, metadata, security, source);
    }

    /** Creates a {@code DELETE} tombstone OIS document to purge an existing document from downstream stores. */
    public static OisDocument delete(String id) {
        return new OisDocument(id, ACTION_DELETE, null, null, null, null);
    }

    /** Creates a {@code DELETE} tombstone OIS document with source repository provenance. */
    public static OisDocument delete(String id, OisSource source) {
        return new OisDocument(id, ACTION_DELETE, null, null, null, source);
    }

    public OisDocument withSource(OisSource source) {
        return new OisDocument(id, action, content, metadata, security, source);
    }

    public OisDocument withMetadata(Map<String, Object> metadata) {
        return new OisDocument(id, action, content, metadata, security, source);
    }
}
