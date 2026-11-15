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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds OIS {@link RepositoryDocument}s from MFWS {@code ObjectVersion}s and their {@code PropertyValue}s.
 *
 * <p>Ids: the latest version of an object is {@code mfiles://<vault>/objects/<type>/<id>}, so a new version replaces the
 * previous one downstream; an older version (ALL_VERSIONS mode) is {@code …/versions/<n>}. A single-file document carries its
 * file under the object id; every file of a multi-file document is its own document, {@code …/files/<fileId>}, with
 * {@code mfiles.parentId} pointing at the object. An object without files is indexed by its metadata.
 *
 * <p>System data goes under {@code mfiles.*}; every property value goes under {@code <prefix><name>} with the property
 * definition's name in lower camel case ({@code Contract Value} → {@code mfiles_prop_contractValue}), and the ids of lookup
 * values under {@code <prefix><name>_id}.
 */
public final class MFilesDocumentBuilder {

    /** Built-in property definitions (MFBuiltInPropertyDef). */
    static final int PD_NAME_OR_TITLE = 0;
    static final int PD_CREATED = 20;
    static final int PD_LAST_MODIFIED = 21;
    static final int PD_SINGLE_FILE = 22;
    static final int PD_LAST_MODIFIED_BY = 23;
    static final int PD_CREATED_BY = 25;
    static final int PD_WORKFLOW = 38;
    static final int PD_STATE = 39;
    static final int PD_CLASS = 100;

    /** A property definition from {@code /structure/properties}. */
    public record PropertyDef(int id, String name, int dataType) {
    }

    /** One file of an object version ({@code ObjectFile}). */
    public record FileRef(int fileId, String name, String extension, long size) {
        public String fileName() {
            if (extension == null || extension.isBlank() || name.toLowerCase(Locale.ROOT).endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                return name;
            }
            return name + "." + extension;
        }
    }

    /** What the connector knows about the vault and this emission. */
    public record Context(String vaultKey, String vaultName, boolean latest, boolean includeProperties, String propertyPrefix) {
    }

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("msg", "application/vnd.ms-outlook"),
            Map.entry("eml", "message/rfc822"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("htm", "text/html"),
            Map.entry("html", "text/html"),
            Map.entry("xml", "application/xml"),
            Map.entry("json", "application/json"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("zip", "application/zip"));

    private MFilesDocumentBuilder() {
    }

    public static String objectId(String vaultKey, int type, int id) {
        return "mfiles://" + vaultKey + "/objects/" + type + "/" + id;
    }

    /** The id of a version: the object id for the latest version, {@code …/versions/<n>} for an older one. */
    public static String versionId(String vaultKey, int type, int id, int version, boolean latest) {
        String base = objectId(vaultKey, type, id);
        return latest ? base : base + "/versions/" + version;
    }

    public static String fileDocumentId(String versionId, int fileId) {
        return versionId + "/files/" + fileId;
    }

    /** The files of an {@code ObjectVersion}, in the order MFWS lists them. */
    public static List<FileRef> files(JsonNode objectVersion) {
        List<FileRef> files = new ArrayList<>();
        for (JsonNode file : objectVersion.path("Files")) {
            int id = file.path("ID").asInt(-1);
            if (id < 0) {
                continue;
            }
            files.add(new FileRef(id, file.path("Name").asText("file-" + id), text(file, "Extension"), file.path("Size").asLong(-1)));
        }
        return files;
    }

    /**
     * The OIS ids an object version produces: the version id for a single-file document or an object without files, one id
     * per file for a multi-file document.
     */
    public static List<String> ids(String vaultKey, JsonNode objectVersion, boolean latest) {
        JsonNode objVer = objectVersion.path("ObjVer");
        String versionId = versionId(vaultKey, objVer.path("Type").asInt(), objVer.path("ID").asInt(), objVer.path("Version").asInt(), latest);
        List<FileRef> files = files(objectVersion);
        if (isSingleFile(objectVersion, files) || files.isEmpty()) {
            return List.of(versionId);
        }
        return files.stream().map(f -> fileDocumentId(versionId, f.fileId())).toList();
    }

    /** {@code SingleFile} as MFWS reports it; when absent, an object with exactly one file is treated as single-file. */
    public static boolean isSingleFile(JsonNode objectVersion, List<FileRef> files) {
        JsonNode singleFile = objectVersion.path("SingleFile");
        return singleFile.isBoolean() ? singleFile.asBoolean() : files.size() == 1;
    }

    public static String mimeType(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : MIME_TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static RepositoryDocument tombstone(String id, String vaultKey, int type, int objId) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("mfiles.vaultGuid", List.of(vaultKey));
        metadata.put("mfiles.objType", List.of(Integer.toString(type)));
        metadata.put("mfiles.objId", List.of(Integer.toString(objId)));
        metadata.put("mfiles.status", List.of("DELETED"));
        return new RepositoryDocument(id, id, null, metadata, "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);
    }

    /**
     * One OIS document for an object version, or for one file of it.
     *
     * @param file          the file this document carries, or {@code null} for a metadata-only document
     * @param contentLength bytes downloaded, or {@code -1} when no content is attached
     */
    public static RepositoryDocument build(String id, Context context, JsonNode objectVersion, List<JsonNode> properties,
                                           Map<Integer, PropertyDef> definitions, FileRef file, String parentId,
                                           InputStream content, long contentLength, MFilesSecurityMapper.Mapped security) {
        JsonNode objVer = objectVersion.path("ObjVer");
        List<FileRef> files = files(objectVersion);
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        Map<Integer, MFilesValues.Value> values = new LinkedHashMap<>();
        for (JsonNode property : properties) {
            int pd = property.path("PropertyDef").asInt(-1);
            if (pd >= 0) {
                values.put(pd, MFilesValues.normalize(property.path("TypedValue")));
            }
        }

        String title = firstOf(values, PD_NAME_OR_TITLE);
        if (title == null) {
            title = text(objectVersion, "Title");
        }
        String fileName = file != null ? file.fileName() : null;
        String fallbackName = objVer.path("Type").asText() + "-" + objVer.path("ID").asText();
        metadata.put("name", List.of(fileName != null ? fileName : title != null ? title : fallbackName));
        if (title != null) {
            metadata.put("title", List.of(title));
        }
        String mimeType = mimeType(fileName);
        if (mimeType != null) {
            metadata.put("mimeType", List.of(mimeType));
        }

        put(metadata, "mfiles.vaultGuid", context.vaultKey());
        put(metadata, "mfiles.vaultName", context.vaultName());
        put(metadata, "mfiles.objType", objVer.path("Type").asText(null));
        put(metadata, "mfiles.objId", objVer.path("ID").asText(null));
        put(metadata, "mfiles.version", objVer.path("Version").asText(null));
        put(metadata, "mfiles.isLatestVersion", Boolean.toString(context.latest()));
        put(metadata, "mfiles.displayId", text(objectVersion, "DisplayID"));
        put(metadata, "mfiles.title", title);
        put(metadata, "mfiles.classId", objectVersion.has("Class") ? objectVersion.path("Class").asText() : idOf(values, PD_CLASS));
        put(metadata, "mfiles.className", firstOf(values, PD_CLASS));
        put(metadata, "mfiles.singleFile", Boolean.toString(isSingleFile(objectVersion, files)));
        put(metadata, "mfiles.fileCount", Integer.toString(files.size()));
        put(metadata, "mfiles.created", MFilesValues.timestamp(text(objectVersion, "CreatedUtc")));
        put(metadata, "mfiles.createdBy", firstOf(values, PD_CREATED_BY));
        put(metadata, "mfiles.lastModified", MFilesValues.timestamp(text(objectVersion, "LastModifiedUtc")));
        put(metadata, "mfiles.lastModifiedBy", firstOf(values, PD_LAST_MODIFIED_BY));
        put(metadata, "mfiles.workflow", firstOf(values, PD_WORKFLOW));
        put(metadata, "mfiles.state", firstOf(values, PD_STATE));
        if (objectVersion.path("ObjectCheckedOut").asBoolean(false)) {
            put(metadata, "mfiles.checkedOut", "true");
        }
        if (file != null) {
            put(metadata, "mfiles.fileId", Integer.toString(file.fileId()));
            put(metadata, "mfiles.fileName", fileName);
            long size = contentLength >= 0 ? contentLength : file.size();
            if (size >= 0) {
                put(metadata, "mfiles.fileSize", Long.toString(size));
            }
        }
        put(metadata, "mfiles.parentId", parentId);
        if (!security.users().isEmpty()) {
            metadata.put("mfiles_identity_users", List.of(String.join(",", security.users())));
        }
        if (!security.groups().isEmpty()) {
            metadata.put("mfiles_identity_groups", List.of(String.join(",", security.groups())));
        }

        if (context.includeProperties()) {
            String prefix = context.propertyPrefix() == null ? "" : context.propertyPrefix();
            Set<String> used = new HashSet<>();
            for (Map.Entry<Integer, MFilesValues.Value> entry : values.entrySet()) {
                MFilesValues.Value value = entry.getValue();
                if (value.isEmpty()) {
                    continue;
                }
                PropertyDef definition = definitions.get(entry.getKey());
                String key = prefix + propertyKey(definition != null ? definition.name() : null, entry.getKey());
                if (!used.add(key)) {
                    key = key + "_" + entry.getKey();
                    used.add(key);
                }
                metadata.put(key, value.values());
                if (!value.lookupIds().isEmpty()) {
                    metadata.put(key + "_id", value.lookupIds());
                }
            }
        }

        Instant lastModified = MFilesValues.parseInstant(text(objectVersion, "LastModifiedUtc")).orElseGet(Instant::now);
        return new RepositoryDocument(id, id, content, metadata, security.acl(), security.security(), lastModified,
                DocumentAction.UPSERT);
    }

    /** {@code Contract Value} → {@code contractValue}; a name with no letters or digits → {@code p<id>}. */
    static String propertyKey(String name, int id) {
        if (name == null || name.isBlank()) {
            return "p" + id;
        }
        String[] words = name.strip().split("[^\\p{L}\\p{N}]+");
        StringBuilder key = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (key.isEmpty()) {
                key.append(word.substring(0, 1).toLowerCase(Locale.ROOT)).append(word.substring(1));
            } else {
                key.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return key.isEmpty() ? "p" + id : key.toString();
    }

    private static String firstOf(Map<Integer, MFilesValues.Value> values, int propertyDef) {
        MFilesValues.Value value = values.get(propertyDef);
        return value == null || value.isEmpty() ? null : value.values().getFirst();
    }

    private static String idOf(Map<Integer, MFilesValues.Value> values, int propertyDef) {
        MFilesValues.Value value = values.get(propertyDef);
        return value == null || value.lookupIds().isEmpty() ? null : value.lookupIds().getFirst();
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
