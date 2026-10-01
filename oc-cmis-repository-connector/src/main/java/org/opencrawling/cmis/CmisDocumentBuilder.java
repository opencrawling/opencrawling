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
package org.opencrawling.cmis;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Builds Open Ingestion Standard (OIS) RepositoryDocument instances from
 * OASIS CMIS 1.1 JSON objects (supporting both verbose and succinct properties).
 */
public class CmisDocumentBuilder {

    public static RepositoryDocument buildDocument(
            String repositoryId,
            JsonNode objectNode,
            InputStream contentStream,
            boolean includeAcls) {
        
        JsonNode actualNode = objectNode.has("object") ? objectNode.get("object") : objectNode;

        String objectId = getPropertyValue(actualNode, "cmis:objectId");
        if (objectId == null || objectId.isBlank()) {
            objectId = actualNode.path("id").asText("unknown-cmis-id");
        }

        String name = getPropertyValue(actualNode, "cmis:name");
        if (name == null || name.isBlank()) {
            name = objectId;
        }

        String path = getPropertyValue(actualNode, "cmis:path");
        String uri = (path != null && !path.isBlank())
                ? "cmis://" + repositoryId + (path.startsWith("/") ? path : "/" + path)
                : "cmis://" + repositoryId + "/documents/" + objectId;

        String docId = "cmis://" + repositoryId + "/documents/" + objectId;

        // Parse last modification timestamp
        String modDateStr = getPropertyValue(actualNode, "cmis:lastModificationDate");
        Instant lastModified = parseTimestamp(modDateStr);

        // Build metadata
        Map<String, List<String>> metadata = new HashMap<>();
        
        // Standard OIS fields
        metadata.put("name", List.of(name));
        metadata.put("cmis.objectId", List.of(objectId));
        metadata.put("cmis.name", List.of(name));

        String mimeType = getPropertyValue(actualNode, "cmis:contentStreamMimeType");
        if (mimeType != null && !mimeType.isBlank()) {
            metadata.put("mimeType", List.of(mimeType));
            metadata.put("cmis.contentStreamMimeType", List.of(mimeType));
        }

        putIfPresent(metadata, "cmis.baseTypeId", getPropertyValue(actualNode, "cmis:baseTypeId"));
        putIfPresent(metadata, "cmis.objectTypeId", getPropertyValue(actualNode, "cmis:objectTypeId"));
        putIfPresent(metadata, "cmis.createdBy", getPropertyValue(actualNode, "cmis:createdBy"));
        putIfPresent(metadata, "cmis.creationDate", getPropertyValue(actualNode, "cmis:creationDate"));
        putIfPresent(metadata, "cmis.lastModifiedBy", getPropertyValue(actualNode, "cmis:lastModifiedBy"));
        putIfPresent(metadata, "cmis.lastModificationDate", modDateStr);
        putIfPresent(metadata, "cmis.versionLabel", getPropertyValue(actualNode, "cmis:versionLabel"));
        putIfPresent(metadata, "cmis.isLatestVersion", getPropertyValue(actualNode, "cmis:isLatestVersion"));
        putIfPresent(metadata, "cmis.isLatestMajorVersion", getPropertyValue(actualNode, "cmis:isLatestMajorVersion"));
        putIfPresent(metadata, "cmis.contentStreamLength", getPropertyValue(actualNode, "cmis:contentStreamLength"));
        putIfPresent(metadata, "cmis.contentStreamFileName", getPropertyValue(actualNode, "cmis:contentStreamFileName"));
        if (path != null && !path.isBlank()) {
            metadata.put("cmis.path", List.of(path));
        }

        // Secondary types
        List<String> secondaryTypes = getPropertyValues(actualNode, "cmis:secondaryObjectTypeIds");
        if (!secondaryTypes.isEmpty()) {
            metadata.put("cmis.secondaryObjectTypeIds", secondaryTypes);
        }

        // Extract all other properties (including custom aspects / properties)
        extractCustomProperties(actualNode, metadata);

        // Security / ACLs
        SecurityConfig securityConfig = includeAcls
                ? CmisSecurityMapper.mapAcl(actualNode.path("acl"))
                : SecurityConfig.createPublic();

        return new RepositoryDocument(
            docId,
            uri,
            contentStream,
            metadata,
            "",
            securityConfig,
            lastModified,
            DocumentAction.UPSERT
        );
    }

    public static RepositoryDocument buildTombstone(String repositoryId, String objectId) {
        String docId = "cmis://" + repositoryId + "/documents/" + objectId;
        String uri = "cmis://" + repositoryId + "/documents/" + objectId;
        return RepositoryDocument.createTombstone(docId, uri);
    }

    public static String getPropertyValue(JsonNode node, String propertyId) {
        // 1. Check succinctProperties
        JsonNode succinct = node.path("succinctProperties");
        if (succinct.isObject() && succinct.has(propertyId)) {
            JsonNode val = succinct.get(propertyId);
            return val.isValueNode() ? val.asText() : val.toString();
        }

        // 2. Check verbose properties map
        JsonNode props = node.path("properties");
        if (props.isObject() && props.has(propertyId)) {
            JsonNode prop = props.get(propertyId);
            if (prop.has("value") && !prop.get("value").isNull()) {
                return prop.get("value").asText();
            }
            if (prop.has("values") && prop.get("values").isArray() && !prop.get("values").isEmpty()) {
                return prop.get("values").get(0).asText();
            }
            if (prop.isValueNode()) {
                return prop.asText();
            }
        }

        // 3. Fallback direct property on node
        if (node.has(propertyId)) {
            JsonNode val = node.get(propertyId);
            return val.isValueNode() ? val.asText() : val.toString();
        }

        return null;
    }

    public static List<String> getPropertyValues(JsonNode node, String propertyId) {
        List<String> values = new ArrayList<>();
        
        // 1. Check succinctProperties
        JsonNode succinct = node.path("succinctProperties");
        if (succinct.isObject() && succinct.has(propertyId)) {
            JsonNode val = succinct.get(propertyId);
            if (val.isArray()) {
                val.forEach(v -> values.add(v.asText()));
                return values;
            } else if (val.isValueNode()) {
                values.add(val.asText());
                return values;
            }
        }

        // 2. Check verbose properties map
        JsonNode props = node.path("properties");
        if (props.isObject() && props.has(propertyId)) {
            JsonNode prop = props.get(propertyId);
            if (prop.has("values") && prop.get("values").isArray()) {
                prop.get("values").forEach(v -> values.add(v.asText()));
                return values;
            }
            if (prop.has("value") && !prop.get("value").isNull()) {
                values.add(prop.get("value").asText());
                return values;
            }
        }

        return values;
    }

    private static void extractCustomProperties(JsonNode node, Map<String, List<String>> metadata) {
        // Collect from succinctProperties
        JsonNode succinct = node.path("succinctProperties");
        if (succinct.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = succinct.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (!key.startsWith("cmis:") && !metadata.containsKey("cmis_prop_" + sanitizeKey(key))) {
                    JsonNode val = field.getValue();
                    if (val.isArray()) {
                        List<String> list = new ArrayList<>();
                        val.forEach(v -> list.add(v.asText()));
                        metadata.put("cmis_prop_" + sanitizeKey(key), list);
                    } else if (val.isValueNode() && !val.isNull()) {
                        metadata.put("cmis_prop_" + sanitizeKey(key), List.of(val.asText()));
                    }
                }
            }
        }

        // Collect from verbose properties
        JsonNode props = node.path("properties");
        if (props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (!key.startsWith("cmis:") && !metadata.containsKey("cmis_prop_" + sanitizeKey(key))) {
                    JsonNode propNode = field.getValue();
                    if (propNode.has("values") && propNode.get("values").isArray()) {
                        List<String> list = new ArrayList<>();
                        propNode.get("values").forEach(v -> list.add(v.asText()));
                        metadata.put("cmis_prop_" + sanitizeKey(key), list);
                    } else if (propNode.has("value") && !propNode.get("value").isNull()) {
                        metadata.put("cmis_prop_" + sanitizeKey(key), List.of(propNode.get("value").asText()));
                    }
                }
            }
        }
    }

    private static String sanitizeKey(String key) {
        return key.replace(':', '_').replace('.', '_').replace('-', '_');
    }

    private static void putIfPresent(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            // Check if numeric (epoch milliseconds)
            long epochMillis = Long.parseLong(value);
            return Instant.ofEpochMilli(epochMillis);
        } catch (NumberFormatException nfe) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException dtpe) {
                return Instant.now();
            }
        }
    }
}
