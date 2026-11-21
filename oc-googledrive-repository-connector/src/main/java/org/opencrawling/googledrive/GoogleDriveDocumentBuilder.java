/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.ConnectorSchema;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps Google Drive REST API v3 file resources into Open Ingestion Standard (OIS) {@link RepositoryDocument}s,
 * oversized ({@code TOO_LARGE}) reports, and {@link DocumentAction#DELETE} tombstones.
 */
public final class GoogleDriveDocumentBuilder {

    public static final String STATUS_KEY = "opencrawling.document.status";
    public static final String REASON_KEY = "opencrawling.document.reason";
    public static final String STATUS_TOO_LARGE = "TOO_LARGE";
    public static final String MY_DRIVE_SEGMENT = "my-drive";

    /** Target export MIME type and conventional file extension for native Google Workspace files. */
    public record ExportTarget(String mimeType, String extension) {
    }

    /** Context resolved around a Drive file before document construction. */
    public record FileContext(
            String driveId,
            String driveName,
            List<String> breadcrumbs,
            String folderPath,
            String fullPath,
            String shortcutSourceId
    ) {
        public FileContext {
            breadcrumbs = breadcrumbs == null ? List.of() : List.copyOf(breadcrumbs);
        }
    }

    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("text/html", ".html"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/markdown", ".md"),
            Map.entry("text/csv", ".csv"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("application/rtf", ".rtf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
            Map.entry("application/vnd.oasis.opendocument.text", ".odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", ".ods"),
            Map.entry("application/vnd.oasis.opendocument.presentation", ".odp"),
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/svg+xml", ".svg")
    );

    private GoogleDriveDocumentBuilder() {
    }

    /** Canonical OIS document ID: {@code googledrive://drives/<driveId>/files/<fileId>}. */
    public static String documentId(String driveId, String fileId) {
        String driveSegment = (driveId != null && !driveId.isBlank()) ? driveId.strip() : MY_DRIVE_SEGMENT;
        return "googledrive://drives/" + driveSegment + "/files/" + fileId.strip();
    }

    public static boolean isGoogleWorkspaceMime(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("application/vnd.google-apps.");
    }

    public static boolean isFolder(JsonNode file) {
        return file != null && GoogleDriveProperties.MIME_FOLDER.equalsIgnoreCase(file.path("mimeType").asText(""));
    }

    public static boolean isShortcut(JsonNode file) {
        return file != null && GoogleDriveProperties.MIME_SHORTCUT.equalsIgnoreCase(file.path("mimeType").asText(""));
    }

    /** Resolves the configured export target for native Google Docs, Sheets, Slides, and Drawings. */
    public static Optional<ExportTarget> resolveExportTarget(String originalMimeType, GoogleDriveProperties settings) {
        if (originalMimeType == null || originalMimeType.isBlank()) {
            return Optional.empty();
        }
        String targetMime = switch (originalMimeType.strip().toLowerCase(Locale.ROOT)) {
            case GoogleDriveProperties.MIME_GOOGLE_DOC -> settings.exportDocMimeType();
            case GoogleDriveProperties.MIME_GOOGLE_SHEET -> settings.exportSheetMimeType();
            case GoogleDriveProperties.MIME_GOOGLE_SLIDES -> settings.exportSlidesMimeType();
            case GoogleDriveProperties.MIME_GOOGLE_DRAWING -> settings.exportDrawingMimeType();
            default -> null;
        };
        if (targetMime == null || targetMime.isBlank()) {
            return Optional.empty();
        }
        String normalized = targetMime.strip();
        String ext = MIME_EXTENSIONS.getOrDefault(normalized.toLowerCase(Locale.ROOT), "");
        return Optional.of(new ExportTarget(normalized, ext));
    }

    /**
     * Computes a cheap, deterministic version key from Drive's {@code version}, {@code headRevisionId}, and
     * {@code md5Checksum} (falling back to {@code modifiedTime} when none of those are returned) so unchanged files
     * are skipped before downloading content.
     */
    public static String versionKey(JsonNode file) {
        if (file == null || file.isMissingNode()) {
            return null;
        }
        String version = text(file, "version");
        String headRev = text(file, "headRevisionId");
        String md5 = text(file, "md5Checksum");
        List<String> parts = new ArrayList<>(3);
        if (version != null && !version.isBlank()) {
            parts.add("v:" + version.strip());
        }
        if (headRev != null && !headRev.isBlank()) {
            parts.add("rev:" + headRev.strip());
        }
        if (md5 != null && !md5.isBlank()) {
            parts.add("md5:" + md5.strip());
        }
        if (!parts.isEmpty()) {
            return String.join("|", parts);
        }
        String modifiedTime = text(file, "modifiedTime");
        return modifiedTime != null && !modifiedTime.isBlank() ? "mod:" + modifiedTime.strip() : null;
    }

    /** Appends export extension to a Google Workspace file name when not already present. */
    public static String effectiveName(String rawName, ExportTarget exportTarget) {
        String name = (rawName == null || rawName.isBlank()) ? "untitled" : rawName.strip();
        if (exportTarget == null || exportTarget.extension().isEmpty()) {
            return name;
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(exportTarget.extension().toLowerCase(Locale.ROOT))) {
            return name;
        }
        return name + exportTarget.extension();
    }

    /** Builds a standard OIS {@link DocumentAction#UPSERT} document. */
    public static RepositoryDocument build(
            JsonNode file,
            FileContext context,
            ExportTarget exportTarget,
            GoogleDriveProperties settings,
            InputStream contentStream,
            long contentLength,
            GoogleDriveSecurityMapper.Mapped security
    ) {
        return buildInternal(file, context, exportTarget, settings, contentStream, contentLength, security, null, null);
    }

    /**
     * Builds a metadata-only {@link RepositoryDocument} explicitly flagged as {@code TOO_LARGE} so oversized files are
     * visibly reported in execution ledgers and telemetry rather than silently skipped (#339, #397).
     */
    public static RepositoryDocument buildTooLarge(
            JsonNode file,
            FileContext context,
            ExportTarget exportTarget,
            GoogleDriveProperties settings,
            long reportedSizeBytes,
            String reason,
            GoogleDriveSecurityMapper.Mapped security
    ) {
        return buildInternal(file, context, exportTarget, settings, null, reportedSizeBytes, security,
                STATUS_TOO_LARGE, reason);
    }

    private static RepositoryDocument buildInternal(
            JsonNode file,
            FileContext context,
            ExportTarget exportTarget,
            GoogleDriveProperties settings,
            InputStream contentStream,
            long contentLength,
            GoogleDriveSecurityMapper.Mapped security,
            String documentStatus,
            String statusReason
    ) {
        String fileId = file.path("id").asText();
        String driveId = context.driveId();
        String id = documentId(driveId, fileId);

        String originalName = textOrDefault(file, "name", fileId);
        String effectiveFileName = effectiveName(originalName, exportTarget);
        String originalMime = text(file, "mimeType");
        String effectiveMime = exportTarget != null ? exportTarget.mimeType() : originalMime;
        String webViewLink = text(file, "webViewLink");

        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("source_type", List.of("googledrive"));
        // File name as user-visible title; effective name with export extension for downstream Tika/claim-check
        metadata.put("title", List.of(originalName));
        metadata.put("name", List.of(effectiveFileName));
        metadata.put("filename", List.of(effectiveFileName));
        put(metadata, "mimeType", effectiveMime);

        // User-facing URL (webViewLink)
        put(metadata, "url", webViewLink);
        put(metadata, "webViewLink", webViewLink);
        put(metadata, "googledrive.webViewLink", webViewLink);

        // Folder path & breadcrumbs
        String fullPath = context.fullPath();
        if (fullPath == null || fullPath.isBlank()) {
            fullPath = "/" + effectiveFileName;
        }
        put(metadata, "path", fullPath);
        put(metadata, "googledrive.path", fullPath);
        put(metadata, "googledrive.folderPath", context.folderPath());
        if (!context.breadcrumbs().isEmpty()) {
            metadata.put("breadcrumbs", context.breadcrumbs());
            metadata.put("googledrive.breadcrumbs", List.of(String.join(" > ", context.breadcrumbs())));
        }

        // Core Google Drive metadata
        put(metadata, "googledrive.fileId", fileId);
        put(metadata, "googledrive.name", effectiveFileName);
        put(metadata, "googledrive.originalName", originalName);
        put(metadata, "googledrive.originalMimeType", originalMime);
        put(metadata, "googledrive.exported", Boolean.toString(exportTarget != null));
        put(metadata, "googledrive.driveId", driveId);
        put(metadata, "googledrive.driveName", context.driveName());
        put(metadata, "googledrive.description", text(file, "description"));
        put(metadata, "googledrive.shortcutSourceId", context.shortcutSourceId());

        List<String> parents = stringArray(file.path("parents"));
        if (!parents.isEmpty()) {
            metadata.put("googledrive.parentIds", parents);
        }

        put(metadata, "googledrive.createdTime", text(file, "createdTime"));
        put(metadata, "googledrive.modifiedTime", text(file, "modifiedTime"));
        put(metadata, "googledrive.version", text(file, "version"));
        put(metadata, "googledrive.headRevisionId", text(file, "headRevisionId"));
        put(metadata, "googledrive.md5Checksum", text(file, "md5Checksum"));
        put(metadata, "googledrive.versionKey", versionKey(file));

        long size = contentLength >= 0 ? contentLength : file.path("size").asLong(-1L);
        if (size >= 0) {
            put(metadata, "googledrive.size", Long.toString(size));
            put(metadata, "binary_size", Long.toString(size));
        }

        List<String> owners = extractEmails(file.path("owners"));
        if (!owners.isEmpty()) {
            metadata.put("googledrive.owners", owners);
        }
        String lastModUser = extractEmailOrName(file.path("lastModifyingUser"));
        put(metadata, "googledrive.lastModifyingUser", lastModUser);

        if (settings.includeCustomProperties()) {
            addCustomProperties(metadata, file.path("properties"));
            addCustomProperties(metadata, file.path("appProperties"));
        }

        if (!security.users().isEmpty()) {
            metadata.put("googledrive_identity_users", List.of(String.join(",", security.users())));
        }
        if (!security.groups().isEmpty()) {
            metadata.put("googledrive_identity_groups", List.of(String.join(",", security.groups())));
        }
        if (!security.domains().isEmpty()) {
            metadata.put("googledrive_identity_domains", List.of(String.join(",", security.domains())));
        }

        if (documentStatus != null && !documentStatus.isBlank()) {
            put(metadata, "googledrive.status", documentStatus);
            put(metadata, STATUS_KEY, documentStatus);
            put(metadata, REASON_KEY, statusReason);
        }

        Instant lastModified = parseInstant(text(file, "modifiedTime"));
        return new RepositoryDocument(
                id,
                id,
                contentStream,
                metadata,
                security.acl(),
                security.security(),
                lastModified != null ? lastModified : Instant.now(),
                DocumentAction.UPSERT
        );
    }

    /** Builds a {@link DocumentAction#DELETE} tombstone payload for a trashed, deleted, or out-of-scope file. */
    public static RepositoryDocument buildTombstone(String docId, String driveId, String fileId) {
        String id = (docId != null && !docId.isBlank()) ? docId : documentId(driveId, fileId);
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("source_type", List.of("googledrive"));
        put(metadata, "googledrive.fileId", fileId);
        put(metadata, "googledrive.driveId", driveId);
        metadata.put("googledrive.status", List.of("DELETED"));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(),
                DocumentAction.DELETE);
    }

    public static ConnectorSchema schema() {
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("title", "STRING", "Original Google Drive file name"),
                new ConnectorSchema.SchemaField("url", "STRING", "Google Drive webViewLink shown to users"),
                new ConnectorSchema.SchemaField("path", "STRING", "Full folder path and file name"),
                new ConnectorSchema.SchemaField("breadcrumbs", "STRING_ARRAY", "Ordered folder path breadcrumbs"),
                new ConnectorSchema.SchemaField("mimeType", "STRING", "Effective MIME type (exported MIME type for native Google Workspace docs)"),
                new ConnectorSchema.SchemaField("googledrive.fileId", "STRING", "Google Drive file ID"),
                new ConnectorSchema.SchemaField("googledrive.name", "STRING", "Effective file name (including export extension)"),
                new ConnectorSchema.SchemaField("googledrive.originalMimeType", "STRING", "Original Drive MIME type"),
                new ConnectorSchema.SchemaField("googledrive.webViewLink", "STRING", "Browser URL for viewing the document in Google Drive"),
                new ConnectorSchema.SchemaField("googledrive.driveId", "STRING", "Shared Drive ID (or my-drive)"),
                new ConnectorSchema.SchemaField("googledrive.driveName", "STRING", "Shared Drive display name"),
                new ConnectorSchema.SchemaField("googledrive.parentIds", "STRING_ARRAY", "Parent folder IDs"),
                new ConnectorSchema.SchemaField("googledrive.createdTime", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("googledrive.modifiedTime", "TIMESTAMP", "Last modification timestamp"),
                new ConnectorSchema.SchemaField("googledrive.version", "STRING", "Google Drive monotonic version counter"),
                new ConnectorSchema.SchemaField("googledrive.headRevisionId", "STRING", "Head revision ID"),
                new ConnectorSchema.SchemaField("googledrive.md5Checksum", "STRING", "MD5 checksum for binary files"),
                new ConnectorSchema.SchemaField("googledrive.versionKey", "STRING", "Composite version key for incremental skip detection"),
                new ConnectorSchema.SchemaField("googledrive.size", "LONG", "Content size in bytes"),
                new ConnectorSchema.SchemaField("googledrive.owners", "STRING_ARRAY", "File owner emails"),
                new ConnectorSchema.SchemaField("googledrive.lastModifyingUser", "STRING", "Last modifying user email or display name"),
                new ConnectorSchema.SchemaField("googledrive_prop_<key>", "STRING", "Custom Google Drive properties and appProperties")
        ));
    }

    private static void addCustomProperties(Map<String, List<String>> metadata, JsonNode propsNode) {
        if (propsNode == null || !propsNode.isObject()) {
            return;
        }
        propsNode.properties().forEach(entry -> {
            String key = entry.getKey();
            String val = entry.getValue().isNull() ? null : entry.getValue().asText(null);
            if (key != null && !key.isBlank() && val != null && !val.isBlank()) {
                metadata.put("googledrive_prop_" + key.strip(), List.of(val.strip()));
            }
        });
    }

    private static List<String> extractEmails(JsonNode usersNode) {
        List<String> out = new ArrayList<>();
        if (usersNode != null && usersNode.isArray()) {
            for (JsonNode user : usersNode) {
                String value = extractEmailOrName(user);
                if (value != null && !value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static String extractEmailOrName(JsonNode userNode) {
        if (userNode == null || !userNode.isObject()) {
            return null;
        }
        String email = text(userNode, "emailAddress");
        return (email != null && !email.isBlank()) ? email.strip() : text(userNode, "displayName");
    }

    static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String val = item.asText(null);
            if (val != null && !val.isBlank()) {
                out.add(val.strip());
            }
        });
        return List.copyOf(out);
    }

    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode val = node.path(field);
        return val.isMissingNode() || val.isNull() ? null : val.asText();
    }

    static String textOrDefault(JsonNode node, String field, String fallback) {
        String val = text(node, field);
        return val == null || val.isBlank() ? fallback : val;
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }
}
