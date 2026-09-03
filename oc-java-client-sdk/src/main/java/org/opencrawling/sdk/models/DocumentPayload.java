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
import java.util.Map;

/**
 * Open Ingestion Standard (OIS) Document Payload.
 * Supports lifecycle actions (UPSERT / DELETE) and tombstone payloads.
 *
 * @param id Unique document identifier in target index
 * @param action Document action (UPSERT or DELETE)
 * @param source Metadata map describing origin system and connector version
 * @param content Document content stream or text map (optional for DELETE tombstones)
 * @param metadata Key-value map of document metadata (optional for DELETE tombstones)
 * @param security Security and ACL configuration (optional for DELETE tombstones)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentPayload(
    String id,
    DocumentAction action,
    Map<String, Object> source,
    Map<String, Object> content,
    Map<String, Object> metadata,
    Map<String, Object> security
) {
    public DocumentPayload(
        String id,
        Map<String, Object> source,
        Map<String, Object> content,
        Map<String, Object> metadata,
        Map<String, Object> security
    ) {
        this(id, DocumentAction.UPSERT, source, content, metadata, security);
    }

    /**
     * Creates an OIS-compliant tombstone deletion payload (action: DELETE).
     *
     * @param id Document identifier to remove
     * @param source Metadata map describing origin system
     * @return A DocumentPayload configured with action DELETE
     */
    public static DocumentPayload createDeleteTombstone(String id, Map<String, Object> source) {
        return new DocumentPayload(id, DocumentAction.DELETE, source, null, null, null);
    }
}
