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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

                // Handle DELETE action (OIS Document Lifecycle Tombstone)
                if (document.action() == DocumentAction.DELETE) {
                    handleDelete(document);
                    return;
                }

                // Handle UPSERT action: migrate binary as-is and write companion OIS JSON
                handleUpsert(document, resolveKey(document));

            } catch (Exception e) {
                log.error("Failed to process document {} in OzoneOutputConnector: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("OzoneOutputConnector ingestion failed for doc: " + document.id(), e);
            }
        });
    }

    private void handleUpsert(RepositoryDocument document, String key) throws Exception {
        String filename = resolveFilename(document);
        String mimeType = resolveMimeType(document, filename);

        // 1. Stream the pristine source binary to a local spool file while computing its SHA-256.
        //    This keeps heap usage constant regardless of document size and gives both transports
        //    an exact content length up-front (required by Ozone RPC and S3 PutObject).
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path spool = Files.createTempFile("oc-ozone-migration-", ".bin");
        long contentLength = 0;
        URI storedUri;
        try {
            if (document.contentStream() != null) {
                try (InputStream in = new DigestInputStream(document.contentStream(), digest);
                     OutputStream out = Files.newOutputStream(spool)) {
                    contentLength = in.transferTo(out);
                }
            }
            storedUri = client.putFile(key, spool, contentLength, mimeType);
        } finally {
            Files.deleteIfExists(spool);
        }
        String sha256Hex = HexFormat.of().formatHex(digest.digest());

        // 2. Build companion OIS JSON metadata sidecar
        String lastMod = document.lastModified() != null ? document.lastModified().toString() : Instant.now().toString();
        String sourceUri = document.uri() != null ? document.uri() : "";
        String originalPath = firstMetadataValue(document, "path", "relativePath", "file_path", "cmis:path");
        if (originalPath == null) {
            originalPath = sourceUri;
        }

        OisMigrationDocument.SourceRef sourceRef = new OisMigrationDocument.SourceRef(
                defaultIfNull(firstMetadataValue(document, "source_type", "sourceType"), "repository"),
                sourceUri,
                originalPath
        );

        OisMigrationDocument.ContentRef contentRef = new OisMigrationDocument.ContentRef(
                key,
                properties.getVolume(),
                properties.getBucket(),
                sourceUri,
                filename,
                mimeType,
                contentLength,
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

        // 4. Record document id -> key mapping so that later DELETE tombstones (which carry no
        //    metadata) can locate the exact objects written for this document.
        client.putText(indexKey(document.id()), key, "text/plain");

        log.info("Migrated content as-is to Ozone: binary key='{}' ({} bytes, SHA-256: {}), OIS sidecar='{}'",
                key, contentLength, sha256Hex, sidecarKey);
    }

    private void handleDelete(RepositoryDocument document) throws Exception {
        String key = lookupIndexedKey(document.id());
        if (key == null) {
            key = resolveKey(document);
            log.debug("No key index entry found for document {}; falling back to computed key '{}'", document.id(), key);
        }
        String sidecarKey = key + properties.getSidecarSuffix();

        if ("ARCHIVE_TOMBSTONE".equalsIgnoreCase(properties.getTombstoneAction())) {
            String tombstoneKey = TOMBSTONE_PREFIX + key + properties.getSidecarSuffix();
            String lastMod = document.lastModified() != null ? document.lastModified().toString() : Instant.now().toString();
            OisMigrationDocument tombstone = OisMigrationDocument.createTombstone(document.id(), document.uri(), lastMod);
            String jsonPayload = objectMapper.writeValueAsString(tombstone);
            client.putText(tombstoneKey, jsonPayload, "application/json");
            log.info("Created OIS DELETE tombstone in Ozone: {}", tombstoneKey);
        }

        // Delete binary, metadata sidecar and key index entry
        client.deleteObject(key);
        client.deleteObject(sidecarKey);
        client.deleteObject(indexKey(document.id()));
        log.info("Processed OIS DELETE action: removed Ozone keys '{}' and '{}'", key, sidecarKey);
    }

    private String lookupIndexedKey(String documentId) {
        try (InputStream in = client.getObject(indexKey(documentId))) {
            if (in == null) {
                return null;
            }
            String key = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return key.isEmpty() ? null : key;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Key of the small index object mapping a source document id to the Ozone key of its binary.
     * The id is hashed so arbitrary source identifiers (paths, URLs, GUIDs) yield a safe key.
     */
    static String indexKey(String documentId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(documentId).getBytes(StandardCharsets.UTF_8));
            return INDEX_PREFIX + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    String resolveKey(RepositoryDocument document) {
        String filename = resolveFilename(document);
        String flatKey = sanitizeSegment(document.id()) + "_" + sanitizeSegment(filename);

        if ("FLAT".equalsIgnoreCase(properties.getKeyStrategy())) {
            return flatKey;
        }

        // Default: HIERARCHICAL strategy preserving the source directory structure when known
        String sourcePath = firstMetadataValue(document, "relativePath", "file_path");
        if (sourcePath == null && document.uri() != null && document.uri().startsWith("file:")) {
            try {
                sourcePath = URI.create(document.uri()).getPath();
            } catch (Exception ignored) {
                // not a parseable file URI, fall through to the flat key
            }
        }
        String hierarchical = sanitizePath(sourcePath);
        return hierarchical != null ? hierarchical : flatKey;
    }

    private String resolveFilename(RepositoryDocument document) {
        String filename = firstMetadataValue(document, "filename", "fileName", "file_name", "name", "cmis:name");
        return filename != null ? filename : String.valueOf(document.id());
    }

    private static String resolveMimeType(RepositoryDocument document, String filename) {
        String mimeType = firstMetadataValue(document, "mimeType", "mime_type", "contentType", "Content-Type", "cmis:contentStreamMimeType");
        if (mimeType == null && filename != null) {
            mimeType = URLConnection.guessContentTypeFromName(filename);
        }
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    private static String firstMetadataValue(RepositoryDocument document, String... keys) {
        if (document.metadata() == null) {
            return null;
        }
        for (String k : keys) {
            List<String> values = document.metadata().get(k);
            if (values != null && !values.isEmpty() && values.get(0) != null && !values.get(0).isBlank()) {
                return values.get(0);
            }
        }
        return null;
    }

    /**
     * Normalizes a source path into a safe relative Ozone key: drops empty, "." and ".." segments
     * (preventing traversal outside the bucket namespace) and normalizes Windows separators.
     *
     * @return the normalized key, or {@code null} if nothing usable remains
     */
    static String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            segments.add(segment.replaceAll("\\p{Cntrl}", "_"));
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private static String sanitizeSegment(String value) {
        return String.valueOf(value).replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private static String defaultIfNull(String value, String fallback) {
        return value != null ? value : fallback;
    }

    static final String INDEX_PREFIX = ".opencrawling/index/";
    static final String TOMBSTONE_PREFIX = ".tombstones/";
}
