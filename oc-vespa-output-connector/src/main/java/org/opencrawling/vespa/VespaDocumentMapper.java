/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import ai.vespa.feed.client.DocumentId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a chunk of a crawled document (plus its ACL and embedding) into a Vespa document/v1 PUT payload.
 * Shared by {@link VespaOutputConnector} and {@link org.opencrawling.vespa.messaging.VespaStoreWriterConsumer}
 * so the ACL/field mapping logic exists exactly once.
 */
@Component
public class VespaDocumentMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param security either a {@link SecurityConfig} (direct ingestion path) or the generic
     *                  {@code Map} shape a {@code SecurityConfig} deserializes to off Kafka
     */
    public VespaDocument toDocument(String namespace, String documentType, String chunkId, String text, String uri,
                                     String acl, String lastModified, Object security, float[] embedding,
                                     Map<String, Object> extraMetadata) {
        EffectiveAcl aclResult = EffectiveAcl.resolve(security, acl);

        ObjectNode fields = MAPPER.createObjectNode();
        fields.put(VespaFields.FIELD_CHUNK_ID, chunkId);
        fields.put(VespaFields.FIELD_TEXT, text != null ? text : "");
        fields.put(VespaFields.FIELD_URI, uri != null ? uri : "");
        // An empty security_allowed_read array is not fed, and readers then fall back to the acl field, so it
        // holds the effective readers rather than the raw acl string (which permission rules override).
        fields.put(VespaFields.FIELD_ACL, String.join(",", aclResult.allowedRead()));
        fields.put(VespaFields.FIELD_LAST_MODIFIED, lastModified != null ? lastModified : "");
        fields.put(VespaFields.FIELD_SECURITY_INHERITANCE, aclResult.inheritanceEnabled());
        putStringArray(fields, VespaFields.FIELD_SECURITY_ALLOWED_READ, aclResult.allowedRead());
        putStringArray(fields, VespaFields.FIELD_SECURITY_DENIED_READ, aclResult.deniedRead());

        if (embedding != null) {
            ArrayNode values = fields.putObject(VespaFields.FIELD_EMBEDDING).putArray("values");
            for (float value : embedding) {
                values.add(value);
            }
        }

        // Vespa schemas are strongly typed, unlike Qdrant/Milvus payloads: feeding a field name
        // that isn't declared in the .sd file is rejected outright, so arbitrary document
        // metadata is folded into one JSON field instead of dynamic top-level fields.
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (extraMetadata != null) {
            extraMetadata.forEach((key, value) -> {
                if (value != null && !"security".equals(key)) {
                    metadata.put(key, value);
                }
            });
        }
        if (!metadata.isEmpty()) {
            fields.put(VespaFields.FIELD_METADATA_JSON, writeJson(metadata));
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.set("fields", fields);

        DocumentId id = DocumentId.of(namespace, documentType, chunkId);
        return new VespaDocument(id, root.toString());
    }

    /**
     * Routes to a dimension-specific document type (mirroring VectorStoreWriterConsumer's pgvector
     * routing) so a single running instance can feed multiple embedding models side by side without
     * redeploying the schema. Falls back to the configured default document type otherwise.
     */
    public static String resolveDocumentType(int dimensions, String defaultDocumentType) {
        return switch (dimensions) {
            case 384 -> VespaFields.DOCUMENT_TYPE_384;
            case 768 -> VespaFields.DOCUMENT_TYPE_768;
            case 1024 -> VespaFields.DOCUMENT_TYPE_1024;
            default -> defaultDocumentType;
        };
    }

    private static String writeJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void putStringArray(ObjectNode fields, String field, List<String> values) {
        ArrayNode array = fields.putArray(field);
        values.forEach(array::add);
    }

    public record VespaDocument(DocumentId id, String json) {
    }
}
