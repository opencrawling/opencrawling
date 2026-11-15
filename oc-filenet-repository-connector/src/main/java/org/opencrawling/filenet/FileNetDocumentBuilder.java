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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

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
 * Builds OIS documents from FileNet document versions.
 *
 * <p>Ids: {@code filenet://<object-store>/documents/<version-series-id>} in {@code current} and {@code released} version
 * modes, so a new version replaces the previous one downstream (in FileNet every version is its own object with its own
 * id), and {@code .../documents/<version-series-id>/versions/<version-id>} in {@code all} mode. A document's first content
 * element (sequence 0) carries that id; each further element of a multi-element document is its own OIS document at
 * {@code <id>/elements/<sequence>} with {@code filenet.parentId}. A document without content is indexed by its metadata.
 * Braces are removed from GUIDs in ids.
 */
final class FileNetDocumentBuilder {

    /** Content Engine system properties: mapped to {@code filenet.*} fields or not useful as metadata; skipped by default. */
    static final Set<String> SYSTEM_PROPERTIES = Set.of(
            "Id", "This", "ClassDescription", "ObjectStore", "Name", "DocumentTitle", "Creator", "DateCreated",
            "LastModifier", "DateLastModified", "Owner", "Permissions", "AccessMask", "ActiveMarkings", "Annotations",
            "AuditedEvents", "MajorVersionNumber", "MinorVersionNumber", "IsCurrentVersion", "VersionStatus", "ContentSize",
            "MimeType", "VersionSeries", "Versions", "CurrentVersion", "ReleasedVersion", "Reservation", "ReservationType",
            "IsReserved", "IsVersioningEnabled", "IsFrozenVersion", "ContentElements", "ContentElementsPresent",
            "ContentRetentionDate", "CurrentState", "DateCheckedIn", "DateContentLastAccessed", "IsInExceptionState",
            "LockOwner", "LockTimeout", "LockToken", "SecurityFolder", "SecurityParent", "SecurityPolicy", "StorageArea",
            "StoragePolicy", "StorageLocation", "UpdateSequenceNumber", "ReplicationGroup", "IndexationId",
            "CmIndexingFailureCode", "CmRetentionDate", "CmIsMarkedForDeletion", "CmThumbnails", "CmHoldRelationships",
            "CompoundDocumentState", "ClassificationStatus", "PublicationInfo", "PublishingSubsidiaryFolder",
            "EntryTemplateId", "EntryTemplateObjectStoreName", "EntryTemplateLaunchedWorkflowNumber", "IgnoreRedirect",
            "ChildDocuments", "ChildRelationships", "ParentDocuments", "ParentRelationships", "Containers",
            "DependentDocuments", "DestinationDocuments", "DocumentLifecyclePolicy", "FoldersFiledIn", "OwnerDocument",
            "WorkflowSubscriptions", "SourceDocument", "ComponentBindingLabel", "ExternalReplicaIdentities",
            "ReplicationStatus");

    private final FileNetProperties settings;

    FileNetDocumentBuilder(FileNetProperties settings) {
        this.settings = settings;
    }

    /** The OIS id of a document version: per version series, or per version in {@code all} version mode. */
    String id(JsonNode doc) {
        String base = "filenet://" + settings.objectStoreKey() + "/documents/" + key(seriesId(doc));
        return settings.versionMode() == FileNetProperties.VersionMode.ALL ? base + "/versions/" + key(doc.path("id").asText())
                : base;
    }

    /** The crawl-state key: the version series, or the version in {@code all} mode. */
    String stateKey(JsonNode doc) {
        return settings.versionMode() == FileNetProperties.VersionMode.ALL ? doc.path("id").asText() : seriesId(doc);
    }

    static String seriesId(JsonNode doc) {
        String series = doc.path("versionSeries").path("id").asText("");
        return series.isEmpty() ? doc.path("id").asText() : series;
    }

    static String elementId(String documentId, int sequence) {
        return sequence == 0 ? documentId : documentId + "/elements/" + sequence;
    }

    private static String key(String guid) {
        return FileNetProperties.stripBraces(guid);
    }

    /**
     * One OIS document for a version, or for one of its content elements.
     *
     * @param element the content element, or null for a document indexed by its metadata
     * @param content the element's content, or null
     */
    RepositoryDocument build(JsonNode doc, JsonNode element, int elementCount, InputStream content, List<String> folderPaths,
                             FileNetSecurityMapper.Mapped security) {
        String documentId = id(doc);
        int sequence = element == null ? 0 : element.path("elementSequenceNumber").asInt(0);
        String id = elementId(documentId, sequence);
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        String title = text(doc, "name");
        String retrievalName = element == null ? null : text(element, "retrievalName");
        put(metadata, "name", retrievalName != null ? retrievalName : title);
        put(metadata, "title", title);
        String mime = element != null ? text(element, "contentType") : text(doc, "mimeType");
        put(metadata, "mimeType", mime);
        put(metadata, "filenet.objectStore", settings.objectStore());
        put(metadata, "filenet.id", text(doc, "id"));
        put(metadata, "filenet.versionSeriesId", seriesId(doc));
        put(metadata, "filenet.name", title);
        put(metadata, "filenet.class", text(doc, "className"));
        put(metadata, "filenet.creator", text(doc, "creator"));
        put(metadata, "filenet.dateCreated", instant(text(doc, "dateCreated")).map(Instant::toString).orElse(null));
        put(metadata, "filenet.lastModifier", text(doc, "lastModifier"));
        put(metadata, "filenet.dateLastModified", instant(text(doc, "dateLastModified")).map(Instant::toString).orElse(null));
        put(metadata, "filenet.majorVersion", text(doc, "majorVersionNumber"));
        put(metadata, "filenet.minorVersion", text(doc, "minorVersionNumber"));
        if (doc.hasNonNull("majorVersionNumber")) {
            put(metadata, "filenet.versionLabel", doc.path("majorVersionNumber").asInt() + "."
                    + doc.path("minorVersionNumber").asInt());
        }
        put(metadata, "filenet.isCurrentVersion", text(doc, "isCurrentVersion"));
        put(metadata, "filenet.versionStatus", text(doc, "versionStatus"));
        if (doc.path("contentSize").isNumber()) {
            put(metadata, "filenet.contentSize", Long.toString(doc.path("contentSize").asLong()));
        }
        if (!folderPaths.isEmpty()) {
            metadata.put("filenet.folderPath", List.copyOf(folderPaths));
        }
        put(metadata, "filenet.elementCount", Integer.toString(elementCount));
        if (element != null) {
            put(metadata, "filenet.elementSequenceNumber", Integer.toString(sequence));
            put(metadata, "filenet.retrievalName", retrievalName);
            if (element.path("contentSize").isNumber()) {
                put(metadata, "filenet.elementSize", Long.toString(element.path("contentSize").asLong()));
            }
            put(metadata, "filenet.contentLocation", text(element, "contentLocation"));
        }
        if (sequence > 0) {
            put(metadata, "filenet.parentId", documentId);
        }
        if (!security.users().isEmpty()) {
            metadata.put("filenet_identity_users", security.users());
        }
        if (!security.groups().isEmpty()) {
            metadata.put("filenet_identity_groups", security.groups());
        }
        if (settings.includeCustomProperties()) {
            properties(doc.path("properties"), metadata);
        }
        Instant lastModified = instant(text(doc, "dateLastModified")).orElseGet(Instant::now);
        return new RepositoryDocument(id, id, content, metadata, security.acl(), security.security(), lastModified,
                DocumentAction.UPSERT);
    }

    /** Class properties as {@code <prefix><camelCaseSymbolicName>}; system properties only when configured. */
    private void properties(JsonNode properties, Map<String, List<String>> metadata) {
        String prefix = settings.propertyPrefix() == null ? "" : settings.propertyPrefix();
        for (JsonNode p : properties) {
            String name = p.path("id").asText("");
            String type = p.path("type").asText("");
            if (name.isEmpty() || type.equals("OBJECT") || type.equals("BINARY")
                    || (!settings.includeSystemProperties() && SYSTEM_PROPERTIES.contains(name))) {
                continue;
            }
            List<String> values = values(p.path("value"), type);
            if (!values.isEmpty()) {
                metadata.put(prefix + camel(name), values);
            }
        }
    }

    /** A property value as strings: lists flattened, dates as ISO instants; objects and nulls give nothing. */
    static List<String> values(JsonNode value, String type) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isNull() || value.isMissingNode()) {
            return out;
        }
        if (value.isArray()) {
            value.forEach(v -> out.addAll(values(v, type)));
            return out;
        }
        if (value.isObject()) {
            return out;
        }
        String text = value.asText();
        if (type.equals("DATE")) {
            text = instant(text).map(Instant::toString).orElse(text);
        } else if (value.isDouble() && value.asDouble() == Math.rint(value.asDouble()) && Math.abs(value.asDouble()) < 1e15) {
            text = Long.toString(value.asLong()); // 4500000.0 -> 4500000
        }
        if (!text.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    /** {@code LoanNumber} → {@code loanNumber}, {@code URLAddress} → {@code urlAddress}, {@code ID} → {@code id}. */
    static String camel(String symbolicName) {
        int upper = 0;
        while (upper < symbolicName.length() && Character.isUpperCase(symbolicName.charAt(upper))) {
            upper++;
        }
        if (upper == 0) {
            return symbolicName;
        }
        int cut = upper == symbolicName.length() || upper == 1 ? upper : upper - 1;
        return symbolicName.substring(0, cut).toLowerCase(java.util.Locale.ROOT) + symbolicName.substring(cut);
    }

    static RepositoryDocument tombstone(String id, String objectStore, String seriesOrVersionId) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("filenet.objectStore", List.of(objectStore));
        if (seriesOrVersionId != null) {
            metadata.put("filenet.id", List.of(seriesOrVersionId));
        }
        metadata.put("filenet.status", List.of("DELETED"));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(),
                DocumentAction.DELETE);
    }

    /** A FileNet DateTime: ISO-8601 with an offset or Z, with or without fractions. */
    static Optional<Instant> instant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.strip()).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(value.strip()));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, List.of(value));
        }
    }
}
