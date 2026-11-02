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
package org.opencrawling.core.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A candidate document handed to an {@link AuthorizationProvider}.
 *
 * <p>The metadata is the access-control data the store returned with the candidate (the {@code security}
 * object, the flattened {@code security_allowed_read}/{@code security_denied_read} fields or the legacy
 * {@code acl}). A metadata-based provider decides from it; a relationship-based provider decides from the
 * document id and may ignore it.
 *
 * @param documentId the source document id, or {@code null} when the store did not keep it
 * @param metadata   the access-control metadata stored with the candidate, never {@code null}
 */
public record ResourceRef(String documentId, Map<String, Object> metadata) {

    public ResourceRef {
        metadata = metadata == null ? Map.of() : metadata;
    }

    /** Metadata keys that carry the source document id, in order of preference. */
    public static final List<String> DOCUMENT_ID_KEYS = List.of("documentId", "document_id", "parent_document_id");

    /**
     * Builds a reference from a store's metadata, taking the document id from {@link #DOCUMENT_ID_KEYS}.
     *
     * <p>There is deliberately no fallback to a chunk or point id: a relationship provider keys its decisions
     * on the source document id, and a stand-in would only ever be denied. A candidate without one gets a
     * {@code null} id, which relationship providers deny and the metadata provider ignores.
     */
    public static ResourceRef fromMetadata(Map<String, Object> metadata) {
        String id = null;
        if (metadata != null) {
            for (String key : DOCUMENT_ID_KEYS) {
                id = idValue(metadata.get(key));
                if (id != null) {
                    break;
                }
            }
        }
        return new ResourceRef(id, metadata);
    }

    /**
     * Reads a stored id value: a string, or the first element of a multi-valued field (Solr's schemaless
     * mode stores unknown fields as lists). Blank values count as absent.
     */
    public static String idValue(Object value) {
        if (value instanceof Collection<?> values) {
            value = values.isEmpty() ? null : values.iterator().next();
        }
        if (value == null) {
            return null;
        }
        String id = String.valueOf(value).trim();
        return id.isEmpty() ? null : id;
    }
}
