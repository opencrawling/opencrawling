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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * A folder or document node discovered in a repository hierarchy without reading content.
 *
 * @param id the document ID as it would be indexed
 * @param sourceId the repository's native identifier
 * @param parentId the parent folder's sourceId, or null for root
 * @param name the name of the folder or document
 * @param path the path within the repository
 * @param type the node type ("FOLDER" or "DOCUMENT")
 * @param depth levels below the starting node (1 = immediate child)
 * @param mimeType the document MIME type, or null
 * @param sizeBytes the content size in bytes, or null
 * @param lastModified timestamp of last modification, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryNodeView(
    String id,
    String sourceId,
    String parentId,
    String name,
    String path,
    String type,
    int depth,
    String mimeType,
    Long sizeBytes,
    Instant lastModified
) {}
