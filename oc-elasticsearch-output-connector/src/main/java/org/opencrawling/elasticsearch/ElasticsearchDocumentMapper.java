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
package org.opencrawling.elasticsearch;

import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.ReadKeys;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.opencrawling.elasticsearch.ElasticsearchConstants.*;

/**
 * Builds the chunk document stored in Elasticsearch. Both write paths (the direct {@link ElasticsearchOutputConnector}
 * and the Kafka {@code ElasticsearchStoreWriterConsumer}) go through here so they store identical documents.
 */
public class ElasticsearchDocumentMapper {

    /**
     * One chunk of a document.
     *
     * @param chunkId      the Elasticsearch document id
     * @param documentId   the source document's id (used to purge all its chunks); may be blank
     * @param text         the chunk text
     * @param uri          the source document's URI
     * @param legacyAcl    the legacy flat ACL; a collection is joined with commas
     * @param lastModified ISO-8601 timestamp; omitted when blank
     * @param effectiveAcl the resolved permissions
     * @param embedding    the chunk vector
     * @param metadata     free-form metadata; never overwrites the fields above
     */
    public record Chunk(
            String chunkId,
            String documentId,
            String text,
            String uri,
            Object legacyAcl,
            String lastModified,
            EffectiveAcl effectiveAcl,
            float[] embedding,
            Map<String, ?> metadata
    ) {
    }

    public Map<String, Object> toSource(Chunk chunk) {
        Map<String, Object> source = new LinkedHashMap<>();
        if (chunk.metadata() != null) {
            chunk.metadata().forEach((key, value) -> {
                if (key != null && value != null && !RESERVED_FIELDS.contains(key)) {
                    source.put(key, value);
                }
            });
        }

        source.put(FIELD_ID, chunk.chunkId());
        if (isPresent(chunk.documentId())) {
            source.put(FIELD_DOCUMENT_ID, chunk.documentId());
        }
        source.put(FIELD_TEXT, chunk.text() != null ? chunk.text() : "");
        source.put(FIELD_URI, chunk.uri() != null ? chunk.uri() : "");
        source.put(FIELD_ACL, flatAcl(chunk.legacyAcl()));
        if (isPresent(chunk.lastModified())) {
            source.put(FIELD_LAST_MODIFIED, chunk.lastModified());
        }

        EffectiveAcl acl = chunk.effectiveAcl();
        source.put(FIELD_SECURITY_INHERITANCE, acl.inheritanceEnabled());
        source.put(FIELD_SECURITY_ALLOWED_READ, acl.allowedRead());
        source.put(FIELD_SECURITY_DENIED_READ, acl.deniedRead());
        source.put(FIELD_SECURITY_READ_KEYS, ReadKeys.forAllowed(acl.allowedRead()));
        source.put(FIELD_EMBEDDINGS, chunk.embedding());
        return source;
    }

    static String flatAcl(Object acl) {
        if (acl == null) {
            return "";
        }
        if (acl instanceof Collection<?> values) {
            return values.stream().filter(v -> v != null).map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(acl);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
