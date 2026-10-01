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
package org.opencrawling.doxis.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Maps OIS documents and embedded chunks onto Doxis Dataset v3 rows ({@code column_slug -> value}).
 */
public class DoxisRowMapper {

    private static final String NUL_CHAR = Character.toString(0);
    private static final Set<String> PROMOTED_KEYS = Set.of("title", "name", "mimeType", "source", "uri", "acl",
            "security", "lastModified", "documentId");

    private final DoxisOutputProperties properties;
    private final ObjectMapper objectMapper;

    public DoxisRowMapper(DoxisOutputProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds a full document row, including the inline document cell when content is present.
     */
    public Map<String, Object> documentRow(RepositoryDocument document, byte[] content) {
        Map<String, Object> row = documentMetadataCells(document, content);
        row.put(DoxisConstants.COL_EXTERNAL_ID, document.id());
        row.put(DoxisConstants.COL_DOCUMENT_ID, document.id());
        row.put(DoxisConstants.COL_RECORD_TYPE, DoxisConstants.RECORD_TYPE_DOCUMENT);
        if (properties.uploadContent() && content != null && content.length > 0) {
            Map<String, Object> documentInput = new LinkedHashMap<>();
            documentInput.put("data", Base64.getEncoder().encodeToString(content));
            documentInput.put("filename", filename(document));
            String mimeType = first(document.metadata(), "mimeType");
            if (mimeType != null) {
                documentInput.put("content_type", mimeType);
            }
            row.put(DoxisConstants.COL_DOCUMENT, documentInput);
        }
        return row;
    }

    /**
     * Builds the metadata and security cells of a document row; used for UPDATE_METADATA patches,
     * so it never contains the write-once document cell or the identity columns.
     */
    public Map<String, Object> documentMetadataCells(RepositoryDocument document, byte[] content) {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, List<String>> metadata = document.metadata() != null ? document.metadata() : Map.of();

        row.put(DoxisConstants.COL_URI, document.uri());
        row.put(DoxisConstants.COL_TITLE, title(metadata, document.id()));
        putIfNotNull(row, DoxisConstants.COL_SOURCE_SYSTEM, sourceSystem(first(metadata, "source"), document.uri()));
        putIfNotNull(row, DoxisConstants.COL_MIME_TYPE, first(metadata, "mimeType"));
        if (content != null) {
            row.put(DoxisConstants.COL_CONTENT_LENGTH, content.length);
        }
        if (document.lastModified() != null) {
            row.put(DoxisConstants.COL_LAST_MODIFIED, document.lastModified().toString());
        }
        row.put(DoxisConstants.COL_INGESTED_AT, Instant.now().toString());

        if (properties.applySecurityAcls()) {
            putSecurity(row, document.acl(), document.security());
        }
        if (properties.includeSourceMetadata()) {
            Map<String, Object> extra = new TreeMap<>();
            metadata.forEach((key, values) -> {
                if (!PROMOTED_KEYS.contains(key) && values != null) {
                    extra.put(key, values.size() == 1 ? clean(values.getFirst()) : values.stream().map(DoxisRowMapper::clean).toList());
                }
            });
            row.put(DoxisConstants.COL_METADATA_JSON, toJson(extra));
        }
        return row;
    }

    /**
     * Builds a chunk row from an embedded Kafka message (decoupled mode). The chunk text is stored in a text column;
     * the original binary is not available on this topic.
     */
    public Map<String, Object> chunkRow(DocumentEmbeddedMessage message) {
        Map<String, Object> metadata = message.metadata() != null ? message.metadata() : Map.of();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(DoxisConstants.COL_EXTERNAL_ID, message.chunkId());
        row.put(DoxisConstants.COL_DOCUMENT_ID, message.documentId());
        row.put(DoxisConstants.COL_RECORD_TYPE, DoxisConstants.RECORD_TYPE_CHUNK);

        String uri = scalar(metadata.get("uri"));
        putIfNotNull(row, DoxisConstants.COL_URI, uri);
        String title = scalar(metadata.get("title"));
        if (title == null) {
            title = scalar(metadata.get("name"));
        }
        row.put(DoxisConstants.COL_TITLE, title != null ? title : message.documentId());
        putIfNotNull(row, DoxisConstants.COL_SOURCE_SYSTEM, sourceSystem(scalar(metadata.get("source")), uri));
        putIfNotNull(row, DoxisConstants.COL_MIME_TYPE, scalar(metadata.get("mimeType")));
        putIfNotNull(row, DoxisConstants.COL_LAST_MODIFIED, scalar(metadata.get("lastModified")));
        row.put(DoxisConstants.COL_INGESTED_AT, Instant.now().toString());
        String text = message.text() != null ? clean(message.text()) : "";
        row.put(DoxisConstants.COL_CHUNK_TEXT, text);
        row.put(DoxisConstants.COL_CONTENT_LENGTH, text.length());

        if (properties.applySecurityAcls()) {
            putSecurity(row, scalar(metadata.get("acl")), metadata.get("security"));
        }
        if (properties.includeSourceMetadata()) {
            Map<String, Object> extra = new TreeMap<>();
            metadata.forEach((key, value) -> {
                if (!PROMOTED_KEYS.contains(key) && value != null) {
                    extra.put(key, value);
                }
            });
            row.put(DoxisConstants.COL_METADATA_JSON, toJson(extra));
        }
        return row;
    }

    /**
     * Writes the legacy ACL string, the flattened allow/deny identity lists and the full OIS security JSON.
     * {@code security} is either a {@link SecurityConfig} or the {@code Map} it becomes after Kafka JSON deserialization.
     */
    void putSecurity(Map<String, Object> row, String acl, Object security) {
        if (acl != null && !acl.isBlank()) {
            row.put(DoxisConstants.COL_ACL, acl);
        }

        boolean inheritanceEnabled = true;
        List<String> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        Object securityJson = null;

        if (security instanceof SecurityConfig sc) {
            inheritanceEnabled = sc.inheritanceEnabled();
            if (sc.permissions() != null) {
                for (PermissionRule rule : sc.permissions()) {
                    classify(rule.identity(), rule.access(), allowed, denied);
                }
            }
            securityJson = sc;
        } else if (security instanceof Map<?, ?> securityMap) {
            if (securityMap.containsKey("inheritanceEnabled")) {
                inheritanceEnabled = Boolean.TRUE.equals(securityMap.get("inheritanceEnabled"));
            }
            if (securityMap.get("permissions") instanceof List<?> permissions) {
                for (Object permission : permissions) {
                    if (permission instanceof Map<?, ?> rule) {
                        classify(String.valueOf(rule.get("identity")), String.valueOf(rule.get("access")), allowed, denied);
                    }
                }
            }
            securityJson = securityMap;
        }

        if (security != null) {
            row.put(DoxisConstants.COL_SECURITY_INHERITANCE, inheritanceEnabled);
            row.put(DoxisConstants.COL_SECURITY_ALLOWED_READ, String.join(",", allowed));
            row.put(DoxisConstants.COL_SECURITY_DENIED_READ, String.join(",", denied));
            row.put(DoxisConstants.COL_SECURITY_JSON, toJson(securityJson));
        }
    }

    private static void classify(String identity, String access, List<String> allowed, List<String> denied) {
        if (identity == null || identity.isBlank() || "null".equals(identity)) {
            return;
        }
        if ("deny".equalsIgnoreCase(access)) {
            denied.add(identity);
        } else if ("read".equalsIgnoreCase(access) || "write".equalsIgnoreCase(access)) {
            allowed.add(identity);
        }
    }

    private static String title(Map<String, List<String>> metadata, String fallback) {
        String title = first(metadata, "title");
        if (title == null) {
            title = first(metadata, "name");
        }
        return title != null ? title : fallback;
    }

    private static String filename(RepositoryDocument document) {
        String name = first(document.metadata(), "name");
        if (name != null) {
            return name;
        }
        if (document.uri() != null) {
            String path = document.uri();
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                return path.substring(slash + 1);
            }
        }
        return document.id();
    }

    private static String sourceSystem(String source, String uri) {
        if (source != null) {
            return source;
        }
        if (uri == null) {
            return null;
        }
        try {
            return URI.create(uri).getScheme();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String first(Map<String, List<String>> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        List<String> values = metadata.get(key);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            return null;
        }
        return clean(values.getFirst());
    }

    private static String scalar(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() || list.getFirst() == null ? null : clean(String.valueOf(list.getFirst()));
        }
        return value == null ? null : clean(String.valueOf(value));
    }

    private static String clean(String value) {
        return value.replace(NUL_CHAR, "");
    }

    private static void putIfNotNull(Map<String, Object> row, String key, Object value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
