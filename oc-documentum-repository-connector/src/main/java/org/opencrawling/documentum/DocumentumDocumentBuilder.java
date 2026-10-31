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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds OIS documents from Documentum objects.
 *
 * <p>Ids: {@code documentum://<repository>/documents/<chronicle-id>} in {@code current} and {@code latest_major} version
 * modes, so a new version replaces the previous one downstream (in Documentum every version is its own object with its own
 * {@code r_object_id}, and {@code i_chronicle_id} is the id of the first version), and
 * {@code .../documents/<chronicle-id>/versions/<object-id>} in {@code all} mode.
 *
 * <p>Metadata: the standard attributes as {@code documentum.*} fields; every other attribute (those of custom subtypes) as
 * {@code <attribute-prefix><name>} ({@code documentum_attr_} by default), repeating attributes as lists, dates as ISO
 * instants. The other {@code dm_sysobject} attributes are only added with {@code include-system-attributes}.
 */
final class DocumentumDocumentBuilder {

    /** The attributes of dm_sysobject and dm_document (as a REST 16.7 server returns them), plus r_object_id. */
    static final Set<String> SYSTEM_ATTRIBUTES = Set.of(
            "object_name", "r_object_type", "title", "subject", "authors", "keywords", "a_application_type", "a_status",
            "r_creation_date", "r_modify_date", "r_modifier", "r_access_date", "a_is_hidden", "i_is_deleted",
            "a_retention_date", "a_archive", "a_compound_architecture", "a_link_resolved", "i_reference_cnt", "i_has_folder",
            "i_folder_id", "r_composite_id", "r_composite_label", "r_component_label", "r_order_no", "r_link_cnt",
            "r_link_high_cnt", "r_assembled_from_id", "r_frzn_assembly_cnt", "r_has_frzn_assembly", "resolution_label",
            "r_is_virtual_doc", "i_contents_id", "a_content_type", "r_page_cnt", "r_content_size", "a_full_text",
            "a_storage_type", "i_cabinet_id", "owner_name", "owner_permit", "group_name", "group_permit", "world_permit",
            "i_antecedent_id", "i_chronicle_id", "i_latest_flag", "r_lock_owner", "r_lock_date", "r_lock_machine",
            "log_entry", "r_version_label", "i_branch_cnt", "i_direct_dsc", "r_immutable_flag", "r_frozen_flag",
            "r_has_events", "acl_domain", "acl_name", "a_special_app", "i_is_reference", "r_creator_name", "r_is_public",
            "r_policy_id", "r_resume_state", "r_current_state", "r_alias_set_id", "a_effective_date", "a_expiration_date",
            "a_publish_formats", "a_effective_label", "a_effective_flag", "a_category", "language_code", "a_is_template",
            "a_controlling_app", "r_full_content_size", "a_extended_properties", "a_is_signed", "a_last_review_date",
            "i_retain_until", "r_aspect_name", "i_retainer_id", "i_partition", "i_is_replica", "i_vstamp", "r_object_id");
    static final String NULL_ID = "0000000000000000";
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)$");

    private final DocumentumProperties settings;

    DocumentumDocumentBuilder(DocumentumProperties settings) {
        this.settings = settings;
    }

    /** The OIS id of an object: per chronicle, or per version in {@code all} version mode. */
    String id(String chronicleId, String objectId) {
        String base = "documentum://" + settings.repository() + "/documents/" + chronicleId;
        return settings.versionMode() == DocumentumProperties.VersionMode.ALL ? base + "/versions/" + objectId : base;
    }

    /** The crawl-state key: the chronicle, or the version in {@code all} mode. */
    String stateKey(String chronicleId, String objectId) {
        return settings.versionMode() == DocumentumProperties.VersionMode.ALL ? objectId : chronicleId;
    }

    /** What was downloaded for a document, or null. */
    record Content(InputStream stream, String format, String mimeType, long size, boolean rendition) {
    }

    /**
     * One OIS document.
     *
     * @param properties the object's attributes ({@code properties} of the object resource)
     * @param content    the content indexed, or null for a document indexed by its metadata
     */
    RepositoryDocument build(JsonNode properties, Content content, List<String> folderPaths,
                             DocumentumSecurityMapper.Mapped security) {
        String objectId = text(properties, "r_object_id");
        String chronicleId = chronicle(properties);
        String id = id(chronicleId, objectId);
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        String name = text(properties, "object_name");
        String title = text(properties, "title");
        put(metadata, "name", name);
        put(metadata, "title", title != null ? title : name);
        if (content != null) {
            put(metadata, "mimeType", content.mimeType());
        }
        put(metadata, "documentum.repository", settings.repository());
        put(metadata, "documentum.objectId", objectId);
        put(metadata, "documentum.chronicleId", chronicleId);
        put(metadata, "documentum.objectName", name);
        put(metadata, "documentum.title", title);
        put(metadata, "documentum.subject", text(properties, "subject"));
        put(metadata, "documentum.type", text(properties, "r_object_type"));
        put(metadata, "documentum.creator", text(properties, "r_creator_name"));
        put(metadata, "documentum.created", instant(text(properties, "r_creation_date")).map(Instant::toString).orElse(null));
        put(metadata, "documentum.modifier", text(properties, "r_modifier"));
        put(metadata, "documentum.modified", instant(text(properties, "r_modify_date")).map(Instant::toString).orElse(null));
        put(metadata, "documentum.owner", text(properties, "owner_name"));
        put(metadata, "documentum.group", text(properties, "group_name"));
        put(metadata, "documentum.aclDomain", text(properties, "acl_domain"));
        put(metadata, "documentum.aclName", text(properties, "acl_name"));
        List<String> labels = values(properties.path("r_version_label"));
        if (!labels.isEmpty()) {
            metadata.put("documentum.versionLabels", labels);
            put(metadata, "documentum.versionLabel", labels.getFirst());
            put(metadata, "documentum.isCurrentVersion", Boolean.toString(labels.contains("CURRENT")));
        }
        list(metadata, "documentum.authors", properties.path("authors"));
        list(metadata, "documentum.keywords", properties.path("keywords"));
        put(metadata, "documentum.status", text(properties, "a_status"));
        put(metadata, "documentum.contentFormat", text(properties, "a_content_type"));
        if (properties.path("r_full_content_size").isNumber()) {
            put(metadata, "documentum.contentSize", Long.toString(properties.path("r_full_content_size").asLong()));
        }
        put(metadata, "documentum.lockOwner", text(properties, "r_lock_owner"));
        if (content != null) {
            put(metadata, "documentum.indexedFormat", content.format());
            if (content.rendition()) {
                put(metadata, "documentum.renditionFormat", content.format());
            }
        }
        if (!folderPaths.isEmpty()) {
            metadata.put("documentum.folderPath", List.copyOf(folderPaths));
        }
        if (!security.users().isEmpty()) {
            metadata.put("documentum_identity_users", security.users());
        }
        if (!security.groups().isEmpty()) {
            metadata.put("documentum_identity_groups", security.groups());
        }
        if (settings.includeCustomAttributes() || settings.includeSystemAttributes()) {
            attributes(properties, metadata);
        }
        Instant lastModified = instant(text(properties, "r_modify_date")).orElseGet(Instant::now);
        return new RepositoryDocument(id, id, content == null ? null : content.stream(), metadata, security.acl(),
                security.security(), lastModified, DocumentAction.UPSERT);
    }

    /** Attributes as {@code <prefix><name>}: custom ones, and the system ones when configured. */
    private void attributes(JsonNode properties, Map<String, List<String>> metadata) {
        String prefix = settings.attributePrefix() == null ? "" : settings.attributePrefix();
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> p = it.next();
            boolean system = SYSTEM_ATTRIBUTES.contains(p.getKey());
            if (system ? !settings.includeSystemAttributes() : !settings.includeCustomAttributes()) {
                continue;
            }
            List<String> values = values(p.getValue());
            if (!values.isEmpty()) {
                metadata.put(prefix + p.getKey(), values);
            }
        }
    }

    static String chronicle(JsonNode properties) {
        String chronicle = text(properties, "i_chronicle_id");
        return chronicle == null || chronicle.equals(NULL_ID) ? text(properties, "r_object_id") : chronicle;
    }

    /**
     * An attribute value as strings: repeating values flattened (an empty repeating attribute is {@code null}), dates as
     * ISO instants, whole doubles without the fraction; nulls, empty strings and null ids give nothing.
     */
    static List<String> values(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isNull() || value.isMissingNode() || value.isObject()) {
            return out;
        }
        if (value.isArray()) {
            value.forEach(v -> out.addAll(values(v)));
            return out;
        }
        String text = value.asText();
        if (value.isTextual() && DATE.matcher(text).matches()) {
            text = instant(text).map(Instant::toString).orElse(text);
        } else if (value.isDouble() && value.asDouble() == Math.rint(value.asDouble()) && Math.abs(value.asDouble()) < 1e15) {
            text = Long.toString(value.asLong()); // 9689.0 -> 9689
        }
        if (!text.isEmpty() && !text.equals(NULL_ID)) {
            out.add(text);
        }
        return out;
    }

    static RepositoryDocument tombstone(String id, String repository, String key) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("documentum.repository", List.of(repository));
        if (key != null) {
            metadata.put("documentum.id", List.of(key));
        }
        metadata.put("documentum.status", List.of("DELETED"));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(),
                DocumentAction.DELETE);
    }

    /** A REST date: RFC 3339 with an offset, e.g. {@code 2020-07-17T17:51:38.000+00:00}. */
    static Optional<Instant> instant(String value) {
        return offsetDateTime(value).map(OffsetDateTime::toInstant);
    }

    static Optional<OffsetDateTime> offsetDateTime(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    private static void list(Map<String, List<String>> metadata, String key, JsonNode value) {
        List<String> values = values(value);
        if (!values.isEmpty()) {
            metadata.put(key, values);
        }
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, List.of(value));
        }
    }
}
