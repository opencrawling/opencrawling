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
package org.opencrawling.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.s3.S3MultipartUploader;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.s3.client.S3ClientFactory;
import org.opencrawling.s3.config.S3OutputProperties;
import org.opencrawling.s3.model.OisMigrationDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * AWS S3 Output Connector dedicated exclusively to Migration Mode.
 * Migrates binary contents as-is directly from Claim Check storage into Amazon S3 or S3-compatible
 * object storage, generating companion OIS JSON metadata sidecars with Zero-Trust security.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "s3")
@EnableConfigurationProperties(S3OutputProperties.class)
public class S3OutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(S3OutputConnector.class);

    static final String INDEX_PREFIX = ".opencrawling/index/";
    static final String TOMBSTONE_PREFIX = ".tombstones/";

    private final S3OutputProperties properties;
    private final S3Client s3Client;
    private final S3ClientFactory clientFactory;
    private final ObjectMapper objectMapper;
    private PipelineMode pipelineMode;

    public S3OutputConnector() {
        this(new S3OutputProperties(), null, new PipelineProperties(), new ObjectMapper());
    }

    public S3OutputConnector(S3OutputProperties properties, PipelineProperties pipelineProperties) {
        this(properties, null, pipelineProperties, new ObjectMapper());
    }

    @Autowired
    public S3OutputConnector(
            S3OutputProperties properties,
            @Autowired(required = false) S3Client s3Client,
            @Autowired(required = false) PipelineProperties pipelineProperties,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.properties = properties != null ? properties : new S3OutputProperties();
        this.pipelineMode = pipelineProperties != null ? pipelineProperties.getMode() : PipelineMode.MIGRATION;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        if (s3Client != null) {
            this.s3Client = s3Client;
            this.clientFactory = null;
        } else {
            this.clientFactory = new S3ClientFactory(this.properties);
            this.s3Client = this.clientFactory.createS3Client();
        }
    }

    public void setPipelineMode(PipelineMode pipelineMode) {
        this.pipelineMode = pipelineMode != null ? pipelineMode : PipelineMode.MIGRATION;
    }

    public PipelineMode getPipelineMode() {
        return pipelineMode;
    }

    public S3Client getS3Client() {
        return s3Client;
    }

    public S3OutputProperties getProperties() {
        return properties;
    }

    @Override
    public String getName() {
        return "S3OutputConnector";
    }

    @PostConstruct
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            log.debug("S3OutputConnector initial connection deferred: {}", e.getMessage());
        }
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting S3OutputConnector (Bucket: {}, Region: {}, Strategy: {})...",
                properties.getBucket(), properties.getRegion(), properties.getCredentialsStrategy());
        if (clientFactory != null) {
            clientFactory.ensureBucketExists(s3Client, properties.getBucket());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting S3OutputConnector...");
        if (clientFactory != null) {
            clientFactory.close();
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            // Guardrail: Enforce exclusive support for Migration Mode
            if (pipelineMode != PipelineMode.MIGRATION) {
                throw new IllegalStateException(
                    "S3OutputConnector only supports MIGRATION mode (opencrawling.pipeline.mode=migration). " +
                    "Current mode is " + pipelineMode + ". RAG mode (narrativization and embedding) is not supported by this connector."
                );
            }

            try {
                // Handle DELETE action (OIS Document Lifecycle Tombstone)
                if (document.action() == DocumentAction.DELETE) {
                    handleDelete(document);
                    return;
                }

                // Handle UPSERT action: migrate binary as-is and write companion OIS JSON
                handleUpsert(document, resolveKey(document));

            } catch (Exception e) {
                log.error("Failed to process document {} in S3OutputConnector: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("S3OutputConnector ingestion failed for doc: " + document.id(), e);
            }
        });
    }

    private void handleUpsert(RepositoryDocument document, String key) throws Exception {
        String filename = resolveFilename(document);
        String mimeType = resolveMimeType(document, filename);

        // 1. Stream the pristine source binary to a local spool file while computing its SHA-256 digest.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path spool = Files.createTempFile("oc-s3-migration-", ".bin");
        long contentLength = 0;
        String eTag = "";
        try {
            if (document.contentStream() != null) {
                try (InputStream in = new DigestInputStream(document.contentStream(), digest);
                     OutputStream out = Files.newOutputStream(spool)) {
                    contentLength = in.transferTo(out);
                }
            }

            long multipartThreshold = properties.resolveMultipartThresholdBytes();
            if (contentLength >= multipartThreshold && multipartThreshold > 0) {
                log.info("Uploading large binary ({} bytes >= threshold {}) to S3 via S3MultipartUploader: key='{}'",
                        contentLength, multipartThreshold, key);
                S3MultipartUploader uploader = new S3MultipartUploader(
                        s3Client,
                        multipartThreshold,
                        properties.resolveMultipartPartSizeBytes(),
                        properties.getMultipartConcurrency()
                );
                uploader.uploadFile(properties.getBucket(), key, spool, mimeType);
            } else {
                PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType(mimeType)
                        .contentLength(contentLength);

                applyEnterpriseOptions(putBuilder);

                PutObjectResponse response = s3Client.putObject(putBuilder.build(), RequestBody.fromFile(spool));
                if (response != null && response.eTag() != null) {
                    eTag = response.eTag().replaceAll("^\"|\"$", "");
                }
            }
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
                properties.getBucket(),
                properties.getRegion(),
                sourceUri,
                filename,
                mimeType,
                contentLength,
                sha256Hex,
                properties.getStorageClass(),
                eTag
        );

        SecurityConfig security = document.security() != null ? document.security() : SecurityConfig.createPublic();
        String acl = document.acl() != null ? document.acl() : "";
        String s3Uri = "s3://" + properties.getBucket() + "/" + key;

        OisMigrationDocument oisDocument = new OisMigrationDocument(
                OisMigrationDocument.DEFAULT_SCHEMA,
                document.id(),
                "UPSERT",
                s3Uri,
                lastMod,
                sourceRef,
                contentRef,
                document.metadata() != null ? document.metadata() : Map.of(),
                security,
                acl
        );

        // 3. Upload companion OIS JSON sidecar directly adjacent to the binary
        String sidecarKey = key + properties.getSidecarSuffix();
        String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(oisDocument);
        PutObjectRequest.Builder sidecarBuilder = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(sidecarKey)
                .contentType("application/json")
                .contentLength((long) jsonPayload.getBytes(StandardCharsets.UTF_8).length);
        applyEnterpriseOptions(sidecarBuilder);
        s3Client.putObject(sidecarBuilder.build(), RequestBody.fromString(jsonPayload, StandardCharsets.UTF_8));

        // 4. Record document id -> key mapping in S3 index so that later DELETE tombstones can locate the object
        String indexKey = indexKey(document.id());
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(indexKey)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromString(key, StandardCharsets.UTF_8)
        );

        // 5. Enrich document metadata for downstream ledger recording
        if (document.metadata() != null) {
            try {
                document.metadata().put("checksum_sha256", List.of(sha256Hex));
                document.metadata().put("target_bucket", List.of(properties.getBucket()));
                document.metadata().put("target_key", List.of(key));
                document.metadata().put("binary_size", List.of(String.valueOf(contentLength)));
            } catch (UnsupportedOperationException e) {
                // If metadata map was unmodifiable, wrap in mutable copy
                Map<String, List<String>> mutable = new HashMap<>(document.metadata());
                mutable.put("checksum_sha256", List.of(sha256Hex));
                mutable.put("target_bucket", List.of(properties.getBucket()));
                mutable.put("target_key", List.of(key));
                mutable.put("binary_size", List.of(String.valueOf(contentLength)));
            }
        }

        log.info("Migrated content as-is to S3: binary key='{}' ({} bytes, SHA-256: {}), OIS sidecar='{}'",
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
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(tombstoneKey)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(jsonPayload, StandardCharsets.UTF_8)
            );
            log.info("Created OIS DELETE tombstone in S3: {}", tombstoneKey);
        }

        // Delete binary, metadata sidecar and key index entry
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(sidecarKey).build());
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(indexKey(document.id())).build());
        log.info("Processed OIS DELETE action: removed S3 keys '{}' and '{}'", key, sidecarKey);
    }

    private String lookupIndexedKey(String documentId) {
        try {
            ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                    GetObjectRequest.builder().bucket(properties.getBucket()).key(indexKey(documentId)).build());
            if (in == null) {
                return null;
            }
            try (in) {
                String key = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return key.isEmpty() ? null : key;
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String indexKey(String documentId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(documentId).getBytes(StandardCharsets.UTF_8));
            return INDEX_PREFIX + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String resolveKey(RepositoryDocument document) {
        String filename = resolveFilename(document);
        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix().trim().replaceAll("^/+", "") : "";
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        String strategy = properties.getKeyStrategy() != null ? properties.getKeyStrategy().toUpperCase() : "HIERARCHICAL";

        if ("FLAT".equals(strategy)) {
            return prefix + sanitizeSegment(document.id()) + "_" + sanitizeSegment(filename);
        }

        if ("DATE_PARTITIONED".equals(strategy)) {
            String datePath = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            return prefix + datePath + "/" + sanitizeSegment(document.id()) + "_" + sanitizeSegment(filename);
        }

        // Default: HIERARCHICAL strategy preserving the source directory structure when known
        String sourcePath = firstMetadataValue(document, "relativePath", "file_path", "path");
        if (sourcePath == null && document.uri() != null && document.uri().startsWith("file:")) {
            try {
                sourcePath = URI.create(document.uri()).getPath();
            } catch (Exception ignored) {
                // not a parseable file URI
            }
        }
        String hierarchical = sanitizePath(sourcePath);
        return hierarchical != null ? prefix + hierarchical : prefix + sanitizeSegment(document.id()) + "_" + sanitizeSegment(filename);
    }

    private void applyEnterpriseOptions(PutObjectRequest.Builder builder) {
        // Storage Class
        if (properties.getStorageClass() != null && !properties.getStorageClass().isBlank()) {
            try {
                builder.storageClass(StorageClass.fromValue(properties.getStorageClass().toUpperCase()));
            } catch (Exception ex) {
                log.debug("Unrecognized storage class '{}', falling back to S3 default", properties.getStorageClass());
            }
        }

        // Server-Side Encryption (SSE)
        String sse = properties.getServerSideEncryption() != null ? properties.getServerSideEncryption().toUpperCase() : "NONE";
        if ("SSE_S3".equals(sse)) {
            builder.serverSideEncryption(ServerSideEncryption.AES256);
        } else if ("SSE_KMS".equals(sse)) {
            builder.serverSideEncryption(ServerSideEncryption.AWS_KMS);
            if (properties.getKmsKeyId() != null && !properties.getKmsKeyId().isBlank()) {
                builder.ssekmsKeyId(properties.getKmsKeyId());
            }
            builder.bucketKeyEnabled(properties.isBucketKeyEnabled());
        }

        // S3 Object Lock & Compliance (WORM)
        if (properties.isObjectLockEnabled()) {
            if (properties.getObjectLockMode() != null && !properties.getObjectLockMode().isBlank()) {
                try {
                    builder.objectLockMode(ObjectLockMode.fromValue(properties.getObjectLockMode().toUpperCase()));
                    Instant retainUntil = Instant.now().plus(properties.getObjectLockRetentionDays(), ChronoUnit.DAYS);
                    builder.objectLockRetainUntilDate(retainUntil);
                } catch (Exception ex) {
                    log.warn("Invalid Object Lock Mode '{}': {}", properties.getObjectLockMode(), ex.getMessage());
                }
            }
            if (properties.isObjectLockLegalHold()) {
                builder.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON);
            }
        }
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
}
