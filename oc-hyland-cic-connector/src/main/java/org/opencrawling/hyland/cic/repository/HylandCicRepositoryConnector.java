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
package org.opencrawling.hyland.cic.repository;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.discovery.*;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.hyland.cic.client.HylandCicClient;
import org.opencrawling.hyland.cic.client.HylandCicException;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicDocument;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import org.opencrawling.hyland.cic.model.HylandCicItem;
import org.opencrawling.hyland.cic.model.HylandCicPagedResult;
import org.opencrawling.hyland.cic.security.HylandCicSecurityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Repository connector for Hyland Content Innovation Cloud (HXP) microservices.
 * Supports pre-crawl discovery, dynamic jobs, single-document fetching, and full/incremental crawls.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "hyland-cic")
@EnableConfigurationProperties(HylandCicProperties.class)
public class HylandCicRepositoryConnector implements DocumentFetchingRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(HylandCicRepositoryConnector.class);
    private static final int MAX_DOCUMENT_ID_LENGTH = 512;

    private final HylandCicProperties properties;
    private final HylandCicClient client;
    private final HylandCicSecurityMapper securityMapper;
    private volatile boolean connected = false;

    public HylandCicRepositoryConnector() {
        this(new HylandCicProperties());
    }

    public HylandCicRepositoryConnector(HylandCicProperties properties) {
        this(properties, new HylandCicClient(properties));
    }

    @Autowired
    public HylandCicRepositoryConnector(Environment environment) {
        this(HylandCicProperties.fromEnvironment(environment));
    }

    public HylandCicRepositoryConnector(HylandCicProperties properties, HylandCicClient client) {
        this.properties = properties != null ? properties : new HylandCicProperties();
        this.client = client != null ? client : new HylandCicClient(this.properties);
        this.securityMapper = new HylandCicSecurityMapper(this.properties.getTenantId());
    }

    public HylandCicProperties getProperties() {
        return properties;
    }

    public HylandCicClient getClient() {
        return client;
    }

    @Override
    public String getName() {
        return "HylandCicRepositoryConnector";
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info("Connecting Hyland CIC connector to {} (tenant: '{}')...",
                properties.getBaseUrl(), properties.getTenantId());
        boolean healthy = client.checkHealth();
        if (healthy) {
            log.info("Hyland CIC connection healthy.");
        } else {
            log.warn("Hyland CIC health check returned false; verify network and service status.");
        }
        connected = true;
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting Hyland CIC connector...");
        client.close();
        connected = false;
    }

    @Override
    public boolean supportsConcurrentProcessing() {
        return true;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        List<ConnectorSchema.SchemaField> fields = List.of(
                new ConnectorSchema.SchemaField("id", "STRING", "HXP Document ID"),
                new ConnectorSchema.SchemaField("name", "STRING", "Document file name"),
                new ConnectorSchema.SchemaField("path", "STRING", "Folder hierarchy path in HXP"),
                new ConnectorSchema.SchemaField("folderId", "STRING", "Parent folder identifier"),
                new ConnectorSchema.SchemaField("contentType", "STRING", "HXP Content Type class name"),
                new ConnectorSchema.SchemaField("mimeType", "STRING", "MIME type"),
                new ConnectorSchema.SchemaField("fileSize", "LONG", "File size in bytes"),
                new ConnectorSchema.SchemaField("version", "STRING", "Document version"),
                new ConnectorSchema.SchemaField("hash", "STRING", "Content checksum / hash"),
                new ConnectorSchema.SchemaField("created", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("modified", "TIMESTAMP", "Modification timestamp")
        );
        return new ConnectorSchema(fields);
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(emitter -> Thread.ofVirtual().start(() -> {
            try {
                if (properties.getQuery() != null && !properties.getQuery().isBlank()) {
                    scanQuery(properties.getQuery(), emitter);
                } else {
                    String root = (basePath != null && !basePath.isBlank()) ? basePath : configuredRootRef();
                    scanFolder(root, emitter);
                }
                emitter.complete();
            } catch (Throwable t) {
                log.error("Hyland CIC crawl failed: {}", t.getMessage(), t);
                emitter.error(t);
            }
        }));
    }

    private void scanQuery(String query, reactor.core.publisher.FluxSink<RepositoryDocument> emitter) {
        int offset = 0;
        int batchSize = properties.getBatchSize();
        boolean hasMore = true;

        while (hasMore && !emitter.isCancelled()) {
            HylandCicPagedResult result = client.query(query, offset, batchSize);
            for (HylandCicItem item : result.items()) {
                if (emitter.isCancelled()) {
                    return;
                }
                if (item.isDocument() && matchesContentType(item.contentType())) {
                    Optional<HylandCicDocument> docOpt = client.findDocument(item.id());
                    docOpt.ifPresent(doc -> emitter.next(toRepositoryDocument(doc)));
                }
            }
            hasMore = result.hasMore() && !result.items().isEmpty();
            offset += result.items().size();
        }
    }

    private void scanFolder(String rootRef, reactor.core.publisher.FluxSink<RepositoryDocument> emitter) {
        Optional<HylandCicFolder> rootFolder = client.findFolder(rootRef);
        if (rootFolder.isEmpty()) {
            log.warn("Configured root folder '{}' not found in HXP.", rootRef);
            return;
        }

        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolder.get().id());
        Set<String> visited = new HashSet<>();

        while (!folderQueue.isEmpty() && !emitter.isCancelled()) {
            String folderId = folderQueue.poll();
            if (!visited.add(folderId)) {
                continue;
            }

            int offset = 0;
            int batchSize = properties.getBatchSize();
            boolean hasMore = true;

            while (hasMore && !emitter.isCancelled()) {
                HylandCicPagedResult page = client.getFolderItems(folderId, offset, batchSize);
                for (HylandCicItem item : page.items()) {
                    if (emitter.isCancelled()) {
                        return;
                    }
                    if (item.isFolder()) {
                        if (properties.isIncludeSubfolders() && !isPathExcluded(item.path())) {
                            folderQueue.add(item.id());
                        }
                    } else if (item.isDocument() && matchesContentType(item.contentType())) {
                        Optional<HylandCicDocument> docOpt = client.findDocument(item.id());
                        docOpt.ifPresent(doc -> emitter.next(toRepositoryDocument(doc)));
                    }
                }
                hasMore = page.hasMore() && !page.items().isEmpty();
                offset += page.items().size();
            }
        }
    }

    // --- Discovery & Pre-Crawl Exploration ---

    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        String startRef = request.start() != null ? request.start() : configuredRootRef();
        DiscoveryNode startNode = visibleFolder(folderInScope(startRef), startRef, filter);

        Deque<DiscoveryNode> folders = new ArrayDeque<>();
        folders.add(startNode);
        List<DiscoveryNode> nodes = new ArrayList<>();
        long hidden = 0;
        TruncationReason truncation = null;

        while (!folders.isEmpty()) {
            DiscoveryNode folder = folders.poll();
            int offset = 0;
            int batchSize = properties.getBatchSize();
            boolean hasMore = true;

            while (hasMore) {
                if (System.nanoTime() - deadline > 0) {
                    return new DiscoveryResult(nodes, hidden, TruncationReason.TIMEOUT);
                }

                HylandCicPagedResult page;
                try {
                    page = client.getFolderItems(folder.sourceId(), offset, batchSize);
                } catch (HylandCicException e) {
                    throw new DiscoveryException("HXP folder items retrieval failed: " + e.getMessage(), e);
                }

                List<DiscoveryNode> candidates = new ArrayList<>();
                for (HylandCicItem item : page.items()) {
                    if (isPathExcluded(item.path())) {
                        continue;
                    }
                    DiscoveryNode node = toDiscoveryNode(item, folder.sourceId(), folder.depth() + 1);
                    if (node != null && (node.type() == DiscoveryNode.NodeType.FOLDER || request.includeDocuments())) {
                        candidates.add(node);
                    }
                }

                boolean[] visible = VisibilityFilter.decide(filter, candidates);
                for (int i = 0; i < visible.length; i++) {
                    if (!visible[i]) {
                        hidden++;
                        continue;
                    }
                    DiscoveryNode node = candidates.get(i);
                    if (nodes.size() >= request.maxNodes()) {
                        return new DiscoveryResult(nodes, hidden, TruncationReason.MAX_NODES);
                    }
                    nodes.add(node);
                    if (node.type() == DiscoveryNode.NodeType.FOLDER) {
                        if (node.depth() < request.maxDepth()) {
                            folders.add(node);
                        } else {
                            truncation = TruncationReason.MAX_DEPTH;
                        }
                    }
                }

                hasMore = page.hasMore() && !page.items().isEmpty();
                offset += page.items().size();
            }
        }

        return new DiscoveryResult(nodes, hidden, truncation);
    }

    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        String scope = request.scope();
        if (scope == null) {
            scope = (properties.getQuery() != null && !properties.getQuery().isBlank())
                    ? properties.getQuery() : configuredRootRef();
        }

        if (isQueryScope(scope)) {
            return dryRunQuery(scope, request, filter, deadline);
        }

        DiscoveryNode startNode = visibleFolder(folderInScope(scope), scope, filter);
        return dryRunFolder(startNode, request, filter, deadline);
    }

    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        if (properties.getQuery() != null && !properties.getQuery().isBlank()) {
            throw new IllegalArgumentException("This connector crawls a query, not a folder tree; crawlStart is not supported.");
        }
        String ref = (reference == null || reference.isBlank()) ? configuredRootRef() : reference.strip();
        return visibleFolder(folderInScope(ref), ref, filter);
    }

    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        String id = documentId == null ? "" : documentId.strip();
        if (id.isEmpty() || id.startsWith("/") || id.length() > MAX_DOCUMENT_ID_LENGTH
                || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(documentNotFound(documentId));
        }

        Optional<HylandCicDocument> docOpt;
        try {
            docOpt = client.findDocument(id);
        } catch (HylandCicException e) {
            throw new DiscoveryException("HXP document retrieval failed: " + e.getMessage(), e);
        }

        if (docOpt.isEmpty()) {
            return new FetchedDocument.Gone("hxp://documents/" + id);
        }

        HylandCicDocument doc = docOpt.get();
        if (!matchesContentType(doc.contentType()) || isPathExcluded(doc.path())) {
            throw new IllegalArgumentException(documentNotFound(id));
        }

        // Verify scope within configured root folder
        if (!isDocumentWithinRoot(doc)) {
            throw new IllegalArgumentException(documentNotFound(id));
        }

        DiscoveryNode node = toDiscoveryNode(doc, doc.folderId(), 1);
        boolean[] visible = VisibilityFilter.decide(filter, List.of(node));
        if (!visible[0]) {
            throw new IllegalArgumentException(documentNotFound(id));
        }

        return new FetchedDocument.Found(node, toRepositoryDocument(doc));
    }

    private DryRunResult dryRunQuery(String query, DryRunRequest request, VisibilityFilter filter, long deadline) {
        Tally tally = new Tally(request);
        int offset = 0;
        int batchSize = properties.getBatchSize();
        boolean hasMore = true;

        while (hasMore) {
            if (System.nanoTime() - deadline > 0) {
                return tally.result(TruncationReason.TIMEOUT);
            }

            HylandCicPagedResult result;
            try {
                result = client.query(query, offset, batchSize);
            } catch (HylandCicException e) {
                throw new DiscoveryException("HXP query dry run failed: " + e.getMessage(), e);
            }

            List<DiscoveryNode> documents = new ArrayList<>();
            for (HylandCicItem item : result.items()) {
                if (item.isDocument() && matchesContentType(item.contentType()) && !isPathExcluded(item.path())) {
                    documents.add(toDiscoveryNode(item, null, 1));
                }
            }

            if (!tally.add(documents, filter)) {
                return tally.result(TruncationReason.MAX_SCAN);
            }

            hasMore = result.hasMore() && !result.items().isEmpty();
            offset += result.items().size();
        }

        return tally.result(null);
    }

    private DryRunResult dryRunFolder(DiscoveryNode start, DryRunRequest request, VisibilityFilter filter, long deadline) {
        Deque<String> folders = new ArrayDeque<>();
        folders.add(start.sourceId());
        Set<String> visited = new HashSet<>();
        Tally tally = new Tally(request);

        while (!folders.isEmpty()) {
            String folderId = folders.poll();
            if (!visited.add(folderId)) {
                continue;
            }

            int offset = 0;
            int batchSize = properties.getBatchSize();
            boolean hasMore = true;

            while (hasMore) {
                if (System.nanoTime() - deadline > 0) {
                    return tally.result(TruncationReason.TIMEOUT);
                }

                HylandCicPagedResult page;
                try {
                    page = client.getFolderItems(folderId, offset, batchSize);
                } catch (HylandCicException e) {
                    throw new DiscoveryException("HXP folder items dry run failed: " + e.getMessage(), e);
                }

                List<DiscoveryNode> documents = new ArrayList<>();
                for (HylandCicItem item : page.items()) {
                    if (isPathExcluded(item.path())) {
                        continue;
                    }
                    if (item.isFolder()) {
                        if (properties.isIncludeSubfolders()) {
                            folders.add(item.id());
                        }
                    } else if (item.isDocument() && matchesContentType(item.contentType())) {
                        documents.add(toDiscoveryNode(item, folderId, 1));
                    }
                }

                if (!tally.add(documents, filter)) {
                    return tally.result(TruncationReason.MAX_SCAN);
                }

                hasMore = page.hasMore() && !page.items().isEmpty();
                offset += page.items().size();
            }
        }

        return tally.result(null);
    }

    // --- Helper Methods ---

    private DiscoveryNode folderInScope(String reference) {
        Optional<HylandCicFolder> folderOpt;
        try {
            folderOpt = client.findFolder(reference);
        } catch (HylandCicException e) {
            throw new DiscoveryException("HXP folder lookup failed: " + e.getMessage(), e);
        }

        if (folderOpt.isEmpty()) {
            throw new IllegalArgumentException(folderNotFound(reference));
        }

        HylandCicFolder folder = folderOpt.get();
        if (isPathExcluded(folder.path())) {
            throw new IllegalArgumentException(folderNotFound(reference));
        }

        String rootPath = configuredRootPath();
        if (folder.path() != null && !isPathWithin(folder.path(), rootPath)) {
            throw new IllegalArgumentException(folderNotFound(reference));
        }

        SecurityConfig sec = properties.isIncludeAcls()
                ? securityMapper.map(List.of(), folder.permissions())
                : SecurityConfig.createPublic();

        return new DiscoveryNode(
                "hxp://folders/" + folder.id(),
                folder.id(),
                folder.parentId(),
                folder.name(),
                folder.path(),
                DiscoveryNode.NodeType.FOLDER,
                0,
                null,
                null,
                folder.modified(),
                null,
                sec
        );
    }

    private DiscoveryNode visibleFolder(DiscoveryNode folder, String reference, VisibilityFilter filter) {
        boolean[] visible = VisibilityFilter.decide(filter, List.of(folder));
        if (!visible[0]) {
            throw new IllegalArgumentException(folderNotFound(reference));
        }
        return folder;
    }

    private DiscoveryNode toDiscoveryNode(HylandCicItem item, String parentId, int depth) {
        DiscoveryNode.NodeType type = item.isFolder() ? DiscoveryNode.NodeType.FOLDER : DiscoveryNode.NodeType.DOCUMENT;
        String idPrefix = item.isFolder() ? "hxp://folders/" : "hxp://documents/";
        SecurityConfig sec = properties.isIncludeAcls()
                ? securityMapper.map(item.permissions(), List.of())
                : SecurityConfig.createPublic();

        return new DiscoveryNode(
                idPrefix + item.id(),
                item.id(),
                parentId != null ? parentId : item.parentId(),
                item.name(),
                item.path(),
                type,
                depth,
                item.mimeType(),
                item.fileSize(),
                item.modified(),
                null,
                sec
        );
    }

    private DiscoveryNode toDiscoveryNode(HylandCicDocument doc, String parentId, int depth) {
        SecurityConfig sec = properties.isIncludeAcls()
                ? securityMapper.map(doc.permissions(), List.of())
                : SecurityConfig.createPublic();

        return new DiscoveryNode(
                "hxp://documents/" + doc.id(),
                doc.id(),
                parentId != null ? parentId : doc.folderId(),
                doc.name(),
                doc.path(),
                DiscoveryNode.NodeType.DOCUMENT,
                depth,
                doc.mimeType(),
                doc.fileSize(),
                doc.modified(),
                null,
                sec
        );
    }

    private RepositoryDocument toRepositoryDocument(HylandCicDocument doc) {
        String id = "hxp://documents/" + doc.id();
        String uri = properties.getBaseUrl() + "/api/content/documents/" + doc.id();

        Map<String, List<String>> metadata = new HashMap<>();
        if (doc.name() != null) metadata.put("name", List.of(doc.name()));
        if (doc.path() != null) metadata.put("path", List.of(doc.path()));
        if (doc.folderId() != null) metadata.put("folderId", List.of(doc.folderId()));
        if (doc.contentType() != null) metadata.put("contentType", List.of(doc.contentType()));
        if (doc.mimeType() != null) metadata.put("mimeType", List.of(doc.mimeType()));
        if (doc.fileSize() != null) metadata.put("fileSize", List.of(String.valueOf(doc.fileSize())));
        if (doc.version() != null) metadata.put("version", List.of(doc.version()));
        if (doc.hash() != null) metadata.put("hash", List.of(doc.hash()));
        if (doc.created() != null) metadata.put("created", List.of(doc.created().toString()));
        if (doc.modified() != null) metadata.put("modified", List.of(doc.modified().toString()));

        if (doc.customFields() != null) {
            for (Map.Entry<String, Object> entry : doc.customFields().entrySet()) {
                if (entry.getValue() != null) {
                    metadata.put("custom." + entry.getKey(), List.of(String.valueOf(entry.getValue())));
                }
            }
        }

        SecurityConfig security = properties.isIncludeAcls()
                ? securityMapper.map(doc.permissions(), List.of())
                : SecurityConfig.createPublic();

        InputStream contentStream = null;
        if (properties.isIncludeContentStream() && (doc.fileSize() == null || doc.fileSize() <= properties.getMaxContentSizeBytes())) {
            try {
                contentStream = client.downloadContent(doc.id());
            } catch (Exception e) {
                log.warn("Could not download content stream for document {}: {}", doc.id(), e.getMessage());
            }
        }

        return new RepositoryDocument(
                id,
                uri,
                contentStream,
                metadata,
                "",
                security,
                doc.modified() != null ? doc.modified() : Instant.now(),
                DocumentAction.UPSERT
        );
    }

    private boolean isDocumentWithinRoot(HylandCicDocument doc) {
        if (properties.getRootFolderId() != null && !properties.getRootFolderId().isBlank()) {
            if (properties.getRootFolderId().equalsIgnoreCase(doc.folderId())) {
                return true;
            }
        }
        String rootPath = configuredRootPath();
        if ("/".equals(rootPath)) {
            return true;
        }
        return doc.path() != null && isPathWithin(doc.path(), rootPath);
    }

    private boolean isPathWithin(String path, String rootPath) {
        if (rootPath == null || "/".equals(rootPath)) {
            return true;
        }
        String normPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        String normRoot = rootPath.endsWith("/") && rootPath.length() > 1 ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        return normPath.equalsIgnoreCase(normRoot) || normPath.toLowerCase().startsWith(normRoot.toLowerCase() + "/");
    }

    private boolean isPathExcluded(String path) {
        if (path == null || properties.getExcludedFolderPaths() == null) {
            return false;
        }
        for (String excluded : properties.getExcludedFolderPaths()) {
            if (isPathWithin(path, excluded)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesContentType(String contentType) {
        if (properties.getContentTypes() == null || properties.getContentTypes().isEmpty()) {
            return true;
        }
        if (contentType == null) {
            return false;
        }
        return properties.getContentTypes().stream().anyMatch(contentType::equalsIgnoreCase);
    }

    private String configuredRootRef() {
        if (properties.getRootFolderId() != null && !properties.getRootFolderId().isBlank()) {
            return properties.getRootFolderId();
        }
        return configuredRootPath();
    }

    private String configuredRootPath() {
        String p = properties.getRootFolderPath();
        return (p != null && !p.isBlank()) ? p.strip() : "/";
    }

    private static boolean isQueryScope(String scope) {
        if (scope == null) {
            return false;
        }
        String lower = scope.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("select ") || lower.contains(" from ") || lower.contains(" where ");
    }

    private static String folderNotFound(String reference) {
        return "No HXP folder '" + reference + "' in this connector's scope.";
    }

    private static String documentNotFound(String documentId) {
        return "No HXP document '" + documentId + "' in this connector's scope.";
    }

    /**
     * Helper to tally scanned documents during dry runs against maxScan and sampleSize.
     */
    private static final class Tally {
        private final DryRunRequest request;
        private final List<DiscoveryNode> samples = new ArrayList<>();
        private long scanned;
        private long count;
        private long hidden;

        Tally(DryRunRequest request) {
            this.request = request;
        }

        boolean add(List<DiscoveryNode> documents, VisibilityFilter filter) {
            boolean[] visible = VisibilityFilter.decide(filter, documents);
            for (int i = 0; i < visible.length; i++) {
                if (scanned >= request.maxScan()) {
                    return false;
                }
                scanned++;
                if (!visible[i]) {
                    hidden++;
                    continue;
                }
                count++;
                if (samples.size() < request.sampleSize()) {
                    samples.add(documents.get(i));
                }
            }
            return true;
        }

        DryRunResult result(TruncationReason truncation) {
            return new DryRunResult(
                    count,
                    truncation == null ? CountAccuracy.EXACT : CountAccuracy.LOWER_BOUND,
                    samples,
                    hidden,
                    truncation
            );
        }
    }
}
