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
import org.opencrawling.doxis.output.client.DoxisApiException;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Filing;
import org.opencrawling.doxis.output.schema.DoxisSchema;
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
            case FIXED:
                return filing.recordId() == null ? Optional.empty() : Optional.of(new Target(filing.recordId(), folderNode, false, null));
            case METADATA: {
                String id = metadata(document, filing.recordIdMetadataKey());
                if (id == null) {
                    id = filing.recordId();
                }
                return id == null ? Optional.empty() : Optional.of(new Target(id, folderNode, false, null));
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
     * {@code relationshipParams} for a REST create (filing method {@code RELATIONSHIP}).
     */
    public Map<String, Object> relationshipParams(Target target) throws IOException, InterruptedException {
        JsonNode record = target.record() != null ? target.record() : record(target.recordId());
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("sourceObjectUUID", target.recordId());
        if (target.folderNodeId() != null) {
            relationship.put("sourceFolderNodeUUID", target.folderNodeId());
        }
        relationship.put("sourceContentRepositoryUUID", record.path("contentRepositoryUUID").asText(recordRepository()));
        relationship.put("sourceObjectInstanceDate", record.path("instanceDate").asText());
        relationship.put("sourceObjectType", "RECORD");
        return relationship;
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
            DoxisSchema.Attribute keyAttribute = schema.attribute(filing.recordKeyAttribute());
            String keyValue = mapper.fitValue(keyAttribute, key);
            String field = keyAttribute.shortName() != null ? keyAttribute.shortName() : keyAttribute.name();
            List<String> found = client.searchRecordIds("SELECT * FROM " + recordRepositoryName() + " WHERE " + field + " = '"
                    + keyValue.replace("'", "''") + "'");
            if (!found.isEmpty()) {
                if (found.size() > 1) {
                    log.warn("E-file key '{}' matches {} Doxis records {}; using {}.", key, found.size(), found, found.getFirst());
                }
                recordIdsByKey.put(key, found.getFirst());
                syncRecordAcls(found.getFirst(), document);
                return new Target(found.getFirst(), folderNode, false, null);
            }
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
        try {
            List<Map<String, Object>> aces = aclMapper.recordAces(document.security());
            if (!created) {
                Set<String> existing = new HashSet<>();
                for (JsonNode ace : client.getRecordPermissions(recordRepository(), recordId)) {
                    existing.add(ace.path("organizationalElementId").asText() + "|" + ace.path("permissionName").asText() + "|"
                            + ace.path("authorizationVariant").asText());
                }
                aces = aces.stream().filter(a -> !existing.contains(a.get("organizationalElementId") + "|" + a.get("permission") + "|"
                        + a.get("authorizationVariant"))).toList();
            }
            client.addRecordPermissions(recordRepository(), recordId, aces);
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
        return folder.endsWith(":") || folder.endsWith(":/") ? null : folder;
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
