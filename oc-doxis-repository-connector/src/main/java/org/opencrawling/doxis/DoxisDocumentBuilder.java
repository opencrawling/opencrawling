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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.client.schema.DoxisSchema;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds OIS {@link RepositoryDocument}s from Doxis {@code DocumentWsTO}s.
 *
 * <p>Descriptors are emitted as {@code <descriptorPrefix><Doxis name>}, their values normalised by data type
 * ({@link DoxisValues}: dates, date-times, numbers, booleans); a value that changed is also kept as
 * {@code doxis.raw.<Doxis name>}. The default empty prefix gives the Doxis names as-is
 * ({@code ObjectName}, {@code ObjectDate}, …), the keys the ManifoldCF Doxis 4 connector emits; {@code doxis_desc_} gives the
 * issue #122 style. System data goes under {@code doxis.*} ({@code documentId}, {@code documentClass}, {@code version},
 * {@code isLatestVersion}, {@code createdBy}, {@code modifiedBy}, {@code parentFolderId}, {@code parentFolderName}, …).
 */
public final class DoxisDocumentBuilder {

    /** Metadata keys OpenCrawling itself uses; an unprefixed descriptor with one of these names becomes {@code doxis.attr.<name>}. */
    static final Set<String> RESERVED_KEYS = Set.of("name", "title", "mimeType", "acl", "security", "uri", "id");

    /** Resolves an {@code attributeDefinitionUUID} to the descriptor's definition (name and data type). */
    @FunctionalInterface
    public interface AttributeResolver {
        Optional<DoxisSchema.Attribute> attribute(String attributeDefinitionUuid) throws IOException, InterruptedException;
    }

    /** The binary chosen for a document version: the default representation's first content object. */
    public record ContentRef(String versionNr, String representationId, String contentObjectId, String mimeType,
                             String fileName, long length, String contentLink) {
        public boolean isLink() {
            return contentLink != null;
        }
    }

    /** Names the connector resolved for a document (class, owner, last modifier, e-file); any may be null. */
    public record Context(String customerName, String repositoryShortName, String documentClass, String createdBy,
                          String modifiedBy, String parentFolderId, String parentFolderName) {
    }

    private DoxisDocumentBuilder() {
    }

    /**
     * {@code doxis://<customer>/<repository>/documents/<uuid>}; an older version (ALL_VERSIONS mode) gets
     * {@code …/versions/<n>}, the current version keeps the plain id.
     */
    public static String documentId(String customerName, String repositoryShortName, String documentUuid) {
        return "doxis://" + customerName + "/" + repositoryShortName + "/documents/" + documentUuid;
    }

    public static String versionId(String customerName, String repositoryShortName, String documentUuid, String versionNumber) {
        return documentId(customerName, repositoryShortName, documentUuid) + "/versions/" + versionNumber;
    }

    public static RepositoryDocument tombstone(String id, String customerName, String repositoryShortName, String documentUuid) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("doxis.documentId", List.of(documentUuid));
        metadata.put("doxis.status", List.of("DELETED"));
        metadata.put("doxis.customer", List.of(customerName));
        metadata.put("doxis.repository", List.of(repositoryShortName));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);
    }

    /**
     * The current version of a {@code DocumentWsTO}: the one flagged {@code currentVersion}, otherwise the last one listed.
     */
    public static Optional<JsonNode> currentVersion(JsonNode document) {
        JsonNode last = null;
        for (JsonNode version : document.path("versions")) {
            if (version.path("currentVersion").asBoolean(false)) {
                return Optional.of(version);
            }
            last = version;
        }
        return Optional.ofNullable(last);
    }

    /**
     * The default representation's first content object. A content object with length 0 and a file name is a content link
     * (the binary stays external; REST cannot download it, {@code SEDNA0104}); its {@code fullFilename} is the link.
     */
    public static Optional<ContentRef> content(JsonNode version) {
        JsonNode representation = null;
        for (JsonNode candidate : version.path("representations")) {
            if (candidate.path("defaultRepresentation").asBoolean(false)) {
                representation = candidate;
                break;
            }
            if (representation == null) {
                representation = candidate;
            }
        }
        if (representation == null) {
            return Optional.empty();
        }
        JsonNode contentObject = null;
        for (JsonNode candidate : representation.path("contentObjects")) {
            if (contentObject == null || candidate.path("index").asInt(Integer.MAX_VALUE) < contentObject.path("index").asInt(Integer.MAX_VALUE)) {
                contentObject = candidate;
            }
        }
        if (contentObject == null) {
            return Optional.empty();
        }
        String fullFilename = text(contentObject, "fullFilename");
        long length = contentObject.path("length").asLong(0);
        String mimeType = Optional.ofNullable(text(contentObject, "mimeTypeName")).orElse(text(representation, "mimeTypeName"));
        String link = length == 0 && fullFilename != null ? fullFilename : null;
        return Optional.of(new ContentRef(text(version, "versionNumber"), text(representation, "uuid"), text(contentObject, "uuid"),
                mimeType, fullFilename == null ? null : baseName(fullFilename), length, link));
    }

    public static RepositoryDocument build(String id, Context context, JsonNode document, JsonNode version, boolean latest,
                                           ContentRef content, boolean includeDescriptors, String descriptorPrefix,
                                           AttributeResolver attributes, InputStream contentStream, SecurityConfig security)
            throws IOException, InterruptedException {
        String uuid = text(document, "uuid");
        String prefix = descriptorPrefix == null ? "" : descriptorPrefix;
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        String title = null;

        for (JsonNode attribute : version.path("attributes")) {
            Optional<DoxisSchema.Attribute> definition = attributes.attribute(attribute.path("attributeDefinitionUUID").asText(""));
            if (definition.isEmpty()) {
                continue;
            }
            String name = definition.get().name();
            String dataType = definition.get().dataType();
            List<String> raw = new ArrayList<>();
            attribute.path("values").forEach(v -> {
                if (!v.isNull() && !v.asText().isEmpty()) {
                    raw.add(v.asText());
                }
            });
            if (raw.isEmpty()) {
                continue;
            }
            List<String> values = raw.stream().map(v -> DoxisValues.normalize(dataType, v)).toList();
            if ("ObjectName".equals(name)) {
                title = values.getFirst();
            }
            if (includeDescriptors) {
                String key = prefix.isEmpty() && RESERVED_KEYS.contains(name) ? "doxis.attr." + name : prefix + name;
                metadata.put(key, values);
                if (!values.equals(raw)) {
                    metadata.put("doxis.raw." + name, raw);
                }
            }
        }

        String fileName = content != null ? content.fileName() : null;
        metadata.put("name", List.of(fileName != null && !fileName.isBlank() ? fileName : title != null ? title : uuid));
        if (title != null) {
            metadata.put("title", List.of(title));
        }
        if (content != null && content.mimeType() != null) {
            metadata.put("mimeType", List.of(content.mimeType()));
        }

        put(metadata, "doxis.documentId", uuid);
        put(metadata, "doxis.customer", context.customerName());
        put(metadata, "doxis.repository", context.repositoryShortName());
        put(metadata, "doxis.documentClass", context.documentClass());
        put(metadata, "doxis.documentClassId", text(document, "documentTypeUUID"));
        put(metadata, "doxis.version", text(version, "versionNumber"));
        put(metadata, "doxis.isLatestVersion", Boolean.toString(latest));
        put(metadata, "doxis.createdDate", text(document, "creationDate"));
        put(metadata, "doxis.createdBy", context.createdBy());
        put(metadata, "doxis.modifiedDate", Optional.ofNullable(text(version, "modificationDate")).orElse(text(document, "modificationDate")));
        put(metadata, "doxis.modifiedBy", context.modifiedBy());
        put(metadata, "doxis.parentFolderId", context.parentFolderId());
        put(metadata, "doxis.parentFolderName", context.parentFolderName());
        put(metadata, "doxis.lifecycleState", text(document, "lifecycleState"));
        if (content != null) {
            put(metadata, "doxis.fileName", content.fileName());
            put(metadata, "doxis.fileSize", Long.toString(content.length()));
            put(metadata, "doxis.contentLink", content.contentLink());
        }

        Instant lastModified = parseDate(text(version, "modificationDate"))
                .or(() -> parseDate(text(document, "modificationDate")))
                .orElseGet(Instant::now);
        return new RepositoryDocument(id, id, contentStream, metadata, "", security, lastModified, DocumentAction.UPSERT);
    }

    static Optional<Instant> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(value));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }
}
