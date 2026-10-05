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
package org.opencrawling.ozone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.ozone.client.OzoneNativeStorageClient;
import org.opencrawling.ozone.client.OzoneS3GatewayStorageClient;
import org.opencrawling.ozone.client.OzoneStorageClient;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.opencrawling.ozone.model.OisMigrationDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Apache Ozone Output Connector dedicated exclusively to Migration Mode.
 * Migrates binary contents as-is directly from Claim Check storage into Apache Ozone
 * object storage and generates companion OIS JSON metadata sidecars with Zero-Trust security.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "ozone")
@EnableConfigurationProperties(OzoneOutputProperties.class)
public class OzoneOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(OzoneOutputConnector.class);

    private final OzoneOutputProperties properties;
    private final OzoneStorageClient client;
    private final ObjectMapper objectMapper;
    private PipelineMode pipelineMode;

    public OzoneOutputConnector() {
        this(new OzoneOutputProperties(), null, new PipelineProperties(), new ObjectMapper());
    }

    public OzoneOutputConnector(OzoneOutputProperties properties, PipelineProperties pipelineProperties) {
        this(properties, null, pipelineProperties, new ObjectMapper());
    }

    @Autowired
    public OzoneOutputConnector(
            OzoneOutputProperties properties,
            @Autowired(required = false) OzoneStorageClient client,
            @Autowired(required = false) PipelineProperties pipelineProperties,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new OzoneOutputProperties();
        this.pipelineMode = pipelineProperties != null ? pipelineProperties.getMode() : PipelineMode.MIGRATION;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        if (client != null) {
            this.client = client;
        } else if ("S3G".equalsIgnoreCase(this.properties.getClientType())) {
            this.client = new OzoneS3GatewayStorageClient(this.properties);
        } else {
            this.client = new OzoneNativeStorageClient(this.properties);
        }
    }

    public void setPipelineMode(PipelineMode pipelineMode) {
        this.pipelineMode = pipelineMode != null ? pipelineMode : PipelineMode.MIGRATION;
    }

    public PipelineMode getPipelineMode() {
        return pipelineMode;
    }

    public OzoneStorageClient getClient() {
        return client;
    }

    @Override
    public String getName() {
        return "OzoneOutputConnector";
    }

    @PostConstruct
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            log.debug("OzoneOutputConnector initial connection deferred: {}", e.getMessage());
        }
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting OzoneOutputConnector (Strategy: {}, Volume: {}, Bucket: {})...",
                properties.getClientType(), properties.getVolume(), properties.getBucket());
        client.connect();
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting OzoneOutputConnector...");
        client.close();
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            // Guardrail: Enforce exclusive support for Migration Mode
            if (pipelineMode != PipelineMode.MIGRATION) {
                throw new IllegalStateException(
                    "OzoneOutputConnector only supports MIGRATION mode (opencrawling.pipeline.mode=migration). " +
                    "Current mode is " + pipelineMode + ". RAG mode (narrativization and embedding) is not supported by this connector."
                );
            }

            try {
                // Ensure client is connected and target bucket initialized
                client.connect();

                String key = resolveKey(document);

                // Handle DELETE action (OIS Document Lifecycle Tombstone)
                if (document.action() == DocumentAction.DELETE) {
                    handleDelete(document, key);
                    return;
                }

                // Handle UPSERT action: migrate binary as-is and write companion OIS JSON
                handleUpsert(document, key);

            } catch (Exception e) {
                log.error("Failed to process document {} in OzoneOutputConnector: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("OzoneOutputConnector ingestion failed for doc: " + document.id(), e);
            }
        });
    }

    private void handleUpsert(RepositoryDocument document, String key) throws Exception {
        byte[] contentBytes = new byte[0];
        if (document.contentStream() != null) {
            try (InputStream is = document.contentStream()) {
                contentBytes = is.readAllBytes();
            }
        }

        String mimeType = "application/octet-stream";
        if (document.metadata() != null && document.metadata().containsKey("mimeType")) {
            List<String> mimes = document.metadata().get("mimeType");
            if (mimes != null && !mimes.isEmpty()) {
                mimeType = mimes.get(0);
            }
        }

        // Calculate SHA-256 checksum of pristine source binary
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(contentBytes);
        String sha256Hex = HexFormat.of().formatHex(hash);

        // 1. Upload content binary as-is to Apache Ozone
        URI storedUri;
        try (InputStream uploadStream = new ByteArrayInputStream(contentBytes)) {
            storedUri = client.putObject(key, uploadStream, contentBytes.length, mimeType);
        }

        // 2. Build companion OIS JSON metadata sidecar
        String lastMod = document.lastModified() != null ? document.lastModified().toString() : Instant.now().toString();
        String sourceUri = document.uri() != null ? document.uri() : "";
        String originalPath = document.metadata() != null ?
                document.metadata().getOrDefault("path", List.of(sourceUri)).get(0) : sourceUri;

        OisMigrationDocument.SourceRef sourceRef = new OisMigrationDocument.SourceRef(
                document.metadata() != null ? document.metadata().getOrDefault("source_type", List.of("repository")).get(0) : "repository",
                sourceUri,
                originalPath
        );

        OisMigrationDocument.ContentRef contentRef = new OisMigrationDocument.ContentRef(
                key,
                properties.getVolume(),
                properties.getBucket(),
                mimeType,
                contentBytes.length,
                sha256Hex
        );

        SecurityConfig security = document.security() != null ? document.security() : SecurityConfig.createPublic();
        String acl = document.acl() != null ? document.acl() : "";

        OisMigrationDocument oisDocument = new OisMigrationDocument(
                OisMigrationDocument.DEFAULT_SCHEMA,
                document.id(),
                "UPSERT",
                storedUri.toString(),
                lastMod,
                sourceRef,
                contentRef,
                document.metadata() != null ? document.metadata() : Map.of(),
                security,
                acl
        );

        // 3. Upload companion OIS JSON sidecar
        String sidecarKey = key + properties.getSidecarSuffix();
        String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(oisDocument);
        client.putText(sidecarKey, jsonPayload, "application/json");

        log.info("Migrated content as-is to Ozone: binary key='{}' ({} bytes, SHA-256: {}), OIS sidecar='{}'",
                key, contentBytes.length, sha256Hex, sidecarKey);
    }

    private void handleDelete(RepositoryDocument document, String key) throws Exception {
        String sidecarKey = key + properties.getSidecarSuffix();

        if ("ARCHIVE_TOMBSTONE".equalsIgnoreCase(properties.getTombstoneAction())) {
            String tombstoneKey = ".tombstones/" + key + properties.getSidecarSuffix();
            String lastMod = document.lastModified() != null ? document.lastModified().toString() : Instant.now().toString();
            OisMigrationDocument tombstone = OisMigrationDocument.createTombstone(document.id(), document.uri(), lastMod);
            String jsonPayload = objectMapper.writeValueAsString(tombstone);
            client.putText(tombstoneKey, jsonPayload, "application/json");
            log.info("Created OIS DELETE tombstone in Ozone: {}", tombstoneKey);
        }

        // Delete binary and metadata sidecar
        client.deleteObject(key);
        client.deleteObject(sidecarKey);
        log.info("Processed OIS DELETE action: removed Ozone keys '{}' and '{}'", key, sidecarKey);
    }

    private String resolveKey(RepositoryDocument document) {
        String filename = null;
        if (document.metadata() != null) {
            List<String> filenames = document.metadata().get("filename");
            if (filenames == null || filenames.isEmpty()) {
                filenames = document.metadata().get("fileName");
            }
            if (filenames != null && !filenames.isEmpty()) {
                filename = filenames.get(0);
            }
        }

        if (filename == null || filename.isBlank()) {
            filename = document.id();
        }

        if ("FLAT".equalsIgnoreCase(properties.getKeyStrategy())) {
            return document.id() + "_" + filename.replaceAll("[^a-zA-Z0-9.-]", "_");
        }

        // Default: HIERARCHICAL strategy preserving relative directory paths if available
        if (document.metadata() != null && document.metadata().containsKey("relativePath")) {
            String relative = document.metadata().get("relativePath").get(0);
            if (relative.startsWith("/")) relative = relative.substring(1);
            return relative;
        }

        return document.id() + "_" + filename.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
