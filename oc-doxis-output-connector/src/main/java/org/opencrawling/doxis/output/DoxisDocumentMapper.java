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

import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.content.ContentPlan;
import org.opencrawling.doxis.client.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Maps an OIS {@link RepositoryDocument} and its {@link ContentPlan} onto Doxis {@code RestDocumentParams} /
 * {@code RestDocumentVersionParams} and typed {@code AttributeParams}.
 *
 * <p>Descriptor sources: the external id (lookup key for upserts and tombstones), the title, the source URI (optional
 * {@code reference-attribute}) and {@code attribute-mapping} entries ({@code <OIS metadata key>: <Doxis attribute>}); the
 * pseudo-keys {@code id}, {@code uri} and {@code lastModified} map document fields.
 */
public class DoxisDocumentMapper {

    private static final Logger log = LoggerFactory.getLogger(DoxisDocumentMapper.class);
    private static final String NUL_CHAR = Character.toString(0);

    private final DoxisOutputProperties properties;
    private final DoxisSchema schema;

    public DoxisDocumentMapper(DoxisOutputProperties properties, DoxisSchema schema) {
        this.properties = properties;
        this.schema = schema;
    }

    /**
     * {@code RestDocumentParams} for a new document.
     */
    public Map<String, Object> documentParams(RepositoryDocument document, ContentPlan plan, String documentTypeId)
            throws IOException, InterruptedException {
        Map<String, Object> params = contentParams(plan);
        params.put("documentTypeUUID", documentTypeId);
        params.put("attributes", attributes(document, plan));
        return params;
    }

    /**
     * {@code RestDocumentVersionParams} for a new version of an existing document.
     */
    public Map<String, Object> versionParams(RepositoryDocument document, ContentPlan plan)
            throws IOException, InterruptedException {
        Map<String, Object> params = contentParams(plan);
        params.put("attributes", attributes(document, plan));
        params.put("versionComment", "OpenCrawling re-crawl " + Instant.now());
        return params;
    }

    /**
     * The descriptor value stored for (and searched by) the external id. Ids longer than the attribute allows are replaced by
     * a deterministic {@code h:}-prefixed SHA-256 prefix so that lookups keep working.
     */
    public String externalIdValue(String documentId) throws IOException, InterruptedException {
        return fitValue(schema.attribute(properties.externalIdAttribute()), documentId);
    }

    /**
     * {@code value} if it fits the descriptor, else a deterministic {@code h:}-prefixed SHA-256 prefix (for lookup keys).
     */
    public String fitValue(DoxisSchema.Attribute attribute, String value) {
        return fitValue(attribute, value, true);
    }

    /**
     * Fits {@code value} into the descriptor's length: hashed (lookup keys) or truncated (display values).
     */
    public String fitValue(DoxisSchema.Attribute attribute, String value, boolean hash) {
        int max = attribute.length() > 0 ? attribute.length() : Integer.MAX_VALUE;
        if (value.length() <= max) {
            return value;
        }
        if (!hash) {
            return value.substring(0, max);
        }
        String hex = sha256Hex(value);
        return "h:" + hex.substring(0, Math.min(hex.length(), Math.max(8, max - 2)));
    }

    /**
     * CQL statement that finds the document(s) carrying the given external id in the repository.
     */
    public String lookupStatement(String repositoryName, String documentId) throws IOException, InterruptedException {
        DoxisSchema.Attribute attribute = schema.attribute(properties.externalIdAttribute());
        String field = attribute.shortName() != null ? attribute.shortName() : attribute.name();
        return "SELECT * FROM " + repositoryName + " WHERE " + field + " = '"
                + externalIdValue(documentId).replace("'", "''") + "'";
    }

    /**
     * Typed {@code AttributeParams} for the document.
     */
    public List<Map<String, Object>> attributes(RepositoryDocument document, ContentPlan plan)
            throws IOException, InterruptedException {
        Map<String, Map<String, Object>> byAttribute = new LinkedHashMap<>();
        put(byAttribute, properties.externalIdAttribute(), List.of(externalIdValue(document.id())));
        put(byAttribute, properties.titleAttribute(), List.of(title(document, plan)));
        if (properties.referenceAttribute() != null && document.uri() != null) {
            put(byAttribute, properties.referenceAttribute(), List.of(document.uri()));
        }
        if (properties.content().changeMarkerAttribute() != null) {
            put(byAttribute, properties.content().changeMarkerAttribute(), List.of(changeMarker(document, plan)));
        }
        for (Map.Entry<String, String> mapping : properties.attributeMapping().entrySet()) {
            List<String> values = sourceValues(document, mapping.getKey());
            if (!values.isEmpty()) {
                put(byAttribute, mapping.getValue(), values);
            }
        }
        return new ArrayList<>(byAttribute.values());
    }

    /**
     * {@code <lastModified>|<length>} of the source — cheap to compute (no content read) and stored in
     * {@code content.change-marker-attribute} so unchanged re-crawls can skip creating a new version.
     */
    public static String changeMarker(RepositoryDocument document, ContentPlan plan) {
        return (document.lastModified() != null ? document.lastModified().toString() : "") + "|"
                + (plan.length() != null ? plan.length() : "");
    }

    private Map<String, Object> contentParams(ContentPlan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mimeTypeName", plan.mimeType());
        if (plan.fileName() != null) {
            params.put("fullFileName", plan.fileName());
            int dot = plan.fileName().lastIndexOf('.');
            if (dot > 0 && dot < plan.fileName().length() - 1) {
                params.put("fileExtension", plan.fileName().substring(dot + 1));
            }
        }
        if (plan.strategy() != ContentStrategy.REFERENCE_ONLY) {
            if (plan.length() != null) {
                params.put("contentLength", plan.length());
            }
            if (plan.sha256() != null) {
                params.put("hashAlgorithm", DoxisConstants.HASH_ALGORITHM_SHA256);
                params.put("hashValue", plan.sha256());
            }
        }
        if (plan.strategy() == ContentStrategy.PREDEFINED_LOCATOR) {
            params.put("predefinedLocator", plan.locator());
        }
        return params;
    }

    private void put(Map<String, Map<String, Object>> target, String attributeName, List<String> rawValues)
            throws IOException, InterruptedException {
        Optional<DoxisSchema.Attribute> found = schema.findAttribute(attributeName);
        if (found.isEmpty()) {
            log.warn("Doxis attribute '{}' does not exist; mapping skipped.", attributeName);
            return;
        }
        DoxisSchema.Attribute attribute = found.get();
        List<Object> values = new ArrayList<>();
        for (String raw : attribute.multiValue() ? rawValues : rawValues.subList(0, 1)) {
            Object value = convert(attribute, raw);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attributeDefinitionUUID", attribute.uuid());
        params.put("attributeDataType", attribute.dataType());
        params.put("values", values);
        target.put(attribute.uuid(), params);
    }

    private Object convert(DoxisSchema.Attribute attribute, String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace(NUL_CHAR, "").strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return switch (attribute.dataType()) {
                case "INTEGER" -> Integer.parseInt(value);
                case "LONGINTEGER" -> Long.parseLong(value);
                case "FLOATINGPOINT" -> Double.parseDouble(value);
                case "DATE", "DATETIME" -> epochMillis(value);
                case "BOOL" -> Boolean.parseBoolean(value) || "1".equals(value) || "y".equalsIgnoreCase(value);
                default -> {
                    if (attribute.length() > 0 && value.length() > attribute.length()) {
                        log.debug("Truncating value for Doxis attribute {} to {} characters.", attribute.name(), attribute.length());
                        yield value.substring(0, attribute.length());
                    }
                    yield value;
                }
            };
        } catch (RuntimeException e) {
            log.warn("Value '{}' is not a valid {} for Doxis attribute {}; skipped.", value, attribute.dataType(), attribute.name());
            return null;
        }
    }

    private static long epochMillis(String value) {
        if (value.chars().allMatch(Character::isDigit) && value.length() > 8) {
            return Long.parseLong(value);
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            // try other ISO forms
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // try date only
        }
        return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static List<String> sourceValues(RepositoryDocument document, String key) {
        switch (key) {
            case "id":
                return List.of(document.id());
            case "uri":
                return document.uri() != null ? List.of(document.uri()) : List.of();
            case "lastModified":
                return document.lastModified() != null ? List.of(document.lastModified().toString()) : List.of();
            default:
                List<String> values = document.metadata() != null ? document.metadata().get(key) : null;
                return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
        }
    }

    private static String title(RepositoryDocument document, ContentPlan plan) {
        Map<String, List<String>> metadata = document.metadata() != null ? document.metadata() : Map.of();
        for (String key : List.of(DoxisConstants.META_TITLE, DoxisConstants.META_NAME)) {
            List<String> values = metadata.get(key);
            if (values != null && !values.isEmpty() && values.getFirst() != null && !values.getFirst().isBlank()) {
                return values.getFirst();
            }
        }
        return plan.fileName() != null ? plan.fileName() : document.id();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
