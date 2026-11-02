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

import java.util.Map;

/**
 * A candidate document handed to an {@link AuthorizationProvider}.
 *
 * <p>The metadata is the access-control data the store returned with the candidate (the {@code security}
 * object, the flattened {@code security_allowed_read}/{@code security_denied_read} fields or the legacy
 * {@code acl}). A metadata-based provider decides from it; a relationship-based provider decides from the
 * document id and may ignore it.
 *
 * @param documentId the source document id, or the best available stand-in (a chunk id or a URI)
 * @param metadata   the access-control metadata stored with the candidate, never {@code null}
 */
public record ResourceRef(String documentId, Map<String, Object> metadata) {

    public ResourceRef {
        metadata = metadata == null ? Map.of() : metadata;
    }

    /**
     * Builds a reference from a store's metadata map, preferring its {@code documentId} or {@code document_id}
     * field over the given fallback.
     */
    public static ResourceRef fromMetadata(Object fallbackId, Map<String, Object> metadata) {
        Object id = null;
        if (metadata != null) {
            id = metadata.get("documentId");
            if (id == null || String.valueOf(id).isBlank()) {
                id = metadata.get("document_id");
            }
        }
        if (id == null || String.valueOf(id).isBlank()) {
            id = fallbackId;
        }
        return new ResourceRef(id == null ? null : String.valueOf(id), metadata);
    }
}
