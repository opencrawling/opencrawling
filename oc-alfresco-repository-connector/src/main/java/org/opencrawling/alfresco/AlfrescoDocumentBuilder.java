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
package org.opencrawling.alfresco;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds OIS {@link RepositoryDocument} instances, tombstones, and {@link ConnectorSchema} metadata for Alfresco
 * Content Services nodes (#425).
 */
public final class AlfrescoDocumentBuilder {

    /** Alfresco writes timezone offsets without a colon ({@code 2026-11-05T10:00:00.000+0000}). */
    static final DateTimeFormatter ALFRESCO_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX][XX]");

    private static final Pattern SITE_PATH_PATTERN =
            Pattern.compile("/Sites/([^/]+)(?:/|$)", Pattern.CASE_INSENSITIVE);

    private AlfrescoDocumentBuilder() {
    }

    /** Parses an Alfresco ISO timestamp (supporting both {@code +0000} and {@code Z} / {@code +00:00}). */
    public static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        try {
            return OffsetDateTime.parse(trimmed, ALFRESCO_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Full repository path for a node entry ({@code path.name + "/" + name}), or {@code null} when absent. */
    public static String pathOf(JsonNode entry) {
        String parentPath = entry.path("path").path("name").asText("");
        if (parentPath.isBlank()) {
            return null;
        }
        String name = entry.path("name").asText("");
        if (name.isBlank()) {
            return parentPath;
        }
        return (parentPath.endsWith("/") ? parentPath : parentPath + "/") + name;
    }

    /** Extracts the Alfresco site short name from a repository path such as {@code /Company Home/Sites/finance/documentLibrary/...}. */
    public static String extractSiteId(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) {
            return null;
        }
        Matcher m = SITE_PATH_PATTERN.matcher(fullPath);
        return m.find() ? m.group(1) : null;
    }

    /** Extracts the string list of {@code aspectNames} from a node entry. */
    public static List<String> aspectNames(JsonNode entry) {
        JsonNode node = entry.path("aspectNames");
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> aspects = new ArrayList<>();
        node.forEach(a -> {
            String text = a.asText("");
            if (!text.isBlank()) {
                aspects.add(text);
            }
        });
        return List.copyOf(aspects);
    }

    /** Extracts the node's version label ({@code properties["cm:versionLabel"]}) or empty string. */
    public static String versionLabel(JsonNode entry) {
        return entry.path("properties").path("cm:versionLabel").asText("");
    }

    /** Canonical OIS document ID for a live node or a specific historical version. */
    public static String documentId(String nodeId, String versionId, boolean multiVersion) {
        if (multiVersion && versionId != null && !versionId.isBlank()) {
            return nodeId + ";v=" + versionId;
        }
        return nodeId;
    }

    /** Builds a live or versioned OIS {@link RepositoryDocument}. */
    public static RepositoryDocument buildDocument(
            AlfrescoProperties settings,
            JsonNode entry,
            JsonNode versionEntry,
            boolean multiVersion,
            String effectiveRendition,
            String effectiveMimeType,
            long effectiveSizeBytes,
            List<String> tags,
            List<String> comments,
            AlfrescoSecurityMapper.SecurityResult securityResult,
            InputStream contentStream
    ) {
        String nodeId = entry.path("id").asText();
        String versionId = versionEntry != null ? versionEntry.path("id").asText("") : "";
        String docId = documentId(nodeId, versionId, multiVersion);
        String name = entry.path("name").asText();
        String nodeType = entry.path("nodeType").asText();

        String modifiedAtStr = versionEntry != null && versionEntry.hasNonNull("modifiedAt")
                ? versionEntry.path("modifiedAt").asText()
                : entry.path("modifiedAt").asText();
        Instant modifiedAt = parseTimestamp(modifiedAtStr);
        if (modifiedAt == null) {
            modifiedAt = Instant.now();
        }

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("name", List.of(name));
        metadata.put("nodeType", List.of(nodeType));
        metadata.put("alfresco_node_id", List.of(nodeId));
        metadata.put("alfresco_node_type", List.of(nodeType));

        if (entry.hasNonNull("parentId")) {
            metadata.put("alfresco_parent_id", List.of(entry.path("parentId").asText()));
        }

        String fullPath = pathOf(entry);
        if (fullPath != null && !fullPath.isBlank()) {
            metadata.put("alfresco_path", List.of(fullPath));
            metadata.put("path", List.of(fullPath));
            String site = extractSiteId(fullPath);
            if (site != null) {
                metadata.put("alfresco_site", List.of(site));
            }
        }

        String createdAtStr = entry.path("createdAt").asText("");
        if (!createdAtStr.isBlank()) {
            metadata.put("alfresco_created_at", List.of(createdAtStr));
        }
        if (modifiedAtStr != null && !modifiedAtStr.isBlank()) {
            metadata.put("alfresco_modified_at", List.of(modifiedAtStr));
        }
        String createdBy = entry.path("createdByUser").path("id").asText("");
        if (!createdBy.isBlank()) {
            metadata.put("alfresco_created_by", List.of(createdBy));
        }
        String modifiedBy = (versionEntry != null ? versionEntry : entry).path("modifiedByUser").path("id").asText("");
        if (!modifiedBy.isBlank()) {
            metadata.put("alfresco_modified_by", List.of(modifiedBy));
        }

        JsonNode contentNode = versionEntry != null && versionEntry.has("content")
                ? versionEntry.path("content")
                : entry.path("content");
        if (effectiveMimeType != null && !effectiveMimeType.isBlank()) {
            metadata.put("mimeType", List.of(effectiveMimeType));
        } else if (contentNode != null && !contentNode.isMissingNode()) {
            String mimeType = contentNode.path("mimeType").asText("");
            if (!mimeType.isBlank()) {
                metadata.put("mimeType", List.of(mimeType));
            }
        }
        if (effectiveSizeBytes >= 0 && (contentNode != null && !contentNode.isMissingNode())) {
            metadata.put("sizeInBytes", List.of(String.valueOf(effectiveSizeBytes)));
        }
        if (contentNode != null && !contentNode.isMissingNode()) {
            String encoding = contentNode.path("encoding").asText("");
            if (!encoding.isBlank()) {
                metadata.put("alfresco_content_encoding", List.of(encoding));
            }
        }

        // Properties mapping (preserving multi-valued JSON arrays)
        JsonNode propertiesNode = versionEntry != null && versionEntry.has("properties")
                ? versionEntry.path("properties")
                : entry.path("properties");
        if (propertiesNode != null && propertiesNode.isObject()) {
            String prefix = settings.propertyPrefix();
            propertiesNode.properties().forEach(prop -> {
                String rawKey = prop.getKey();
                if (!settings.includeSystemProperties() && rawKey.startsWith("sys:")) {
                    return;
                }
                List<String> values = extractPropertyValues(prop.getValue());
                if (!values.isEmpty()) {
                    String targetKey = (prefix != null && !prefix.isEmpty()) ? prefix + rawKey : rawKey;
                    metadata.put(targetKey, values);
                }
            });
        }

        // Version metadata
        String vLabel = !versionId.isBlank() ? versionId : versionLabel(entry);
        if (!vLabel.isBlank()) {
            metadata.put("alfresco_version_label", List.of(vLabel));
        }
        if (versionEntry != null) {
            String vComment = versionEntry.path("versionComment").asText("");
            if (!vComment.isBlank()) {
                metadata.put("alfresco_version_comment", List.of(vComment));
            }
            String vType = versionEntry.path("properties").path("cm:versionType").asText("");
            if (!vType.isBlank()) {
                metadata.put("alfresco_version_type", List.of(vType));
            }
        }

        if (effectiveRendition != null && !effectiveRendition.isBlank()) {
            metadata.put("alfresco_rendition", List.of(effectiveRendition));
        }

        // Aspects mapping
        List<String> aspects = aspectNames(entry);
        if (!aspects.isEmpty()) {
            metadata.put("alfresco_aspects", aspects);
        }

        if (tags != null && !tags.isEmpty()) {
            metadata.put("alfresco_tags", List.copyOf(tags));
        }
        if (comments != null && !comments.isEmpty()) {
            metadata.put("alfresco_comments", List.copyOf(comments));
        }

        if (settings.webAppUrl() != null && !settings.webAppUrl().isBlank()) {
            metadata.put("alfresco_web_url", List.of(settings.webAppUrl() + "/#/libraries/viewer/" + nodeId));
        }

        // Security metadata
        metadata.put("alfresco_is_inherited", List.of(String.valueOf(securityResult.isInherited())));
        if (!securityResult.identityUsers().isEmpty()) {
            metadata.put("alfresco_identity_users", List.of(String.join(",", securityResult.identityUsers())));
        }
        if (!securityResult.identityGroups().isEmpty()) {
            metadata.put("alfresco_identity_groups", List.of(String.join(",", securityResult.identityGroups())));
        }

        String contentUri;
        if (effectiveRendition != null && !effectiveRendition.isBlank()) {
            contentUri = settings.coreApiUrl() + "/nodes/" + nodeId + "/renditions/" + effectiveRendition + "/content";
        } else if (versionEntry != null && !versionId.isBlank() && (multiVersion || !versionId.equals(versionLabel(entry)))) {
            contentUri = settings.coreApiUrl() + "/nodes/" + nodeId + "/versions/" + versionId + "/content";
        } else {
            contentUri = settings.coreApiUrl() + "/nodes/" + nodeId + "/content";
        }

        return new RepositoryDocument(
                docId,
                contentUri,
                contentStream,
                metadata,
                securityResult.aclString(),
                securityResult.securityConfig(),
                modifiedAt,
                DocumentAction.UPSERT
        );
    }

    /** Creates an OIS {@link DocumentAction#DELETE} tombstone for a removed, trashed, or out-of-scope Alfresco document. */
    public static RepositoryDocument buildTombstone(AlfrescoProperties settings, String docId) {
        String nodeId = docId.contains(";v=") ? docId.substring(0, docId.indexOf(";v=")) : docId;
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("alfresco_node_id", List.of(nodeId));
        return new RepositoryDocument(
                docId,
                settings.coreApiUrl() + "/nodes/" + nodeId + "/content",
                null,
                metadata,
                "",
                SecurityConfig.none(),
                Instant.now(),
                DocumentAction.DELETE
        );
    }

    /** Schema exposed by {@link AlfrescoRepositoryConnector#getSchema(String)}. */
    public static ConnectorSchema schema() {
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("alfresco_node_id", "STRING", "Alfresco node UUID"),
                new ConnectorSchema.SchemaField("alfresco_node_type", "STRING", "Alfresco content model type (e.g. cm:content)"),
                new ConnectorSchema.SchemaField("alfresco_parent_id", "STRING", "Parent folder node UUID"),
                new ConnectorSchema.SchemaField("alfresco_path", "STRING", "Full repository path (/Company Home/...)"),
                new ConnectorSchema.SchemaField("alfresco_site", "STRING", "Alfresco site short name when inside a site"),
                new ConnectorSchema.SchemaField("name", "STRING", "Document filename"),
                new ConnectorSchema.SchemaField("mimeType", "STRING", "Document or rendition MIME type"),
                new ConnectorSchema.SchemaField("sizeInBytes", "LONG", "Binary content size in bytes"),
                new ConnectorSchema.SchemaField("alfresco_content_encoding", "STRING", "Content character encoding"),
                new ConnectorSchema.SchemaField("cm:title", "STRING", "Document title (cm:titled aspect)"),
                new ConnectorSchema.SchemaField("cm:description", "STRING", "Document description (cm:titled aspect)"),
                new ConnectorSchema.SchemaField("cm:author", "STRING", "Document author (cm:author aspect)"),
                new ConnectorSchema.SchemaField("alfresco_version_label", "STRING", "Version label (e.g. 1.0, 2.1)"),
                new ConnectorSchema.SchemaField("alfresco_version_type", "STRING", "Version type (MAJOR or MINOR)"),
                new ConnectorSchema.SchemaField("alfresco_version_comment", "STRING", "Version check-in comment"),
                new ConnectorSchema.SchemaField("alfresco_rendition", "STRING", "Rendition ID indexed when prefer-rendition is active (e.g. pdf)"),
                new ConnectorSchema.SchemaField("alfresco_aspects", "STRING", "Applied Alfresco aspects (multi-valued)"),
                new ConnectorSchema.SchemaField("alfresco_tags", "STRING", "Node tags (multi-valued)"),
                new ConnectorSchema.SchemaField("alfresco_comments", "STRING", "Node comments (multi-valued)"),
                new ConnectorSchema.SchemaField("alfresco_created_at", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("alfresco_modified_at", "TIMESTAMP", "Last modification timestamp"),
                new ConnectorSchema.SchemaField("alfresco_created_by", "STRING", "Creator username"),
                new ConnectorSchema.SchemaField("alfresco_modified_by", "STRING", "Last modifier username"),
                new ConnectorSchema.SchemaField("alfresco_web_url", "STRING", "Deep link to Alfresco Digital Workspace / ACA"),
                new ConnectorSchema.SchemaField("alfresco_is_inherited", "BOOLEAN", "Whether ACL inheritance is enabled on the node"),
                new ConnectorSchema.SchemaField("alfresco_identity_users", "STRING", "Comma-separated authorized user principals"),
                new ConnectorSchema.SchemaField("alfresco_identity_groups", "STRING", "Comma-separated authorized group principals")
        ));
    }

    static List<String> extractPropertyValues(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    String text = item.isValueNode() ? item.asText() : item.toString();
                    if (text != null && !text.isEmpty()) {
                        values.add(text);
                    }
                }
            }
            return values;
        }
        String text = node.isValueNode() ? node.asText() : node.toString();
        return text == null ? List.of() : List.of(text);
    }
}
