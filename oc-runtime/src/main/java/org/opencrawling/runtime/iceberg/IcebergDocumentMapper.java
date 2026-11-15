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
package org.opencrawling.runtime.iceberg;

import org.apache.iceberg.Schema;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.types.Types;
import org.opencrawling.core.ois.OisMetadata;
import org.opencrawling.core.ois.OisSidecar;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless conversions between Apache Iceberg {@link Record}s written against
 * {@code IcebergTableManager.CANONICAL_SCHEMA} and OpenCrawling's security, OIS sidecar, and MCP models.
 */
public final class IcebergDocumentMapper {

    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("^(\\d{4}-\\d{2})$");

    private static final Set<String> CANONICAL_COLUMNS = Set.of(
            "id", "document_id", "source_type", "document_uri", "title", "mime_type",
            "content_length", "sha256_checksum", "claim_check_uri", "extracted_text",
            "extracted_entities", "custom_metadata", "security_owner", "security_inheritance",
            "allowed_principals", "denied_principals", "action", "crawled_at"
    );

    private IcebergDocumentMapper() {
    }

    /**
     * Canonical in-memory projection of one Iceberg Parquet document record together with its Iceberg commit ordering.
     */
    public record IcebergDocumentRecord(
            String id,
            String documentId,
            String sourceType,
            String documentUri,
            String title,
            String mimeType,
            long contentLength,
            String sha256Checksum,
            String claimCheckUri,
            String extractedText,
            Map<String, String> extractedEntities,
            Map<String, String> customMetadata,
            String securityOwner,
            boolean securityInheritance,
            List<String> allowedPrincipals,
            List<String> deniedPrincipals,
            String action,
            Instant crawledAt,
            String tableName,
            long dataSequenceNumber,
            long scanOrder
    ) {
        /** Logical key used to deduplicate latest version across snapshots. */
        public String logicalKey() {
            if (documentId != null && !documentId.isBlank()) {
                return documentId.trim();
            }
            if (documentUri != null && !documentUri.isBlank()) {
                return documentUri.trim();
            }
            return id != null ? id : "";
        }

        public boolean isDelete() {
            return "DELETE".equalsIgnoreCase(action);
        }

        public String yearMonthPartition() {
            Instant ts = crawledAt != null ? crawledAt : Instant.EPOCH;
            return YearMonth.from(ts.atOffset(ZoneOffset.UTC)).toString();
        }
    }

    /** Order deciding which append record supersedes an earlier record for the same document. */
    public static final Comparator<IcebergDocumentRecord> RECENCY_ORDER = Comparator
            .comparingLong(IcebergDocumentRecord::dataSequenceNumber)
            .thenComparing(IcebergDocumentRecord::crawledAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(IcebergDocumentRecord::scanOrder);

    /** Prefix hints parsed from {@code <sourceType>/<yyyy-MM>/...} sidecar keys for Iceberg pruning. */
    public record SidecarPrefixHints(String sourceTypePrefix, boolean exactSourceType, YearMonth yearMonth) {
        public static final SidecarPrefixHints NONE = new SidecarPrefixHints(null, false, null);
    }

    /** Extracts a typed {@link IcebergDocumentRecord} from an Iceberg {@link Record}. */
    public static IcebergDocumentRecord fromRecord(Record record, Schema schema, String tableName,
                                                   long dataSequenceNumber, long scanOrder) {
        String id = stringField(record, schema, "id", "");
        String documentId = stringField(record, schema, "document_id", id);
        String sourceType = stringField(record, schema, "source_type", "generic");
        String documentUri = stringField(record, schema, "document_uri", "");
        String title = stringField(record, schema, "title", documentId);
        String mimeType = stringField(record, schema, "mime_type", "application/octet-stream");
        long contentLength = longField(record, schema, "content_length", 0L);
        String sha256Checksum = stringField(record, schema, "sha256_checksum", "");
        String claimCheckUri = stringField(record, schema, "claim_check_uri", "");
        String extractedText = stringField(record, schema, "extracted_text", "");
        Map<String, String> extractedEntities = stringMapField(record, schema, "extracted_entities");
        mergeEvolvedEntityColumns(record, schema, extractedEntities);
        Map<String, String> customMetadata = stringMapField(record, schema, "custom_metadata");
        String securityOwner = stringField(record, schema, "security_owner", "");
        boolean securityInheritance = booleanField(record, schema, "security_inheritance", false);
        List<String> allowedPrincipals = stringListField(record, schema, "allowed_principals");
        List<String> deniedPrincipals = stringListField(record, schema, "denied_principals");
        String action = stringField(record, schema, "action", "UPSERT");
        Instant crawledAt = instantField(record, schema, "crawled_at");

        return new IcebergDocumentRecord(
                id, documentId, sourceType, documentUri, title, mimeType,
                contentLength, sha256Checksum, claimCheckUri, extractedText,
                Map.copyOf(extractedEntities), Map.copyOf(customMetadata),
                securityOwner, securityInheritance,
                List.copyOf(allowedPrincipals), List.copyOf(deniedPrincipals),
                action, crawledAt, tableName, dataSequenceNumber, scanOrder
        );
    }

    /**
     * Builds OpenCrawling {@link SecurityConfig} from Iceberg's {@code allowed_principals} and {@code denied_principals}.
     * Fails closed: empty principal lists yield an empty permission list that denies all callers.
     */
    public static SecurityConfig toSecurityConfig(IcebergDocumentRecord doc) {
        if (doc == null || doc.isDelete()) {
            return SecurityConfig.none();
        }
        List<PermissionRule> rules = new ArrayList<>();
        for (String denied : doc.deniedPrincipals()) {
            PermissionRule rule = toPermissionRule(denied, "deny");
            if (rule != null) {
                rules.add(rule);
            }
        }
        for (String allowed : doc.allowedPrincipals()) {
            PermissionRule rule = toPermissionRule(allowed, "read");
            if (rule != null) {
                rules.add(rule);
            }
        }
        return new SecurityConfig(doc.securityInheritance(), List.copyOf(rules));
    }

    /**
     * Converts an Iceberg record into a {@link ResourceRef} suitable for {@code DocumentAccessGate} evaluation
     * in all authorization modes ({@code metadata}, {@code shadow}, {@code intersection}, {@code openfga}).
     */
    public static ResourceRef toResourceRef(IcebergDocumentRecord doc) {
        if (doc == null) {
            return new ResourceRef(null, Map.of());
        }
        Map<String, Object> authMeta = new LinkedHashMap<>();
        String docId = blankToNull(doc.documentId());
        if (docId != null) {
            authMeta.put("documentId", docId);
        }
        if (blankToNull(doc.documentUri()) != null) {
            authMeta.put("uri", doc.documentUri());
        }
        SecurityConfig security = toSecurityConfig(doc);
        authMeta.put("security", security);
        authMeta.put("security_allowed_read", doc.isDelete() ? List.of() : doc.allowedPrincipals());
        authMeta.put("security_denied_read", doc.deniedPrincipals());
        authMeta.put("security_inheritance", doc.securityInheritance());
        return new ResourceRef(docId, authMeta);
    }

    /** Builds a canonical OIS sidecar envelope directly from an Iceberg Lakehouse row. */
    public static OisSidecar toOisSidecar(IcebergDocumentRecord doc, IcebergOutputProperties properties) {
        String docId = blankToNull(doc.documentId()) != null ? doc.documentId() : doc.id();
        String uri = blankToNull(doc.documentUri()) != null ? doc.documentUri() : doc.claimCheckUri();
        String lastModified = (doc.crawledAt() != null ? doc.crawledAt() : Instant.EPOCH).toString();

        String sourceInstance = doc.customMetadata().getOrDefault(OisMetadata.SOURCE_INSTANCE,
                properties != null ? properties.getWarehouse() : null);
        String sourceVersion = doc.customMetadata().get(OisMetadata.SOURCE_CONNECTOR_VERSION);
        String originalPath = firstNonBlankValue(doc.customMetadata(), "originalPath", "path", "cmis:path");
        if (originalPath == null && blankToNull(doc.documentUri()) != null) {
            originalPath = doc.documentUri();
        }
        OisSidecar.Source source = new OisSidecar.Source(
                blankToNull(doc.sourceType()) != null ? doc.sourceType() : "generic",
                blankToNull(sourceInstance),
                blankToNull(sourceVersion),
                blankToNull(doc.documentUri()),
                originalPath
        );

        if (doc.isDelete()) {
            return OisSidecar.tombstone(docId, uri != null ? uri : docId, lastModified, source);
        }

        Map<String, List<String>> metadataLists = new LinkedHashMap<>();
        if (blankToNull(doc.title()) != null) {
            metadataLists.put("title", List.of(doc.title()));
        }
        if (blankToNull(doc.securityOwner()) != null) {
            metadataLists.put("owner", List.of(doc.securityOwner()));
        }
        doc.customMetadata().forEach((k, v) -> {
            if (k != null && v != null && !"acl".equals(k) && !"security".equals(k)) {
                metadataLists.putIfAbsent(k, List.of(v));
            }
        });
        doc.extractedEntities().forEach((k, v) -> {
            if (k != null && v != null) {
                metadataLists.putIfAbsent("entity." + k, List.of(v));
            }
        });

        String filename = extractFilename(doc.title(), doc.documentUri(), docId);
        OisSidecar.Content content = new OisSidecar.Content(
                blankToNull(doc.mimeType()),
                blankToNull(doc.claimCheckUri()),
                null,
                blankToNull(doc.sha256Checksum()),
                doc.contentLength(),
                docId,
                null,
                properties != null ? blankToNull(properties.getTableName()) : null,
                properties != null && properties.getS3() != null ? blankToNull(properties.getS3().getRegion()) : null,
                filename,
                null,
                null
        );

        return OisSidecar.upsert(
                docId,
                uri != null ? uri : docId,
                lastModified,
                source,
                content,
                metadataLists,
                toSecurityConfig(doc),
                null
        );
    }

    /** Caller-safe metadata map returned in MCP responses (never exposes raw ACL grant/deny lists). */
    public static Map<String, Object> toPublicMetadataMap(IcebergDocumentRecord doc) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentId", doc.documentId());
        meta.put("uri", doc.documentUri());
        if (blankToNull(doc.title()) != null) {
            meta.put("title", doc.title());
        }
        meta.put("mimeType", doc.mimeType());
        meta.put("sourceType", doc.sourceType());
        meta.put("contentLength", doc.contentLength());
        if (blankToNull(doc.sha256Checksum()) != null) {
            meta.put("sha256_checksum", doc.sha256Checksum());
        }
        if (blankToNull(doc.claimCheckUri()) != null) {
            meta.put("claim_check_uri", doc.claimCheckUri());
        }
        if (blankToNull(doc.securityOwner()) != null) {
            meta.put("security_owner", doc.securityOwner());
        }
        if (doc.crawledAt() != null) {
            meta.put("crawled_at", doc.crawledAt().toString());
        }
        if (blankToNull(doc.tableName()) != null) {
            meta.put("iceberg_table", doc.tableName());
        }
        if (!doc.extractedEntities().isEmpty()) {
            meta.put("extracted_entities", doc.extractedEntities());
        }
        if (!doc.customMetadata().isEmpty()) {
            meta.put("custom_metadata", doc.customMetadata());
        }
        return meta;
    }

    /** Deterministic, path-safe virtual sidecar key ending in {@code .ois.json}. */
    public static String sidecarKey(IcebergDocumentRecord doc, String suffix) {
        String sourceSegment = sanitizeKeySegment(blankToNull(doc.sourceType()) != null ? doc.sourceType() : "generic");
        String partitionSegment = doc.yearMonthPartition();
        String baseInput = blankToNull(doc.documentUri()) != null ? doc.documentUri() : doc.logicalKey();
        String pathSlug = sanitizeKeyPath(baseInput);
        String hash = shortHash(doc.logicalKey() + "\0" + doc.documentUri());
        String effectiveSuffix = (suffix != null && !suffix.isBlank()) ? suffix : ".ois.json";
        return sourceSegment + "/" + partitionSegment + "/" + pathSlug + "-" + hash + effectiveSuffix;
    }

    /** Extracts optional {@code source_type} and {@code YYYY-MM} partition hints from a relative prefix. */
    public static SidecarPrefixHints parseSidecarPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return SidecarPrefixHints.NONE;
        }
        String normalized = prefix.trim().replaceAll("^/+", "");
        if (normalized.isEmpty()) {
            return SidecarPrefixHints.NONE;
        }
        int firstSlash = normalized.indexOf('/');
        if (firstSlash < 0) {
            return new SidecarPrefixHints(normalized, false, null);
        }
        String first = normalized.substring(0, firstSlash);
        String remainder = normalized.substring(firstSlash + 1);
        YearMonth ym = null;
        if (!remainder.isEmpty()) {
            int secondSlash = remainder.indexOf('/');
            String second = secondSlash >= 0 ? remainder.substring(0, secondSlash) : remainder;
            Matcher m = YEAR_MONTH_PATTERN.matcher(second);
            if (m.matches()) {
                try {
                    ym = YearMonth.parse(m.group(1));
                } catch (Exception ignored) {
                    ym = null;
                }
            }
        }
        return new SidecarPrefixHints(first.isEmpty() ? null : first, !first.isEmpty(), ym);
    }

    public static String formatAclSummary(IcebergDocumentRecord doc) {
        if (doc == null || doc.allowedPrincipals().isEmpty()) {
            return "restricted";
        }
        return String.join(",", doc.allowedPrincipals());
    }

    private static PermissionRule toPermissionRule(String principal, String access) {
        if (principal == null) {
            return null;
        }
        String trimmed = principal.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (EffectiveAcl.PUBLIC.equalsIgnoreCase(trimmed)) {
            return "read".equals(access)
                    ? new PermissionRule(EffectiveAcl.PUBLIC, EffectiveAcl.PUBLIC, "Public Access", "read")
                    : null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("user:")) {
            return new PermissionRule(trimmed, "user", trimmed.substring("user:".length()), access);
        }
        if (lower.startsWith("group:")) {
            return new PermissionRule(trimmed, "group", trimmed.substring("group:".length()), access);
        }
        return new PermissionRule(trimmed, null, trimmed, access);
    }

    private static void mergeEvolvedEntityColumns(Record record, Schema schema, Map<String, String> target) {
        if (schema == null) {
            return;
        }
        for (Types.NestedField field : schema.columns()) {
            String colName = field.name();
            if (CANONICAL_COLUMNS.contains(colName)) {
                continue;
            }
            Object raw = record.getField(colName);
            if (raw != null) {
                String value = String.valueOf(raw);
                if (!value.isBlank()) {
                    target.putIfAbsent(colName, value);
                }
            }
        }
    }

    private static Object fieldOrNull(Record record, Schema schema, String fieldName) {
        if (schema != null && schema.findField(fieldName) == null) {
            return null;
        }
        return record.getField(fieldName);
    }

    private static String stringField(Record record, Schema schema, String fieldName, String defaultValue) {
        Object val = fieldOrNull(record, schema, fieldName);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    private static long longField(Record record, Schema schema, String fieldName, long defaultValue) {
        Object val = fieldOrNull(record, schema, fieldName);
        if (val instanceof Number number) {
            return number.longValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static boolean booleanField(Record record, Schema schema, String fieldName, boolean defaultValue) {
        Object val = fieldOrNull(record, schema, fieldName);
        if (val instanceof Boolean bool) {
            return bool;
        }
        if (val instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private static Map<String, String> stringMapField(Record record, Schema schema, String fieldName) {
        Object val = fieldOrNull(record, schema, fieldName);
        Map<String, String> result = new LinkedHashMap<>();
        if (val instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return result;
    }

    private static List<String> stringListField(Record record, Schema schema, String fieldName) {
        Object val = fieldOrNull(record, schema, fieldName);
        List<String> result = new ArrayList<>();
        if (val instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
        }
        return result;
    }

    private static Instant instantField(Record record, Schema schema, String fieldName) {
        Object val = fieldOrNull(record, schema, fieldName);
        return switch (val) {
            case null -> Instant.EPOCH;
            case Instant instant -> instant;
            case LocalDateTime ldt -> ldt.toInstant(ZoneOffset.UTC);
            case OffsetDateTime odt -> odt.toInstant();
            case Number n -> {
                long raw = n.longValue();
                // Iceberg Parquet timestamps without zone are stored in microseconds.
                yield raw > 100_000_000_000_000L
                        ? Instant.ofEpochSecond(raw / 1_000_000L, (raw % 1_000_000L) * 1_000L)
                        : Instant.ofEpochMilli(raw);
            }
            default -> {
                try {
                    yield Instant.parse(String.valueOf(val).trim());
                } catch (Exception e) {
                    yield Instant.EPOCH;
                }
            }
        };
    }

    private static String extractFilename(String title, String uri, String docId) {
        if (blankToNull(title) != null) {
            return title.trim();
        }
        if (blankToNull(uri) != null) {
            String cleaned = uri.trim();
            int slash = cleaned.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < cleaned.length()) {
                return cleaned.substring(slash + 1);
            }
            return cleaned;
        }
        return docId != null ? docId : "document";
    }

    private static String sanitizeKeySegment(String value) {
        if (value == null || value.isBlank()) {
            return "generic";
        }
        String sanitized = value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.replaceAll("\\.\\.", "_");
    }

    private static String sanitizeKeyPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "doc";
        }
        String cleaned = raw.trim()
                .replace("://", "/")
                .replace('\\', '/')
                .replaceAll("^/+", "");
        List<String> safeParts = new ArrayList<>();
        for (String part : cleaned.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            safeParts.add(part.replaceAll("[^a-zA-Z0-9._-]", "_"));
        }
        if (safeParts.isEmpty()) {
            return "doc";
        }
        String joined = String.join("/", safeParts);
        return joined.length() > 160 ? joined.substring(0, 160) : joined;
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String firstNonBlankValue(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = blankToNull(map.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
