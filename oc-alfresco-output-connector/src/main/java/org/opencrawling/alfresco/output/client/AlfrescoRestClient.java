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
package org.opencrawling.alfresco.output.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modern REST client for Alfresco Content Services v1 REST API using native Java 25 HttpClient.
 */
public class AlfrescoRestClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoRestClient.class);
    private static final String API_PREFIX = "/api/-default-/public/alfresco/versions/1";

    private final AlfrescoOutputProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String authHeader;
    private final Map<String, String> folderPathCache = new ConcurrentHashMap<>();

    public AlfrescoRestClient(AlfrescoOutputProperties properties, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AlfrescoOutputProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.properties.getTimeoutSeconds()))
                .build();
        this.authHeader = buildAuthHeader(this.properties);
    }

    public AlfrescoRestClient(AlfrescoOutputProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AlfrescoOutputProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newHttpClient();
        this.authHeader = buildAuthHeader(this.properties);
    }

    private String buildAuthHeader(AlfrescoOutputProperties props) {
        if ("bearer".equalsIgnoreCase(props.getAuthType()) && props.getBearerToken() != null && !props.getBearerToken().isBlank()) {
            return "Bearer " + props.getBearerToken().trim();
        }
        String credentials = (props.getUsername() != null ? props.getUsername() : "") + ":" +
                (props.getPassword() != null ? props.getPassword() : "");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks connection to the Alfresco repository by pinging the target parent node.
     */
    public boolean checkConnection() {
        try {
            String targetNodeId = properties.getTargetParentNodeId();
            String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(targetNodeId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.warn("Alfresco connection check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Ensures the complete hierarchical folder path exists under the target root parent node,
     * returning the final folder's node ID.
     */
    public String ensureFolderPath(String rootParentId, String relativePath) throws Exception {
        if (relativePath == null || relativePath.isBlank()) {
            return rootParentId;
        }

        String normalized = relativePath.replaceAll("^/+", "").replaceAll("/+$", "").trim();
        if (normalized.isBlank()) {
            return rootParentId;
        }

        String cacheKey = rootParentId + "::" + normalized;
        String cached = folderPathCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String currentParentId = rootParentId;
        String[] segments = normalized.split("/+");
        StringBuilder currentPathAccumulator = new StringBuilder(rootParentId);

        for (String segment : segments) {
            String folderName = segment.trim();
            if (folderName.isBlank()) continue;

            currentPathAccumulator.append("/").append(folderName);
            String segCacheKey = currentPathAccumulator.toString();
            String existingNodeId = folderPathCache.get(segCacheKey);

            if (existingNodeId != null) {
                currentParentId = existingNodeId;
                continue;
            }

            currentParentId = getOrCreateFolder(currentParentId, folderName);
            folderPathCache.put(segCacheKey, currentParentId);
        }

        folderPathCache.put(cacheKey, currentParentId);
        return currentParentId;
    }

    private String getOrCreateFolder(String parentId, String folderName) throws Exception {
        // Attempt to create the folder
        Map<String, Object> body = Map.of(
                "name", folderName,
                "nodeType", "cm:folder"
        );
        byte[] payload = objectMapper.writeValueAsBytes(body);

        String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(parentId, StandardCharsets.UTF_8) + "/children";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201) {
            JsonNode resJson = objectMapper.readTree(response.body());
            return resJson.path("entry").path("id").asText();
        }

        if (response.statusCode() == 409) {
            // Folder already exists, resolve its ID by finding it in children
            String existingId = findChildNodeByName(parentId, folderName);
            if (existingId != null) {
                return existingId;
            }
        }

        throw new IOException("Failed to create or resolve folder '" + folderName + "' under parent " + parentId
                + ": HTTP " + response.statusCode() + " - " + response.body());
    }

    /**
     * Finds a child node with the specified name under parentId.
     */
    public String findChildNodeByName(String parentId, String name) {
        try {
            int maxItems = 100;
            int skipCount = 0;
            boolean hasMore = true;

            while (hasMore) {
                String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(parentId, StandardCharsets.UTF_8)
                        + "/children?maxItems=" + maxItems + "&skipCount=" + skipCount;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", authHeader)
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode entries = root.path("list").path("entries");
                if (entries.isArray()) {
                    for (JsonNode item : entries) {
                        JsonNode entry = item.path("entry");
                        if (name.equalsIgnoreCase(entry.path("name").asText())) {
                            return entry.path("id").asText();
                        }
                    }
                }

                JsonNode pagination = root.path("list").path("pagination");
                hasMore = pagination.path("hasMoreItems").asBoolean(false);
                skipCount += maxItems;
            }
        } catch (Exception e) {
            log.debug("Error while searching child node '{}' under {}: {}", name, parentId, e.getMessage());
        }
        return null;
    }

    /**
     * Uploads document binary content and metadata to Alfresco via multipart form-data.
     */
    public NodeCreationResult uploadNode(
            String parentFolderId,
            String name,
            String nodeType,
            Map<String, Object> propertiesMap,
            List<String> aspectsList,
            Path binaryFile,
            String mimeType,
            boolean overwrite,
            boolean autoRename
    ) throws Exception {
        String boundary = "----OpenCrawlingAlfrescoBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartPayload = buildMultipartBody(
                boundary, name, nodeType, propertiesMap, aspectsList, binaryFile, mimeType, overwrite, autoRename
        );

        String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(parentFolderId, StandardCharsets.UTF_8) + "/children";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 201) {
            JsonNode entry = objectMapper.readTree(response.body()).path("entry");
            return new NodeCreationResult(entry.path("id").asText(), entry.path("name").asText(), false);
        }

        // Handle conflict if already exists
        if (response.statusCode() == 409) {
            String existingNodeId = findChildNodeByName(parentFolderId, name);
            if (existingNodeId != null && overwrite) {
                log.info("Overwriting existing Alfresco node '{}' ({}) with new content and metadata.", name, existingNodeId);
                updateNodeContentAndMetadata(existingNodeId, binaryFile, mimeType, propertiesMap, aspectsList);
                return new NodeCreationResult(existingNodeId, name, true);
            }
        }

        throw new IOException("Failed to upload node '" + name + "' to Alfresco under parent " + parentFolderId
                + ": HTTP " + response.statusCode() + " - " + response.body());
    }

    /**
     * Updates content binary and metadata of an existing node in Alfresco.
     */
    public void updateNodeContentAndMetadata(
            String nodeId,
            Path binaryFile,
            String mimeType,
            Map<String, Object> propertiesMap,
            List<String> aspectsList
    ) throws Exception {
        // 1. Update binary content stream
        String contentUrl = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8) + "/content";
        HttpRequest.BodyPublisher contentPublisher = binaryFile != null && Files.exists(binaryFile)
                ? HttpRequest.BodyPublishers.ofFile(binaryFile)
                : HttpRequest.BodyPublishers.ofByteArray(new byte[0]);

        HttpRequest contentReq = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", mimeType != null && !mimeType.isBlank() ? mimeType : "application/octet-stream")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .PUT(contentPublisher)
                .build();

        HttpResponse<String> contentRes = httpClient.send(contentReq, HttpResponse.BodyHandlers.ofString());
        if (contentRes.statusCode() < 200 || contentRes.statusCode() >= 300) {
            throw new IOException("Failed to update content for Alfresco node " + nodeId + ": HTTP "
                    + contentRes.statusCode() + " - " + contentRes.body());
        }

        // 2. Update properties and aspects
        Map<String, Object> updateBody = new HashMap<>();
        if (propertiesMap != null && !propertiesMap.isEmpty()) {
            updateBody.put("properties", propertiesMap);
        }
        if (aspectsList != null && !aspectsList.isEmpty()) {
            updateBody.put("aspectNames", aspectsList);
        }

        if (!updateBody.isEmpty()) {
            String nodeUrl = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
            byte[] bodyBytes = objectMapper.writeValueAsBytes(updateBody);

            HttpRequest metaReq = HttpRequest.newBuilder()
                    .uri(URI.create(nodeUrl))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> metaRes = httpClient.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaRes.statusCode() < 200 || metaRes.statusCode() >= 300) {
                log.warn("Warning: Updating metadata on Alfresco node {} returned HTTP {}: {}",
                        nodeId, metaRes.statusCode(), metaRes.body());
            }
        }
    }

    /**
     * Synchronizes OIS Zero-Trust SecurityConfig or ACL to Alfresco node permissions.
     */
    public void updatePermissions(
            String nodeId,
            SecurityConfig securityConfig,
            String aclString,
            String defaultRole,
            boolean isInheritanceEnabled
    ) throws Exception {
        List<Map<String, String>> locallySet = new ArrayList<>();
        Set<String> seenAuthorities = new HashSet<>();

        if (securityConfig != null && securityConfig.permissions() != null && !securityConfig.permissions().isEmpty()) {
            for (PermissionRule rule : securityConfig.permissions()) {
                String authorityId = formatAuthority(rule.identity());
                if (authorityId == null || authorityId.isBlank() || seenAuthorities.contains(authorityId)) {
                    continue;
                }
                seenAuthorities.add(authorityId);
                String role = resolveRole(rule.access(), defaultRole);
                locallySet.add(Map.of(
                        "authorityId", authorityId,
                        "name", role,
                        "accessStatus", "deny".equalsIgnoreCase(rule.access()) ? "DENIED" : "ALLOWED"
                ));
            }
        } else if (aclString != null && !aclString.isBlank() && !"public".equalsIgnoreCase(aclString.trim())) {
            String[] tokens = aclString.split("[,; ]+");
            for (String token : tokens) {
                String authorityId = formatAuthority(token.trim());
                if (authorityId == null || authorityId.isBlank() || seenAuthorities.contains(authorityId)) {
                    continue;
                }
                seenAuthorities.add(authorityId);
                locallySet.add(Map.of(
                        "authorityId", authorityId,
                        "name", defaultRole != null && !defaultRole.isBlank() ? defaultRole : "Consumer",
                        "accessStatus", "ALLOWED"
                ));
            }
        }

        if (locallySet.isEmpty() && isInheritanceEnabled) {
            // Nothing to set explicitly
            return;
        }

        Map<String, Object> permissionsBlock = new HashMap<>();
        permissionsBlock.put("isInheritanceEnabled", isInheritanceEnabled);
        permissionsBlock.put("locallySet", locallySet);

        Map<String, Object> requestPayload = Map.of("permissions", permissionsBlock);
        byte[] payloadBytes = objectMapper.writeValueAsBytes(requestPayload);

        String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Failed to set permissions on Alfresco node {}: HTTP {} - {}",
                    nodeId, response.statusCode(), response.body());
        }
    }

    private String formatAuthority(String identity) {
        if (identity == null || identity.isBlank()) return null;
        String raw = identity.trim();
        if (raw.startsWith("group:") || raw.startsWith("role:")) {
            String clean = raw.substring(raw.indexOf(":") + 1).trim();
            return clean.toUpperCase().startsWith("GROUP_") ? clean.toUpperCase() : "GROUP_" + clean.toUpperCase();
        }
        if (raw.startsWith("user:")) {
            return raw.substring(5).trim();
        }
        if (raw.startsWith("GROUP_") || raw.startsWith("ROLE_")) {
            return raw;
        }
        return raw;
    }

    private String resolveRole(String access, String defaultRole) {
        if (access == null || access.isBlank()) {
            return defaultRole != null && !defaultRole.isBlank() ? defaultRole : "Consumer";
        }
        String lower = access.trim().toLowerCase();
        if (lower.contains("coordinator") || lower.contains("admin")) {
            return "Coordinator";
        }
        if (lower.contains("collaborator")) {
            return "Collaborator";
        }
        if (lower.contains("contributor")) {
            return "Contributor";
        }
        if (lower.contains("editor")) {
            return "Editor";
        }
        if (lower.contains("write")) {
            return "Collaborator";
        }
        if (lower.contains("read") || lower.contains("consumer")) {
            return "Consumer";
        }
        return access.trim();
    }

    /**
     * Deletes a node from Alfresco (move to trashcan or permanent purge).
     */
    public boolean deleteNode(String nodeId, boolean permanent) throws Exception {
        String url = properties.getUrl() + API_PREFIX + "/nodes/" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8)
                + "?permanent=" + permanent;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 204 || response.statusCode() == 200 || response.statusCode() == 404;
    }

    private byte[] buildMultipartBody(
            String boundary,
            String name,
            String nodeType,
            Map<String, Object> propertiesMap,
            List<String> aspectsList,
            Path binaryFile,
            String mimeType,
            boolean overwrite,
            boolean autoRename
    ) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String lineSeparator = "\r\n";

        // Form field: name
        addFormField(baos, boundary, "name", name, lineSeparator);

        // Form field: nodeType
        if (nodeType != null && !nodeType.isBlank()) {
            addFormField(baos, boundary, "nodeType", nodeType, lineSeparator);
        }

        // Form field: overwrite
        addFormField(baos, boundary, "overwrite", String.valueOf(overwrite), lineSeparator);

        // Form field: autoRename
        addFormField(baos, boundary, "autoRename", String.valueOf(autoRename), lineSeparator);

        // Form field: properties
        if (propertiesMap != null && !propertiesMap.isEmpty()) {
            addFormField(baos, boundary, "properties", objectMapper.writeValueAsString(propertiesMap), lineSeparator);
        }

        // Form field: aspectNames
        if (aspectsList != null && !aspectsList.isEmpty()) {
            addFormField(baos, boundary, "aspectNames", objectMapper.writeValueAsString(aspectsList), lineSeparator);
        }

        // File part: filedata
        baos.write(("--" + boundary + lineSeparator).getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"filedata\"; filename=\"" + name + "\"" + lineSeparator).getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Type: " + (mimeType != null && !mimeType.isBlank() ? mimeType : "application/octet-stream") + lineSeparator).getBytes(StandardCharsets.UTF_8));
        baos.write(lineSeparator.getBytes(StandardCharsets.UTF_8));

        if (binaryFile != null && Files.exists(binaryFile)) {
            try (InputStream in = Files.newInputStream(binaryFile)) {
                in.transferTo(baos);
            }
        }
        baos.write(lineSeparator.getBytes(StandardCharsets.UTF_8));

        // Closing boundary
        baos.write(("--" + boundary + "--" + lineSeparator).getBytes(StandardCharsets.UTF_8));

        return baos.toByteArray();
    }

    private void addFormField(ByteArrayOutputStream baos, String boundary, String name, String value, String lineSeparator) throws IOException {
        baos.write(("--" + boundary + lineSeparator).getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"" + lineSeparator).getBytes(StandardCharsets.UTF_8));
        baos.write(lineSeparator.getBytes(StandardCharsets.UTF_8));
        baos.write(value.getBytes(StandardCharsets.UTF_8));
        baos.write(lineSeparator.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        folderPathCache.clear();
    }

    public record NodeCreationResult(String nodeId, String nodeName, boolean isUpdated) {}
}
