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

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.doxis.client.DoxisApiException;
import org.opencrawling.doxis.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Filing;
import org.opencrawling.doxis.client.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides into which e-file (Doxis record) a new document is filed, per {@link DoxisOutputProperties.FilingMode}, and — for the
 * keyed modes — finds the e-file by its key descriptor or creates it ({@code filing.auto-create}). When
 * {@code security.mode} includes {@code RECORD}, the OIS permissions are applied to the e-file so the documents inside inherit
 * them ("Primary parent objects → Pass down permissions" on the document class).
 */
public class DoxisRecordFiler {

    private static final Logger log = LoggerFactory.getLogger(DoxisRecordFiler.class);

    /** The e-file a document goes into; {@code record} holds its {@code CompoundEntityWsTO} once known. */
    public record Target(String recordId, String folderNodeId, boolean createdNow, JsonNode record) {
    }

    private final DoxisOutputProperties properties;
    private final DoxisClient client;
    private final DoxisSchema schema;
    private final DoxisDocumentMapper mapper;
    private final DoxisAclMapper aclMapper;
    private final Map<String, String> recordIdsByKey = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> records = new ConcurrentHashMap<>();
    private final Map<String, String> folderNodesByRecord = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> recordAceKeys = new ConcurrentHashMap<>();
    private volatile String recordRepositoryName;

    public DoxisRecordFiler(DoxisOutputProperties properties, DoxisClient client, DoxisSchema schema,
                            DoxisDocumentMapper mapper, DoxisAclMapper aclMapper) {
        this.properties = properties;
        this.client = client;
        this.schema = schema;
        this.mapper = mapper;
        this.aclMapper = aclMapper;
    }

    /**
     * The e-file for {@code document}, or empty when the document is not filed.
     */
    public Optional<Target> target(RepositoryDocument document) throws IOException, InterruptedException {
        Filing filing = properties.filing();
        String folderNode = metadata(document, filing.folderNodeMetadataKey());
        if (folderNode == null) {
            folderNode = filing.folderNodeId();
        }
        switch (filing.mode()) {
            case NONE:
                return Optional.empty();
            case FIXED, METADATA: {
                String id = configuredRecordId(document);
                if (id == null) {
                    return Optional.empty();
                }
                syncRecordAcls(id, document);
                return Optional.of(new Target(id, folderNode, false, null));
            }
            case SOURCE_FOLDER: {
                String folder = sourceFolder(document.uri());
                return folder == null ? Optional.empty() : Optional.of(keyed(document, folder, folderTitle(folder), folderNode));
            }
            case KEY_METADATA: {
                String key = filing.recordKeyMetadataKey() != null ? metadata(document, filing.recordKeyMetadataKey()) : null;
                return key == null ? Optional.empty() : Optional.of(keyed(document, key, key, folderNode));
            }
            default:
                return Optional.empty();
        }
    }

    /**
     * Forgets everything cached about e-file {@code recordId} when {@code failure} says it (or a node in it) no longer exists
     * — HTTP 404 or a Doxis {@code INSTANCE*} error — so the next document looks it up again. Transient errors keep the cache.
     */
    public void evictIfStale(String recordId, Exception failure) {
        if (recordId == null || !(failure instanceof DoxisApiException api)
                || !(api.getStatusCode() == 404 || (api.getErrorCode() != null && api.getErrorCode().startsWith("INSTANCE")))) {
            return;
        }
        folderNodesByRecord.remove(recordId);
        recordAceKeys.remove(recordId);
        records.remove(recordId);
        recordIdsByKey.values().removeIf(recordId::equals);
        log.info("Dropped cached state of Doxis e-file {} after {}.", recordId, api.getErrorCode() != null ? api.getErrorCode() : "HTTP 404");
    }

    /**
     * {@code ADDITIVE} record ACL sync for a document that is already archived (a re-crawl): adds the document's missing
     * ACEs to its e-file. Never creates an e-file; does nothing unless {@code security.record-acl-sync} is {@code ADDITIVE}.
     */
    public void syncExisting(RepositoryDocument document) throws IOException, InterruptedException {
        if (!properties.security().recordAcls() || properties.security().recordAclSync() != DoxisOutputProperties.RecordAclSync.ADDITIVE) {
            return;
        }
        String recordId = switch (properties.filing().mode()) {
            case FIXED, METADATA -> configuredRecordId(document);
            case SOURCE_FOLDER -> {
                String folder = sourceFolder(document.uri());
                yield folder == null ? null : findRecord(folder);
            }
            case KEY_METADATA -> {
                String key = properties.filing().recordKeyMetadataKey() != null
                        ? metadata(document, properties.filing().recordKeyMetadataKey()) : null;
                yield key == null ? null : findRecord(key);
            }
            default -> null;
        };
        if (recordId != null) {
            applyRecordAcls(recordId, document, false);
        }
    }

    private String configuredRecordId(RepositoryDocument document) {
        if (properties.filing().mode() == DoxisOutputProperties.FilingMode.METADATA) {
            String id = metadata(document, properties.filing().recordIdMetadataKey());
            if (id != null) {
                return id;
            }
        }
        return properties.filing().recordId();
    }

    /** The e-file with key {@code key} (cached, else searched); null when there is none. */
    private String findRecord(String key) throws IOException, InterruptedException {
        String cached = recordIdsByKey.get(key);
        if (cached != null) {
            return cached;
        }
        DoxisSchema.Attribute keyAttribute = schema.attribute(properties.filing().recordKeyAttribute());
        String keyValue = mapper.fitValue(keyAttribute, key);
        String field = keyAttribute.shortName() != null ? keyAttribute.shortName() : keyAttribute.name();
        List<String> found = client.searchRecordIds("SELECT * FROM " + recordRepositoryName() + " WHERE " + field + " = '"
                + keyValue.replace("'", "''") + "'");
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            log.warn("E-file key '{}' matches {} Doxis records {}; using {}.", key, found.size(), found, found.getFirst());
        }
        recordIdsByKey.put(key, found.getFirst());
        return found.getFirst();
    }

    /**
     * {@code relationshipParams} for a REST create (filing method {@code RELATIONSHIP}). Without an explicit folder node the
     * document goes into the node named {@code filing.folder-node-name} of the e-file, which is created when missing.
     */
    public Map<String, Object> relationshipParams(Target target, String documentTypeId) throws IOException, InterruptedException {
        JsonNode record;
        String folderNode;
        try {
            record = target.record() != null ? target.record() : record(target.recordId());
            folderNode = target.folderNodeId() != null ? target.folderNodeId() : folderNode(target.recordId(), documentTypeId);
        } catch (IOException e) {
            evictIfStale(target.recordId(), e);
            throw e;
        }
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("sourceObjectUUID", target.recordId());
        relationship.put("sourceFolderNodeUUID", folderNode);
        relationship.put("sourceContentRepositoryUUID", record.path("contentRepositoryUUID").asText(recordRepository()));
        relationship.put("sourceObjectInstanceDate", record.path("instanceDate").asText());
        relationship.put("sourceObjectType", "RECORD");
        return relationship;
    }

    /**
     * The document-capable folder node named {@code filing.folder-node-name} in the e-file: found in its node tree or, with
     * {@code filing.auto-create}, created as a local {@code STATIC} node under the root (the e-file root node is
     * {@code NODES_ONLY} and cannot hold documents itself).
     */
    private String folderNode(String recordId, String documentTypeId) throws IOException, InterruptedException {
        String cached = folderNodesByRecord.get(recordId);
        if (cached != null) {
            return cached;
        }
        synchronized (keyLocks.computeIfAbsent("node|" + recordId, k -> new Object())) {
            cached = folderNodesByRecord.get(recordId);
            if (cached != null) {
                return cached;
            }
            Filing filing = properties.filing();
            String name = filing.folderNodeName();
            JsonNode version = currentVersion(client.getRecordWithNodes(recordRepository(), recordId));
            List<JsonNode> nodes = new ArrayList<>();
            collectNodes(version.path("folderNodes"), nodes, new HashSet<>());
            for (JsonNode node : nodes) {
                if (name.equalsIgnoreCase(node.path("name").asText()) && !node.path("logicalDeleted").asBoolean(false)) {
                    if (!holdsDocuments(node, documentTypeId)) {
                        throw new IOException("Folder node '" + name + "' (" + node.path("uuid").asText() + ") of e-file " + recordId
                                + " cannot hold documents of this class (nodeMetaType " + node.path("nodeMetaType").asText()
                                + "); set filing.folder-node-name to a document node or use filing method PRIMARY_PARENT");
                    }
                    folderNodesByRecord.put(recordId, node.path("uuid").asText());
                    return node.path("uuid").asText();
                }
            }
            if (!filing.autoCreate()) {
                throw new IOException("E-file " + recordId + " has no folder node '" + name + "' and filing.auto-create is disabled");
            }
            String root = rootNode(version, nodes);
            if (root == null) {
                throw new IOException("E-file " + recordId + " has no root folder node to create '" + name + "' under");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("nodeName", name);
            params.put("parentFolderNodeUUID", root);
            params.put("nodeMetaType", "STATIC");
            params.put("allowedTargetInformationObjectTypeUUIDs", List.of(documentTypeId));
            params.put("defaultTargetInformationObjectTypeUUID", documentTypeId);
            String created = client.createFolderNode(recordRepository(), recordId, params);
            log.info("Created folder node '{}' ({}) in Doxis e-file {}.", name, created, recordId);
            folderNodesByRecord.put(recordId, created);
            return created;
        }
    }

    private static JsonNode currentVersion(JsonNode record) {
        JsonNode last = record;
        for (JsonNode version : record.path("versions")) {
            if (version.path("currentVersion").asBoolean(false)) {
                return version;
            }
            last = version;
        }
        return last;
    }

    private static void collectNodes(JsonNode nodes, List<JsonNode> into, Set<String> seen) {
        for (JsonNode node : nodes) {
            if (seen.add(node.path("uuid").asText())) {
                into.add(node);
            }
            collectNodes(node.path("childrenFolderNodes"), into, seen);
        }
    }

    /** A {@code STATIC} node whose allowed target types (if restricted) include the document class. */
    private static boolean holdsDocuments(JsonNode node, String documentTypeId) {
        if (!"STATIC".equals(node.path("nodeMetaType").asText())) {
            return false;
        }
        JsonNode allowed = node.path("allowedTargetInformationObjectTypeUUIDs");
        if (!allowed.isArray() || allowed.isEmpty()) {
            return true;
        }
        for (JsonNode type : allowed) {
            if (type.asText().equals(documentTypeId)) {
                return true;
            }
        }
        return false;
    }

    private static String rootNode(JsonNode version, List<JsonNode> nodes) {
        String reference = version.path("rootFolderNodeReferenceUUID").asText("");
        for (JsonNode node : nodes) {
            if (!reference.isBlank() && reference.equals(node.path("uuid").asText())) {
                return reference;
            }
        }
        for (JsonNode node : nodes) {
            if (node.path("parentFolderNodeUUID").asText("").isBlank()) {
                return node.path("uuid").asText();
            }
        }
        return reference.isBlank() ? null : reference;
    }

    private Target keyed(RepositoryDocument document, String key, String title, String folderNode) throws IOException, InterruptedException {
        String cached = recordIdsByKey.get(key);
        if (cached != null) {
            syncRecordAcls(cached, document);
            return new Target(cached, folderNode, false, records.get(cached));
        }
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = recordIdsByKey.get(key);
            if (cached != null) {
                syncRecordAcls(cached, document);
                return new Target(cached, folderNode, false, records.get(cached));
            }
            Filing filing = properties.filing();
            String existing = findRecord(key);
            if (existing != null) {
                syncRecordAcls(existing, document);
                return new Target(existing, folderNode, false, null);
            }
            DoxisSchema.Attribute keyAttribute = schema.attribute(filing.recordKeyAttribute());
            String keyValue = mapper.fitValue(keyAttribute, key);
            if (!filing.autoCreate()) {
                throw new IOException("No Doxis e-file with " + filing.recordKeyAttribute() + " = '" + keyValue
                        + "' and filing.auto-create is disabled (document " + document.id() + ")");
            }
            if (filing.recordClass() == null) {
                throw new IllegalStateException("filing.record-class is required to auto-create e-files");
            }
            JsonNode created = createRecord(keyAttribute, keyValue, title);
            String recordId = created.path("uuid").asText();
            records.put(recordId, created);
            recordIdsByKey.put(key, recordId);
            log.info("Created Doxis e-file {} ('{}') for key '{}'.", recordId, title, key);
            if (properties.security().recordAcls()) {
                applyRecordAcls(recordId, document, true);
            }
            return new Target(recordId, folderNode, true, created);
        }
    }

    private JsonNode createRecord(DoxisSchema.Attribute keyAttribute, String keyValue, String title) throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("compoundEntityTypeUUID", schema.recordTypeId(properties.filing().recordClass()));
        List<Map<String, Object>> attributes = new ArrayList<>();
        attributes.add(attribute(keyAttribute, keyValue));
        DoxisSchema.Attribute titleAttribute = schema.attribute(properties.filing().recordTitleAttribute());
        if (!titleAttribute.uuid().equals(keyAttribute.uuid())) {
            attributes.add(attribute(titleAttribute, mapper.fitValue(titleAttribute, title, false)));
        }
        params.put("attributes", attributes);
        return client.createRecord(recordRepository(), params);
    }

    private static Map<String, Object> attribute(DoxisSchema.Attribute attribute, String value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("attributeDefinitionUUID", attribute.uuid());
        params.put("attributeDataType", attribute.dataType());
        params.put("values", List.of(value));
        return params;
    }

    /** {@code ADDITIVE} record ACL sync for e-files that already exist. */
    private void syncRecordAcls(String recordId, RepositoryDocument document) throws IOException, InterruptedException {
        if (properties.security().recordAcls() && properties.security().recordAclSync() == DoxisOutputProperties.RecordAclSync.ADDITIVE) {
            applyRecordAcls(recordId, document, false);
        }
    }

    private void applyRecordAcls(String recordId, RepositoryDocument document, boolean created) throws IOException, InterruptedException {
        synchronized (keyLocks.computeIfAbsent("acl|" + recordId, k -> new Object())) {
            try {
                applyRecordAclsLocked(recordId, document, created);
            } catch (IOException e) {
                evictIfStale(recordId, e);
                throw e;
            }
        }
    }

    private void applyRecordAclsLocked(String recordId, RepositoryDocument document, boolean created) throws IOException, InterruptedException {
        try {
            List<Map<String, Object>> aces = new ArrayList<>(aclMapper.recordAces(document.security()));
            if (created && properties.security().grantConnectorUser()) {
                String self = connectorUserId();
                for (String permission : DoxisAclMapper.CONNECTOR_RECORD_PERMISSIONS) {
                    Map<String, Object> ace = new LinkedHashMap<>();
                    ace.put("organizationalElementId", self);
                    ace.put("permission", permission);
                    ace.put("authorizationVariant", "GRANT");
                    if (aces.stream().noneMatch(a -> self.equals(a.get("organizationalElementId")) && permission.equals(a.get("permission"))
                            && "GRANT".equals(a.get("authorizationVariant")))) {
                        aces.add(ace);
                    }
                }
            }
            // the e-file's ACEs are read once per run; later documents of the same e-file only add what is new
            Set<String> known = recordAceKeys.get(recordId);
            if (!created && known == null) {
                known = new HashSet<>();
                for (JsonNode ace : client.getRecordPermissions(recordRepository(), recordId)) {
                    known.add(ace.path("organizationalElementId").asText() + "|" + ace.path("permissionName").asText() + "|"
                            + ace.path("authorizationVariant").asText());
                }
            }
            Set<String> present = known == null ? Set.of() : known;
            aces = aces.stream().filter(a -> !present.contains(aceKey(a))).toList();
            client.addRecordPermissions(recordRepository(), recordId, aces);
            Set<String> updated = new HashSet<>(present);
            aces.forEach(a -> updated.add(aceKey(a)));
            recordAceKeys.put(recordId, updated);
            List<String> unresolved = aclMapper.unresolvedIdentities(document.security());
            if (properties.security().strict() && !unresolved.isEmpty()) {
                throw new IOException("Identities " + unresolved + " have no Doxis user or group (security.strict)");
            }
        } catch (DoxisApiException e) {
            if ("SECU0050".equals(e.getErrorCode()) && !properties.security().strict()) {
                log.warn("E-file class '{}' does not allow per-record permissions; e-file {} keeps its class ACL.",
                        properties.filing().recordClass(), recordId);
                return;
            }
            throw e;
        }
    }

    private static String aceKey(Map<String, Object> ace) {
        return ace.get("organizationalElementId") + "|" + ace.get("permission") + "|" + ace.get("authorizationVariant");
    }

    private volatile String connectorUserId;

    private String connectorUserId() throws IOException, InterruptedException {
        String id = connectorUserId;
        if (id == null) {
            id = client.getLoggedInUser().path("uuid").asText();
            connectorUserId = id;
        }
        return id;
    }

    private JsonNode record(String recordId) throws IOException, InterruptedException {
        JsonNode cached = records.get(recordId);
        if (cached != null) {
            return cached;
        }
        JsonNode record = client.getRecord(recordRepository(), recordId);
        records.put(recordId, record);
        return record;
    }

    private String recordRepository() {
        return properties.filing().recordRepository() != null ? properties.filing().recordRepository() : properties.repository();
    }

    private String recordRepositoryName() throws IOException, InterruptedException {
        String name = recordRepositoryName;
        if (name == null) {
            JsonNode repository = client.getRepository(recordRepository());
            String shortName = repository.path("shortName").asText("");
            name = !shortName.isBlank() ? shortName : repository.path("name").asText(recordRepository());
            recordRepositoryName = name;
        }
        return name;
    }

    /** {@code file:///share/customers/4711/contract.pdf} → {@code file:///share/customers/4711}. */
    static String sourceFolder(String uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String folder = path.substring(0, slash);
        // a file directly under the root ("file:///c.pdf", "s3://bucket" …) has no source folder to file into
        return folder.endsWith(":") || folder.endsWith(":/") || folder.endsWith("://") ? null : folder;
    }

    static String folderTitle(String folder) {
        String name = folder.substring(folder.lastIndexOf('/') + 1);
        name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
        return name.isBlank() ? folder : name;
    }

    private static String metadata(RepositoryDocument document, String key) {
        if (key == null || document.metadata() == null) {
            return null;
        }
        List<String> values = document.metadata().get(key);
        return values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                ? null : values.getFirst().strip();
    }
}
