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
package org.opencrawling.sharepoint;

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
import java.util.Set;

/**
 * Builds OIS {@link RepositoryDocument}s from Graph {@code driveItem}s.
 *
 * <p>Ids are {@code sharepoint://<host>/drives/<driveId>/items/<itemId>}. Drive item ids are stable across renames and
 * moves, so a moved file keeps its id; its path is metadata ({@code sharepoint.path}). System data goes under
 * {@code sharepoint.*}; the library's list item columns go under {@code <fieldPrefix><InternalName>} (internal names, which
 * do not change when a column is renamed), with lookup, taxonomy, person and URL values reduced to their labels.
 */
public final class SharePointDocumentBuilder {

    /** Columns every library has; they duplicate driveItem data or are SharePoint bookkeeping. */
    static final Set<String> SYSTEM_FIELDS = Set.of("id", "ContentType", "Created", "Modified", "AuthorLookupId",
            "EditorLookupId", "Attachments", "Edit", "LinkFilename", "LinkFilenameNoMenu", "LinkTitle", "LinkTitleNoMenu",
            "DocIcon", "ItemChildCount", "FolderChildCount", "FileLeafRef", "FileSizeDisplay", "AppAuthorLookupId",
            "AppEditorLookupId", "ParentVersionStringLookupId", "ParentLeafNameLookupId", "CheckoutUserLookupId",
            "ComplianceAssetId");

    /** What the connector knows about the library this item lives in. */
    public record Context(String host, String siteId, String siteUrl, String driveId, String driveName, String path) {
    }

    private SharePointDocumentBuilder() {
    }

    public static String documentId(String host, String driveId, String itemId) {
        return "sharepoint://" + host + "/drives/" + driveId + "/items/" + itemId;
    }

    public static RepositoryDocument tombstone(String id, String driveId, String itemId) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("sharepoint.itemId", List.of(itemId));
        metadata.put("sharepoint.driveId", List.of(driveId));
        metadata.put("sharepoint.status", List.of("DELETED"));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);
    }

    /**
     * One OIS document for a file.
     *
     * @param fields        the item's list columns ({@code listItem.fields}), or a missing node
     * @param contentLength bytes downloaded, or {@code -1} when no content is attached
     */
    public static RepositoryDocument build(Context context, JsonNode item, JsonNode fields, boolean includeFields, String fieldPrefix,
                                           InputStream content, long contentLength, SharePointSecurityMapper.Mapped security) {
        String itemId = item.path("id").asText();
        String id = documentId(context.host(), context.driveId(), itemId);
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        String name = item.path("name").asText(itemId);
        metadata.put("name", List.of(name));
        String title = fields != null ? GraphClient.text(fields, "Title") : null;
        metadata.put("title", List.of(title != null && !title.isBlank() ? title : name));
        put(metadata, "mimeType", GraphClient.text(item.path("file"), "mimeType"));

        put(metadata, "sharepoint.itemId", itemId);
        put(metadata, "sharepoint.name", name);
        put(metadata, "sharepoint.webUrl", GraphClient.text(item, "webUrl"));
        put(metadata, "sharepoint.path", context.path());
        put(metadata, "sharepoint.siteId", context.siteId());
        put(metadata, "sharepoint.siteUrl", context.siteUrl());
        put(metadata, "sharepoint.driveId", context.driveId());
        put(metadata, "sharepoint.driveName", context.driveName());
        long size = contentLength >= 0 ? contentLength : item.path("size").asLong(-1);
        if (size >= 0) {
            put(metadata, "sharepoint.size", Long.toString(size));
        }
        put(metadata, "sharepoint.createdDateTime", GraphClient.text(item, "createdDateTime"));
        put(metadata, "sharepoint.createdBy", person(item.path("createdBy")));
        put(metadata, "sharepoint.lastModifiedDateTime", GraphClient.text(item, "lastModifiedDateTime"));
        put(metadata, "sharepoint.lastModifiedBy", person(item.path("lastModifiedBy")));
        put(metadata, "sharepoint.etag", GraphClient.text(item, "eTag"));
        put(metadata, "sharepoint.cTag", GraphClient.text(item, "cTag"));
        put(metadata, "sharepoint.listItemUniqueId", GraphClient.text(item.path("sharepointIds"), "listItemUniqueId"));
        if (!security.users().isEmpty()) {
            metadata.put("sharepoint_identity_users", List.of(String.join(",", security.users())));
        }
        if (!security.groups().isEmpty()) {
            metadata.put("sharepoint_identity_groups", List.of(String.join(",", security.groups())));
        }
        if (includeFields && fields != null && fields.isObject()) {
            fields.properties().forEach(field -> {
                String key = field.getKey();
                if (key.startsWith("@") || key.startsWith("_") || SYSTEM_FIELDS.contains(key) || "Title".equals(key)) {
                    return;
                }
                List<String> values = fieldValues(field.getValue());
                if (!values.isEmpty()) {
                    metadata.put(fieldPrefix + key, values);
                }
            });
            if (title != null && !title.isBlank()) {
                metadata.put(fieldPrefix + "Title", List.of(title));
            }
        }

        Instant lastModified = parseInstant(GraphClient.text(item, "lastModifiedDateTime"));
        return new RepositoryDocument(id, id, content, metadata, security.acl(), security.security(),
                lastModified != null ? lastModified : Instant.now(), DocumentAction.UPSERT);
    }

    /**
     * A column value as strings: scalars as text; arrays element by element; objects (lookups, managed metadata terms,
     * people, hyperlinks) by their label: {@code Label}, {@code LookupValue}, {@code Email}, {@code Description} or
     * {@code Url}.
     */
    static List<String> fieldValues(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isNull() || value.isMissingNode()) {
            return out;
        }
        if (value.isArray()) {
            value.forEach(v -> out.addAll(fieldValues(v)));
            return out;
        }
        if (value.isObject()) {
            for (String key : List.of("Label", "LookupValue", "Email", "Description", "Url")) {
                String label = GraphClient.text(value, key);
                if (label != null && !label.isBlank()) {
                    out.add(label);
                    return out;
                }
            }
            return out;
        }
        String text = value.asText();
        if (!text.isBlank()) {
            out.add(text);
        }
        return out;
    }

    /** The e-mail of an {@code identitySet}'s user, else its display name. */
    static String person(JsonNode identitySet) {
        JsonNode user = identitySet.path("user");
        String email = GraphClient.text(user, "email");
        return email != null && !email.isBlank() ? email : GraphClient.text(user, "displayName");
    }

    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }
}
