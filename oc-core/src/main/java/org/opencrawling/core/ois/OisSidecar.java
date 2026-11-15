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
package org.opencrawling.core.ois;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An Open Ingestion Standard document envelope (the {@code <key>.ois.json} sidecar) as the migration writers store it
 * next to each migrated binary. It validates against the OIS {@code schemas/document.schema.json}.
 *
 * <p>Besides the fields the schema defines, it keeps OpenCrawling's own locators, which the schema allows: the
 * top-level {@code uri} and {@code lastModified}, the flat {@code acl}, and in {@code content} the target object's
 * {@code key}, {@code bucket}, {@code filename} and the like.
 *
 * <p>Sidecars written before this format used {@code contentRef} (with {@code contentLength} and
 * {@code checksumSha256}) and plain string lists as metadata; readers accept both.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"$schema", "id", "action", "uri", "lastModified", "source", "content", "metadata", "security", "acl"})
public record OisSidecar(
        @JsonProperty("$schema") String schema,
        String id,
        String action,
        String uri,
        String lastModified,
        Source source,
        Content content,
        Map<String, Object> metadata,
        Security security,
        String acl
) {

    /** The {@code $id} of the OIS document schema. */
    public static final String SCHEMA_ID = "https://opencrawling.org/schemas/ois-document-schema.json";

    /** The only evaluation policy OIS defines: absent or empty permissions deny everyone. */
    public static final String FAIL_CLOSED = "FAIL_CLOSED";

    /**
     * Where the document came from.
     *
     * @param type             repository type, e.g. {@code alfresco}
     * @param instance         base URI of the repository instance
     * @param connectorVersion version of the repository connector
     * @param originalUri      the document's URI when it was read (OpenCrawling extension)
     * @param originalPath     the document's path in the repository (OpenCrawling extension)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type", "instance", "connectorVersion", "originalUri", "originalPath"})
    public record Source(String type, String instance, String connectorVersion, String originalUri,
                         String originalPath) {}

    /**
     * The migrated binary. {@code mimeType} through {@code sizeBytes} are OIS fields; the rest locate the object in
     * the target store and are OpenCrawling extensions, each left out when it does not apply.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"mimeType", "claimCheckUri", "sidecarUri", "sha256", "sizeBytes", "key", "volume", "bucket",
            "region", "filename", "storageClass", "eTag"})
    public record Content(String mimeType, String claimCheckUri, String sidecarUri, String sha256, Long sizeBytes,
                          String key, String volume, String bucket, String region, String filename,
                          String storageClass, String eTag) {}

    /** The document's permissions, evaluated fail-closed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"evaluationPolicy", "inheritanceEnabled", "permissions"})
    public record Security(String evaluationPolicy, boolean inheritanceEnabled, List<PermissionRule> permissions) {

        public static Security of(SecurityConfig config) {
            if (config == null) {
                return denyAll();
            }
            return new Security(FAIL_CLOSED, config.inheritanceEnabled(),
                    config.permissions() != null ? List.copyOf(config.permissions()) : List.of());
        }

        /** No permissions: nobody may read. */
        public static Security denyAll() {
            return new Security(FAIL_CLOSED, false, List.of());
        }
    }

    /** An UPSERT envelope. */
    public static OisSidecar upsert(String id, String uri, String lastModified, Source source, Content content,
                                    Map<String, List<String>> metadata, SecurityConfig security, String acl) {
        return new OisSidecar(SCHEMA_ID, id, "UPSERT", uri, lastModified, source, content, metadata(metadata),
                Security.of(security), acl != null && !acl.isBlank() ? acl : null);
    }

    /**
     * A DELETE tombstone. It names its source, as the schema requires, and grants nobody anything: a tombstone is no
     * reason to make a document public.
     */
    public static OisSidecar tombstone(String id, String uri, String lastModified, Source source) {
        return new OisSidecar(SCHEMA_ID, id, "DELETE", uri, lastModified, Objects.requireNonNull(source, "source"),
                null, null, Security.denyAll(), null);
    }

    /**
     * The OIS source block for a document: the {@code ois:source} fields OpenCrawling stamped on it, or the given
     * fallbacks.
     */
    public static Source source(Map<String, List<String>> metadata, String fallbackType, String fallbackInstance,
                                String originalUri, String originalPath) {
        String type = first(metadata, OisMetadata.SOURCE_TYPE);
        String instance = first(metadata, OisMetadata.SOURCE_INSTANCE);
        return new Source(type != null ? type : fallbackType, instance != null ? instance : fallbackInstance,
                first(metadata, OisMetadata.SOURCE_CONNECTOR_VERSION), blankToNull(originalUri),
                blankToNull(originalPath));
    }

    // ----- metadata -----

    private static final Set<String> STRING_KEYS = Set.of("title", "author", "cmis:objectId", "cmis:name",
            "cmis:objectTypeId", "cmis:createdBy", "cmis:lastModifiedBy", "cmis:changeToken", "cmis:versionLabel",
            "bpmn:processInstanceId", "bpmn:processDefinitionId", "bpmn:processDefinitionKey",
            "bpmn:processDefinitionName", "bpmn:businessKey", "bpmn:startUserId");
    private static final Set<String> DATE_TIME_KEYS = Set.of("lastModified", "cmis:creationDate", "bpmn:startDate",
            "bpmn:endDate");
    private static final Set<String> BOOLEAN_KEYS = Set.of("cmis:isLatestVersion");
    private static final Set<String> BPMN_STATUS = Set.of("ACTIVE", "SUSPENDED", "COMPLETED", "EXTERNALLY_TERMINATED",
            "INTERNALLY_TERMINATED");
    /** Keys the schema types as objects or arrays of objects, which a list of strings cannot be. */
    private static final Set<String> STRUCTURED_KEYS = Set.of("bpmn:variables", "bpmn:activeTasks",
            "bpmn:transitionEvent");

    /** The suffix of the key under which a value that does not fit its declared OIS type is kept as it was. */
    public static final String RAW_SUFFIX = ".raw";

    /**
     * The sidecar metadata for a document's metadata.
     *
     * <ul>
     *   <li>Keys the OIS schema declares get its types: one value as a string (or a date-time, or a boolean),
     *       several as an array of strings. A value that does not fit is kept unchanged under
     *       {@code <key>.raw}, so nothing is lost and the envelope stays valid.</li>
     *   <li>The flat {@code ois:source.*} keys are left out (they become the {@code source} block), and the flat
     *       {@code ois:provenance.*} and {@code ois:governance.*} keys become the nested objects.</li>
     *   <li>Every other key keeps its list of strings.</li>
     * </ul>
     */
    public static Map<String, Object> metadata(Map<String, List<String>> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata == null) {
            return result;
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        Map<String, Object> governance = new LinkedHashMap<>();
        metadata.forEach((key, rawValues) -> {
            if (key == null || rawValues == null) {
                return;
            }
            List<String> values = rawValues.stream().filter(Objects::nonNull).toList();
            if (key.startsWith(OisMetadata.SOURCE + ".") || key.equals(OisMetadata.SOURCE)) {
                return;
            }
            if (key.startsWith(OisMetadata.PROVENANCE + ".")) {
                if (!values.isEmpty()) {
                    provenance.put(key.substring(OisMetadata.PROVENANCE.length() + 1), values.getFirst());
                }
                return;
            }
            if (key.startsWith(OisMetadata.GOVERNANCE + ".")) {
                String field = key.substring(OisMetadata.GOVERNANCE.length() + 1);
                if (field.equals("redactionCategories")) {
                    governance.put(field, values);
                } else if (field.equals("redacted")) {
                    if (!values.isEmpty()) {
                        governance.put(field, Boolean.parseBoolean(values.getFirst()));
                    }
                } else if (!values.isEmpty()) {
                    governance.put(field, values.getFirst());
                }
                return;
            }
            if (key.equals(OisMetadata.PROVENANCE) || key.equals(OisMetadata.GOVERNANCE)) {
                return;
            }
            typed(result, key, values);
        });
        if (!provenance.isEmpty()) {
            result.put(OisMetadata.PROVENANCE, provenance);
        }
        if (governance.containsKey("decision")) {
            result.put(OisMetadata.GOVERNANCE, governance);
        }
        return result;
    }

    private static void typed(Map<String, Object> result, String key, List<String> values) {
        if (STRUCTURED_KEYS.contains(key)) {
            result.put(key + RAW_SUFFIX, values);
            return;
        }
        boolean declared = STRING_KEYS.contains(key) || DATE_TIME_KEYS.contains(key) || BOOLEAN_KEYS.contains(key)
                || key.equals("bpmn:status");
        if (!declared) {
            result.put(key, values);
            return;
        }
        if (values.isEmpty()) {
            return;
        }
        if (BOOLEAN_KEYS.contains(key)) {
            String value = values.getFirst().strip().toLowerCase(Locale.ROOT);
            if (values.size() == 1 && (value.equals("true") || value.equals("false"))) {
                result.put(key, Boolean.valueOf(value));
            } else {
                result.put(key + RAW_SUFFIX, values);
            }
            return;
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            String fitted = DATE_TIME_KEYS.contains(key) ? dateTime(value)
                    : key.equals("bpmn:status") ? (BPMN_STATUS.contains(value) ? value : null)
                    : value;
            if (fitted == null) {
                result.put(key + RAW_SUFFIX, values);
                return;
            }
            normalized.add(fitted);
        }
        if (key.equals("bpmn:status") && normalized.size() > 1) {
            result.put(key + RAW_SUFFIX, values);
            return;
        }
        result.put(key, normalized.size() == 1 ? normalized.getFirst() : List.copyOf(normalized));
    }

    /** The value as an RFC 3339 date-time in UTC, or {@code null} when it is not a date-time. */
    static String dateTime(String value) {
        String trimmed = value.strip();
        try {
            return Instant.parse(trimmed).toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(trimmed).toInstant().toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            // No zone given: OpenCrawling's timestamps are UTC.
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException ignored) {
        }
        if (trimmed.matches("\\d{12,13}")) {
            return Instant.ofEpochMilli(Long.parseLong(trimmed)).toString();
        }
        return null;
    }

    private static String first(Map<String, List<String>> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        List<String> values = metadata.get(key);
        if (values == null) {
            return null;
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
