/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.output;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.hyland.cic.client.HylandCicClient;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Output connector for Hyland Content Innovation Cloud (HXP).
 * Enables bi-directional enterprise ingestion, archiving, and migration into HXP repositories.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "hyland-cic")
@EnableConfigurationProperties(HylandCicProperties.class)
public class HylandCicOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(HylandCicOutputConnector.class);

    private final HylandCicProperties properties;
    private final HylandCicClient client;
    private volatile String resolvedTargetFolderId;

    public HylandCicOutputConnector() {
        this(new HylandCicProperties());
    }

    public HylandCicOutputConnector(HylandCicProperties properties) {
        this(properties, new HylandCicClient(properties));
    }

    @Autowired
    public HylandCicOutputConnector(Environment environment) {
        this(HylandCicProperties.fromEnvironment(environment));
    }

    public HylandCicOutputConnector(HylandCicProperties properties, HylandCicClient client) {
        this.properties = properties != null ? properties : new HylandCicProperties();
        this.client = client != null ? client : new HylandCicClient(this.properties);
    }

    public HylandCicProperties getProperties() {
        return properties;
    }

    public HylandCicClient getClient() {
        return client;
    }

    @Override
    public String getName() {
        return "HylandCicOutputConnector";
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info("Connecting Hyland CIC output connector to {}...", properties.getBaseUrl());
        boolean healthy = client.checkHealth();
        if (!healthy) {
            log.warn("Hyland CIC output health check failed; proceeding with deferred check.");
        }
        resolveTargetFolder();
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting Hyland CIC output connector...");
        client.close();
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            if (document == null) {
                return;
            }

            if (document.action() == DocumentAction.DELETE) {
                handleDelete(document);
            } else {
                handleUpsert(document);
            }
        });
    }

    private void handleDelete(RepositoryDocument document) {
        String docId = cleanDocumentId(document.id());
        log.info("Deleting document {} from Hyland CIC...", docId);
        boolean deleted = client.deleteDocument(docId);
        if (deleted) {
            log.debug("Document {} successfully deleted in HXP.", docId);
        } else {
            log.debug("Document {} was not found or already deleted in HXP.", docId);
        }
    }

    private void handleUpsert(RepositoryDocument document) {
        String name = extractFileName(document);
        String contentType = extractContentType(document);
        String mimeType = extractMimeType(document);
        String folderId = getTargetFolderId();

        byte[] contentBytes = null;
        if (document.contentStream() != null) {
            try (InputStream is = document.contentStream()) {
                contentBytes = is.readAllBytes();
            } catch (Exception e) {
                log.warn("Failed reading binary stream for document {}: {}", document.id(), e.getMessage());
            }
        }

        Map<String, Object> customFields = new HashMap<>();
        if (document.metadata() != null) {
            for (Map.Entry<String, List<String>> entry : document.metadata().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    customFields.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        }

        log.debug("Uploading document '{}' to HXP folder {}...", name, folderId);
        client.createDocument(folderId, name, contentType, contentBytes, mimeType, customFields);
    }

    private synchronized String getTargetFolderId() {
        if (resolvedTargetFolderId != null) {
            return resolvedTargetFolderId;
        }
        resolveTargetFolder();
        return resolvedTargetFolderId != null ? resolvedTargetFolderId : "";
    }

    private synchronized void resolveTargetFolder() {
        if (properties.getTargetFolderId() != null && !properties.getTargetFolderId().isBlank()) {
            this.resolvedTargetFolderId = properties.getTargetFolderId().strip();
            return;
        }
        String path = properties.getTargetFolderPath();
        if (path != null && !path.isBlank()) {
            try {
                Optional<HylandCicFolder> folderOpt = client.findFolder(path);
                folderOpt.ifPresent(folder -> this.resolvedTargetFolderId = folder.id());
            } catch (Exception e) {
                log.warn("Could not pre-resolve target folder path '{}': {}", path, e.getMessage());
            }
        }
    }

    private String cleanDocumentId(String id) {
        if (id == null) {
            return "";
        }
        if (id.startsWith("hxp://documents/")) {
            return id.substring("hxp://documents/".length());
        }
        return id;
    }

    private String extractFileName(RepositoryDocument document) {
        if (document.metadata() != null && document.metadata().containsKey("name")) {
            List<String> names = document.metadata().get("name");
            if (names != null && !names.isEmpty()) {
                return names.get(0);
            }
        }
        String id = document.id();
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private String extractContentType(RepositoryDocument document) {
        if (document.metadata() != null && document.metadata().containsKey("contentType")) {
            List<String> types = document.metadata().get("contentType");
            if (types != null && !types.isEmpty()) {
                return types.get(0);
            }
        }
        return properties.getDefaultContentType();
    }

    private String extractMimeType(RepositoryDocument document) {
        if (document.metadata() != null && document.metadata().containsKey("mimeType")) {
            List<String> mimes = document.metadata().get("mimeType");
            if (mimes != null && !mimes.isEmpty()) {
                return mimes.get(0);
            }
        }
        return "application/octet-stream";
    }
}
