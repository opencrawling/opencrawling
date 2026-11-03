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
package org.opencrawling.qdrant;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt.ListValue;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Maps a chunk of a crawled document (plus its ACL and embedding) into a Qdrant {@link PointStruct}.
 * Shared by {@link QdrantOutputConnector} and {@link org.opencrawling.qdrant.messaging.QdrantStoreWriterConsumer}
 * so the ACL/payload mapping logic exists exactly once.
 */
@Component
public class QdrantPointMapper {

    /**
     * @param security either a {@link SecurityConfig} (direct ingestion path) or the generic
     *                  {@code Map} shape a {@code SecurityConfig} deserializes to off Kafka
     */
    public PointStruct toPoint(String chunkId, String text, String uri, String acl, String lastModified,
                                Object security, float[] embedding, Map<String, Object> extraMetadata) {
        return toPoint(null, chunkId, text, uri, acl, lastModified, security, embedding, extraMetadata);
    }

    /**
     * @param documentId the ID of the parent document (for group deletion and payload filtering)
     * @param security either a {@link SecurityConfig} (direct ingestion path) or the generic
     *                  {@code Map} shape a {@code SecurityConfig} deserializes to off Kafka
     */
    public PointStruct toPoint(String documentId, String chunkId, String text, String uri, String acl, String lastModified,
                                Object security, float[] embedding, Map<String, Object> extraMetadata) {
        EffectiveAcl aclResult = EffectiveAcl.resolve(security, acl);

        Map<String, Value> payload = new HashMap<>();

        String resolvedDocId = (documentId != null && !documentId.isBlank())
                ? documentId
                : (extraMetadata != null && extraMetadata.get(QdrantFields.FIELD_DOCUMENT_ID) != null
                        ? String.valueOf(extraMetadata.get(QdrantFields.FIELD_DOCUMENT_ID))
                        : (extraMetadata != null && extraMetadata.get("document_id") != null
                                ? String.valueOf(extraMetadata.get("document_id"))
                                : null));
        if (resolvedDocId != null) {
            payload.put(QdrantFields.FIELD_DOCUMENT_ID, ValueFactory.value(resolvedDocId));
        }

        payload.put(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value(chunkId));
        payload.put(QdrantFields.FIELD_TEXT, ValueFactory.value(text != null ? text : ""));
        payload.put(QdrantFields.FIELD_URI, ValueFactory.value(uri != null ? uri : ""));
        payload.put(QdrantFields.FIELD_ACL, ValueFactory.value(acl != null ? acl : ""));
        payload.put(QdrantFields.FIELD_LAST_MODIFIED, ValueFactory.value(lastModified != null ? lastModified : ""));
        payload.put(QdrantFields.FIELD_SECURITY_INHERITANCE, ValueFactory.value(aclResult.inheritanceEnabled()));
        payload.put(QdrantFields.FIELD_SECURITY_ALLOWED_READ, stringListValue(aclResult.allowedRead()));
        payload.put(QdrantFields.FIELD_SECURITY_DENIED_READ, stringListValue(aclResult.deniedRead()));

        if (extraMetadata != null) {
            extraMetadata.forEach((key, value) -> {
                // "security" carries the raw SecurityConfig/Map already unpacked into the
                // security_* fields above; re-emitting it verbatim would be redundant payload noise.
                if (!payload.containsKey(key) && !"security".equals(key) && value != null) {
                    payload.put(key, toValue(value));
                }
            });
        }

        UUID pointId = UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8));
        return PointStruct.newBuilder()
                .setId(id(pointId))
                .setVectors(vectors(embedding))
                .putAllPayload(payload)
                .build();
    }

    private static Value toValue(Object value) {
        if (value instanceof List<?> list) {
            List<String> strings = new ArrayList<>();
            for (Object item : list) {
                strings.add(String.valueOf(item));
            }
            return stringListValue(strings);
        }
        return ValueFactory.value(String.valueOf(value));
    }

    private static Value stringListValue(List<String> values) {
        ListValue.Builder listValue = ListValue.newBuilder();
        for (String value : values) {
            listValue.addValues(ValueFactory.value(value));
        }
        return Value.newBuilder().setListValue(listValue.build()).build();
    }
}
