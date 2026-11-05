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
package org.opencrawling.core.connector.discovery;

import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One folder or document found by discovery, described without reading its content.
 *
 * <p>{@code security} is the item's access control as the source repository reports it, mapped the way the
 * connector maps it when crawling. It is {@code null} when the repository did not return it; such an item must be
 * treated as unreadable, never as public.
 *
 * @param id           the id the item would be indexed under (for documents, the same id the crawler emits), so
 *                     that an authorization provider keyed on document ids can decide on it
 * @param sourceId     the repository's own id for the item, used to explore below it
 * @param parentId     the {@code sourceId} of the folder it was found in, or {@code null} for the starting node
 * @param name         the item's name
 * @param path         the item's path in the repository, or {@code null} when it has none
 * @param type         folder or document
 * @param depth        how many levels below the starting node it was found (the starting node's children are 1)
 * @param mimeType     the document's MIME type, or {@code null}
 * @param sizeBytes    the document's content size, or {@code null}
 * @param lastModified when the item last changed, or {@code null}
 * @param childCount   how many children the folder has when the repository reports it, or {@code null}
 * @param security     the item's access control, or {@code null} when unknown
 */
public record DiscoveryNode(
        String id,
        String sourceId,
        String parentId,
        String name,
        String path,
        NodeType type,
        int depth,
        String mimeType,
        Long sizeBytes,
        Instant lastModified,
        Long childCount,
        SecurityConfig security
) {

    /** What kind of item a node is. */
    public enum NodeType { FOLDER, DOCUMENT }

    public DiscoveryNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(type, "type");
    }

    /**
     * The reference an authorization provider decides on: the node's id, and its {@code security} when known.
     * Without {@code security} the metadata carries no access-control data, which metadata-based providers deny.
     */
    public ResourceRef toResourceRef() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", id);
        if (security != null) {
            metadata.put("security", security);
        }
        return new ResourceRef(id, metadata);
    }
}
