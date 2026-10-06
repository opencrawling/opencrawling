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
package org.opencrawling.doxis.client.schema;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.doxis.client.DoxisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lazily loaded, cached view of the Doxis schema and organisation needed to write documents: the document type, attribute
 * definitions (name → UUID, data type, length, multi-value) and organisational elements (user/group name → UUID).
 */
public class DoxisSchema {

    private static final Logger log = LoggerFactory.getLogger(DoxisSchema.class);

    /** An attribute definition as used in {@code AttributeParams}. */
    public record Attribute(String uuid, String name, String shortName, String dataType, int length, boolean multiValue) {
    }

    private final DoxisClient client;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Map<String, Attribute> attributesByName;
    private volatile Map<String, String> documentTypeIdsByName;
    private volatile Map<String, Set<String>> allowedMimeTypesByType;
    private volatile Map<String, String> principalsByName;
    private volatile Set<String> mimeTypes;
    private volatile Map<String, String> recordTypeIdsByName;

    public DoxisSchema(DoxisClient client) {
        this.client = client;
    }

    /**
     * Resolves a document type given by UUID or name.
     */
    public String documentTypeId(String nameOrId) throws IOException, InterruptedException {
        Map<String, String> types = documentTypes();
        String byName = types.get(nameOrId.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        if (types.containsValue(nameOrId)) {
            return nameOrId;
        }
        throw new IOException("Doxis document type '" + nameOrId + "' not found (available: " + types.keySet() + ")");
    }

    /**
     * Resolves an attribute definition by name, short name or UUID.
     */
    public Attribute attribute(String nameOrId) throws IOException, InterruptedException {
        Attribute attribute = attributes().get(nameOrId.toLowerCase(Locale.ROOT));
        if (attribute == null) {
            throw new IOException("Doxis attribute definition '" + nameOrId + "' not found");
        }
        return attribute;
    }

    public Optional<Attribute> findAttribute(String nameOrId) throws IOException, InterruptedException {
        return Optional.ofNullable(attributes().get(nameOrId.toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolves an OIS identity (user login name, user name or group name; case-insensitive) to an organisational element UUID.
     * {@code public} / {@code everybody} map to the built-in {@code everybody} group.
     */
    public Optional<String> principalId(String identity) throws IOException, InterruptedException {
        if (identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        String key = identity.strip().toLowerCase(Locale.ROOT);
        if (key.startsWith("group:") || key.startsWith("user:")) {
            key = key.substring(key.indexOf(':') + 1);
        }
        if ("public".equals(key)) {
            key = "everybody";
        }
        return Optional.ofNullable(principals().get(key));
    }

    /**
     * Returns {@code mimeType} if Doxis knows it, otherwise {@code application/octet-stream} (Doxis rejects unknown MIME types).
     */
    public String knownMimeType(String mimeType) throws IOException, InterruptedException {
        Set<String> known = mimeTypes;
        if (known == null) {
            lock.lock();
            try {
                if (mimeTypes == null) {
                    Set<String> set = new HashSet<>();
                    for (JsonNode node : client.listMimeTypes()) {
                        set.add(node.path("mimeName").asText().toLowerCase(Locale.ROOT));
                    }
                    mimeTypes = set;
                }
                known = mimeTypes;
            } finally {
                lock.unlock();
            }
        }
        String candidate = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        int semicolon = candidate.indexOf(';');
        if (semicolon > 0) {
            candidate = candidate.substring(0, semicolon).strip();
        }
        return known.contains(candidate) ? candidate : "application/octet-stream";
    }

    /**
     * Checks {@code mimeType} against the document type's {@code allowedMimeTypes} (an empty list allows every type). Doxis
     * rejects other MIME types with {@code SEDNA0204}; {@code application/octet-stream} is used when it is allowed instead.
     */
    public String allowedMimeType(String documentType, String mimeType) throws IOException, InterruptedException {
        String typeId = documentTypeId(documentType);
        Set<String> allowed = allowedMimeTypesByType.getOrDefault(typeId, Set.of());
        if (allowed.isEmpty() || allowed.contains(mimeType)) {
            return mimeType;
        }
        if (allowed.contains("application/octet-stream")) {
            return "application/octet-stream";
        }
        throw new IOException("MIME type '" + mimeType + "' is not allowed by Doxis document type '" + documentType
                + "' (allowed: " + allowed + ")");
    }

    /**
     * Resolves an e-file (record) class given by UUID or name ({@code schemaMetaType RECORD}).
     */
    public String recordTypeId(String nameOrId) throws IOException, InterruptedException {
        Map<String, String> types = recordTypeIdsByName;
        if (types == null) {
            lock.lock();
            try {
                if (recordTypeIdsByName == null) {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (JsonNode node : client.listInformationObjectTypes()) {
                        if ("RECORD".equals(node.path("schemaMetaType").asText())) {
                            map.put(node.path("name").asText().toLowerCase(Locale.ROOT), node.path("uuid").asText());
                        }
                    }
                    recordTypeIdsByName = map;
                }
                types = recordTypeIdsByName;
            } finally {
                lock.unlock();
            }
        }
        String byName = types.get(nameOrId.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        if (types.containsValue(nameOrId)) {
            return nameOrId;
        }
        throw new IOException("Doxis e-file (record) class '" + nameOrId + "' not found (available: " + types.keySet() + ")");
    }

    /**
     * Drops cached schema and organisation data (e.g. after a schema change in cubeDesigner).
     */
    public void invalidate() {
        attributesByName = null;
        documentTypeIdsByName = null;
        allowedMimeTypesByType = null;
        principalsByName = null;
        mimeTypes = null;
        recordTypeIdsByName = null;
    }

    private Map<String, Attribute> attributes() throws IOException, InterruptedException {
        Map<String, Attribute> cached = attributesByName;
        if (cached != null) {
            return cached;
        }
        lock.lock();
        try {
            if (attributesByName == null) {
                Map<String, Attribute> map = new HashMap<>();
                for (JsonNode node : client.listAttributeDefinitions()) {
                    Attribute attribute = new Attribute(node.path("uuid").asText(), node.path("name").asText(),
                            node.path("shortName").asText(null), node.path("attributeDataType").asText("STRING"),
                            node.path("length").asInt(0), !"NONE".equals(node.path("multivalueType").asText("NONE")));
                    map.put(attribute.uuid().toLowerCase(Locale.ROOT), attribute);
                    if (attribute.shortName() != null) {
                        map.putIfAbsent(attribute.shortName().toLowerCase(Locale.ROOT), attribute);
                    }
                    map.put(attribute.name().toLowerCase(Locale.ROOT), attribute);
                }
                log.info("Loaded {} Doxis attribute definitions.", map.values().stream().distinct().count());
                attributesByName = map;
            }
            return attributesByName;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> documentTypes() throws IOException, InterruptedException {
        Map<String, String> cached = documentTypeIdsByName;
        if (cached != null) {
            return cached;
        }
        lock.lock();
        try {
            if (documentTypeIdsByName == null) {
                Map<String, String> map = new LinkedHashMap<>();
                Map<String, Set<String>> mimes = new HashMap<>();
                for (JsonNode node : client.listDocumentTypes()) {
                    String uuid = node.path("uuid").asText();
                    map.put(node.path("name").asText().toLowerCase(Locale.ROOT), uuid);
                    Set<String> allowed = new LinkedHashSet<>();
                    node.path("allowedMimeTypes").forEach(m -> allowed.add(m.path("mimeName").asText().toLowerCase(Locale.ROOT)));
                    mimes.put(uuid, allowed);
                }
                allowedMimeTypesByType = mimes;
                documentTypeIdsByName = map;
            }
            return documentTypeIdsByName;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> principals() throws IOException, InterruptedException {
        Map<String, String> cached = principalsByName;
        if (cached != null) {
            return cached;
        }
        lock.lock();
        try {
            if (principalsByName == null) {
                Map<String, String> map = new HashMap<>();
                for (JsonNode group : client.listGroups()) {
                    map.put(group.path("name").asText().toLowerCase(Locale.ROOT), group.path("uuid").asText());
                }
                for (JsonNode user : client.listUsers()) {
                    String uuid = user.path("uuid").asText();
                    map.putIfAbsent(user.path("name").asText().toLowerCase(Locale.ROOT), uuid);
                    String login = user.path("loginName").asText(null);
                    if (login != null) {
                        map.putIfAbsent(login.toLowerCase(Locale.ROOT), uuid);
                    }
                }
                principalsByName = map;
            }
            return principalsByName;
        } finally {
            lock.unlock();
        }
    }
}
