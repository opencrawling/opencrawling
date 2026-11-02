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
package org.opencrawling.alfresco.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.opencrawling.alfresco.output.client.AlfrescoRestClient;
import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.opencrawling.alfresco.output.model.OisMigrationDocument;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Alfresco Content Services Output Connector dedicated exclusively to Migration Mode.
 * Migrates binary contents as-is directly from Claim Check storage into Alfresco repository
 * nodes, recreating folder structures, attaching metadata/aspects, and syncing Zero-Trust ACLs.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "alfresco")
@EnableConfigurationProperties(AlfrescoOutputProperties.class)
public class AlfrescoOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoOutputConnector.class);

    private final AlfrescoOutputProperties properties;
    private final AlfrescoRestClient client;
    private final ObjectMapper objectMapper;
    private PipelineMode pipelineMode;

    public AlfrescoOutputConnector() {
        this(new AlfrescoOutputProperties(), null, new PipelineProperties(), new ObjectMapper());
    }

    public AlfrescoOutputConnector(AlfrescoOutputProperties properties, PipelineProperties pipelineProperties) {
        this(properties, null, pipelineProperties, new ObjectMapper());
    }

    @Autowired
    public AlfrescoOutputConnector(
            AlfrescoOutputProperties properties,
            @Autowired(required = false) AlfrescoRestClient client,
            @Autowired(required = false) PipelineProperties pipelineProperties,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new AlfrescoOutputProperties();
        this.pipelineMode = pipelineProperties != null ? pipelineProperties.getMode() : PipelineMode.MIGRATION;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.client = client != null ? client : new AlfrescoRestClient(this.properties, this.objectMapper);
    }

    public void setPipelineMode(PipelineMode pipelineMode) {
        this.pipelineMode = pipelineMode != null ? pipelineMode : PipelineMode.MIGRATION;
    }

    public PipelineMode getPipelineMode() {
        return pipelineMode;
    }

    public AlfrescoRestClient getClient() {
        return client;
    }

    public AlfrescoOutputProperties getProperties() {
        return properties;
    }

    @Override
    public String getName() {
        return "AlfrescoOutputConnector";
    }

    @PostConstruct
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            log.debug("AlfrescoOutputConnector initial connection deferred: {}", e.getMessage());
        }
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting AlfrescoOutputConnector to {} (TargetParent: {}, Strategy: {})...",
                properties.getUrl(), properties.getTargetParentNodeId(), properties.getPathStrategy());
        boolean connected = client.checkConnection();
        if (connected) {
            log.info("AlfrescoOutputConnector connected successfully.");
        } else {
            log.warn("AlfrescoOutputConnector: Target parent node {} not reachable yet or credentials invalid.",
                    properties.getTargetParentNodeId());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting AlfrescoOutputConnector...");
        client.close();
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            // Guardrail: Enforce exclusive support for Migration Mode
            if (pipelineMode != PipelineMode.MIGRATION) {
                throw new IllegalStateException(
                    "AlfrescoOutputConnector only supports MIGRATION mode (opencrawling.pipeline.mode=migration). " +
                    "Current mode is " + pipelineMode + ". RAG mode (narrativization and embedding) is not supported by this connector."
                );
            }

            try {
                if (document.action() == DocumentAction.DELETE) {
                    handleDelete(document);
                    return;
                }

                handleUpsert(document);
            } catch (Exception e) {
                log.error("Failed to process document {} in AlfrescoOutputConnector: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("AlfrescoOutputConnector ingestion failed for doc: " + document.id(), e);
            }
        });
    }

    private void handleUpsert(RepositoryDocument document) throws Exception {
        String filename = resolveFilename(document);
        String mimeType = resolveMimeType(document, filename);
        String relativeFolderPath = resolveRelativeFolderPath(document);

        // 1. Spool pristine source binary to a temporary file while computing SHA-256 digest
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path spool = Files.createTempFile("oc-alfresco-migration-", ".bin");
        long contentLength = 0;
        try {
            if (document.contentStream() != null) {
                try (InputStream in = new DigestInputStream(document.contentStream(), digest);
                     OutputStream out = Files.newOutputStream(spool)) {
                    contentLength = in.transferTo(out);
                }
            }
            String sha256Hex = HexFormat.of().formatHex(digest.digest());

            // 2. Ensure target parent folder hierarchy exists
            String parentFolderId = client.ensureFolderPath(properties.getTargetParentNodeId(), relativeFolderPath);

            // 3. Map properties and aspects
            Map<String, Object> alfrescoProps = mapAlfrescoProperties(document);
            List<String> aspects = new ArrayList<>(properties.getAspects());

            boolean overwrite = "OVERWRITE".equalsIgnoreCase(properties.getConflictStrategy());
            boolean autoRename = "AUTO_RENAME".equalsIgnoreCase(properties.getConflictStrategy());

            // 4. Upload document node to Alfresco
            AlfrescoRestClient.NodeCreationResult result = client.uploadNode(
                    parentFolderId,
                    filename,
                    properties.getNodeType(),
                    alfrescoProps,
                    aspects,
                    spool,
                    mimeType,
                    overwrite,
                    autoRename
            );

            log.info("Migrated document '{}' to Alfresco node ID '{}' (length: {} bytes, SHA-256: {})",
                    document.id(), result.nodeId(), contentLength, sha256Hex);

            // 5. Sync permissions if enabled
            if (properties.isSyncPermissions()) {
                client.updatePermissions(
                        result.nodeId(),
                        document.security(),
                        document.acl(),
                        properties.getDefaultPermissionRole(),
                        properties.isInheritanceEnabled()
                );
            }

        } finally {
            try {
                Files.deleteIfExists(spool);
            } catch (Exception ignored) {}
        }
    }

    private void handleDelete(RepositoryDocument document) throws Exception {
        String filename = resolveFilename(document);
        String relativeFolderPath = resolveRelativeFolderPath(document);
        String targetParentId = client.ensureFolderPath(properties.getTargetParentNodeId(), relativeFolderPath);

        String nodeId = client.findChildNodeByName(targetParentId, filename);
        if (nodeId == null) {
            // Check if document.id() was directly a nodeId
            nodeId = document.id();
        }

        boolean permanent = "PERMANENT_DELETE".equalsIgnoreCase(properties.getTombstoneAction());
        log.info("AlfrescoOutputConnector deleting node '{}' (permanent={}) on OIS DELETE tombstone.", nodeId, permanent);
        client.deleteNode(nodeId, permanent);
    }

    private String resolveRelativeFolderPath(RepositoryDocument document) {
        StringBuilder pathBuilder = new StringBuilder();

        if (properties.getTargetPathPrefix() != null && !properties.getTargetPathPrefix().isBlank()) {
            pathBuilder.append(properties.getTargetPathPrefix().trim());
        }

        if ("HIERARCHICAL".equalsIgnoreCase(properties.getPathStrategy())) {
            String docPath = extractPathFromDocument(document);
            if (docPath != null && !docPath.isBlank()) {
                if (!pathBuilder.isEmpty() && !pathBuilder.toString().endsWith("/")) {
                    pathBuilder.append("/");
                }
                pathBuilder.append(docPath);
            }
        }

        return pathBuilder.toString();
    }

    private String extractPathFromDocument(RepositoryDocument document) {
        Map<String, List<String>> meta = document.metadata();
        if (meta != null) {
            if (meta.containsKey("path") && !meta.get("path").isEmpty()) {
                return sanitizeFolderDir(meta.get("path").get(0));
            }
            if (meta.containsKey("relative_path") && !meta.get("relative_path").isEmpty()) {
                return sanitizeFolderDir(meta.get("relative_path").get(0));
            }
            if (meta.containsKey("folder") && !meta.get("folder").isEmpty()) {
                return sanitizeFolderDir(meta.get("folder").get(0));
            }
        }

        if (document.uri() != null) {
            try {
                URI u = URI.create(document.uri());
                String p = u.getPath();
                if (p != null && p.contains("/")) {
                    int lastSlash = p.lastIndexOf('/');
                    if (lastSlash > 0) {
                        return sanitizeFolderDir(p.substring(0, lastSlash));
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String sanitizeFolderDir(String path) {
        if (path == null) return "";
        return path.replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("[\\\\:*?\"<>|]", "_");
    }

    private String resolveFilename(RepositoryDocument document) {
        Map<String, List<String>> meta = document.metadata();
        if (meta != null) {
            if (meta.containsKey("filename") && !meta.get("filename").isEmpty()) {
                return meta.get("filename").get(0);
            }
            if (meta.containsKey("name") && !meta.get("name").isEmpty()) {
                return meta.get("name").get(0);
            }
            if (meta.containsKey("title") && !meta.get("title").isEmpty()) {
                return meta.get("title").get(0);
            }
        }

        if (document.uri() != null) {
            try {
                URI u = URI.create(document.uri());
                String path = u.getPath();
                if (path != null && !path.isBlank()) {
                    String last = path.substring(path.lastIndexOf('/') + 1).trim();
                    if (!last.isBlank()) return last;
                }
            } catch (Exception ignored) {}
        }

        String id = document.id();
        if (id != null && !id.isBlank()) {
            int slash = id.lastIndexOf('/');
            if (slash >= 0 && slash < id.length() - 1) {
                return id.substring(slash + 1);
            }
            return id;
        }

        return "document-" + UUID.randomUUID();
    }

    private String resolveMimeType(RepositoryDocument document, String filename) {
        Map<String, List<String>> meta = document.metadata();
        if (meta != null) {
            if (meta.containsKey("mimeType") && !meta.get("mimeType").isEmpty()) {
                return meta.get("mimeType").get(0);
            }
            if (meta.containsKey("Content-Type") && !meta.get("Content-Type").isEmpty()) {
                return meta.get("Content-Type").get(0);
            }
            if (meta.containsKey("content-type") && !meta.get("content-type").isEmpty()) {
                return meta.get("content-type").get(0);
            }
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null && !guessed.isBlank() ? guessed : "application/octet-stream";
    }

    private Map<String, Object> mapAlfrescoProperties(RepositoryDocument document) {
        Map<String, Object> props = new HashMap<>();
        Map<String, List<String>> meta = document.metadata();
        if (meta == null || meta.isEmpty()) {
            return props;
        }

        for (Map.Entry<String, List<String>> entry : meta.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;

            String val = values.get(0);

            if ("title".equalsIgnoreCase(key) || "cm:title".equalsIgnoreCase(key)) {
                props.put("cm:title", val);
            } else if ("description".equalsIgnoreCase(key) || "cm:description".equalsIgnoreCase(key)) {
                props.put("cm:description", val);
            } else if ("author".equalsIgnoreCase(key) || "creator".equalsIgnoreCase(key) || "cm:author".equalsIgnoreCase(key)) {
                props.put("cm:author", val);
            } else if (key.contains(":")) {
                // Custom model property (e.g. custom:projectCode)
                props.put(key, val);
            }
        }

        return props;
    }
}
