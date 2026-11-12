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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoverableRepositoryConnector;
import org.opencrawling.core.connector.discovery.DocumentFetchingRepositoryConnector;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.TruncationReason;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * High-throughput OASIS CMIS 1.0/1.1 Repository Connector.
 * Implements a modern, lightweight, native HTTP/JSON client for the CMIS 1.1 Browser Binding,
 * leveraging Java 25 Virtual Threads (StructuredTaskScope) and Project Reactor.
 */
@Component
public class CmisRepositoryConnector implements DocumentFetchingRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(CmisRepositoryConnector.class);

    private final String endpointUrl;
    private final String repositoryIdConfig;
    private final String username;
    private final String password;
    private final CmisBindingType bindingType;
    private final CmisCrawlMode crawlMode;
    private final String rootFolderPath;
    private final String rootFolderId;
    private final boolean includeSubfolders;
    private final Set<String> excludedFolderPaths;
    private final String cmisQuery;
    private final CmisVersionsMode versionsMode;
    private final boolean includeContentStream;
    private final long maxContentSizeBytes;
    private final boolean includeAcls;
    private final boolean includeSecondaryTypes;
    private final boolean changeLogEnabled;
    private final String changeLogToken;
    private final int batchSize;
    private final int timeoutSeconds;

    private CmisClient cmisClient;
    private String resolvedRepositoryId;
    private String principalAnyone = CmisSecurityMapper.DEFAULT_ANYONE_PRINCIPAL;
    private volatile boolean connected = false;

    public CmisRepositoryConnector() {
        this("http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser",
             "", "admin", "admin", CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
             "/", "", true, Set.of("/Sites/trash", "/System"),
             "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
             true, 52428800L, true, true, false, "", 100, 30);
    }

    public CmisRepositoryConnector(
            String endpointUrl,
            String username,
            String password,
            int batchSize) {
        this(endpointUrl, "", username, password, CmisBindingType.BROWSER, CmisCrawlMode.FOLDER,
             "/", "", true, Set.of("/Sites/trash", "/System"),
             "SELECT * FROM cmis:document", CmisVersionsMode.LATEST_MAJOR,
             true, 52428800L, true, true, false, "", batchSize, 30);
    }

    @Autowired
    public CmisRepositoryConnector(
            @Value("${spring.opencrawling.connector.cmis.endpoint-url:http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser}") String endpointUrl,
            @Value("${spring.opencrawling.connector.cmis.repository-id:}") String repositoryId,
            @Value("${spring.opencrawling.connector.cmis.username:admin}") String username,
            @Value("${spring.opencrawling.connector.cmis.password:admin}") String password,
            @Value("${spring.opencrawling.connector.cmis.binding-type:browser}") String bindingTypeStr,
            @Value("${spring.opencrawling.connector.cmis.crawl-mode:folder}") String crawlModeStr,
            @Value("${spring.opencrawling.connector.cmis.root-folder-path:/}") String rootFolderPath,
            @Value("${spring.opencrawling.connector.cmis.root-folder-id:}") String rootFolderId,
            @Value("${spring.opencrawling.connector.cmis.include-subfolders:true}") boolean includeSubfolders,
            @Value("${spring.opencrawling.connector.cmis.excluded-folder-paths:/Sites/trash,/System}") String excludedFoldersConfig,
            @Value("${spring.opencrawling.connector.cmis.cmis-query:SELECT * FROM cmis:document}") String cmisQuery,
            @Value("${spring.opencrawling.connector.cmis.versions-mode:latest_major}") String versionsModeStr,
            @Value("${spring.opencrawling.connector.cmis.include-content-stream:true}") boolean includeContentStream,
            @Value("${spring.opencrawling.connector.cmis.max-content-size-bytes:52428800}") long maxContentSizeBytes,
            @Value("${spring.opencrawling.connector.cmis.include-acls:true}") boolean includeAcls,
            @Value("${spring.opencrawling.connector.cmis.include-secondary-types:true}") boolean includeSecondaryTypes,
            @Value("${spring.opencrawling.connector.cmis.change-log-enabled:false}") boolean changeLogEnabled,
            @Value("${spring.opencrawling.connector.cmis.change-log-token:}") String changeLogToken,
            @Value("${spring.opencrawling.connector.cmis.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.cmis.timeout-seconds:30}") int timeoutSeconds) {

        this.endpointUrl = endpointUrl;
        this.repositoryIdConfig = repositoryId;
        this.username = username;
        this.password = password;
        this.bindingType = "atompub".equalsIgnoreCase(bindingTypeStr) ? CmisBindingType.ATOMPUB : CmisBindingType.BROWSER;
        this.crawlMode = "query".equalsIgnoreCase(crawlModeStr) ? CmisCrawlMode.QUERY : CmisCrawlMode.FOLDER;
        this.rootFolderPath = (rootFolderPath != null && !rootFolderPath.isBlank()) ? rootFolderPath : "/";
        this.rootFolderId = rootFolderId;
        this.includeSubfolders = includeSubfolders;
        
        if (excludedFoldersConfig != null && !excludedFoldersConfig.isBlank()) {
            this.excludedFolderPaths = Arrays.stream(excludedFoldersConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        } else {
            this.excludedFolderPaths = Set.of();
        }

        this.cmisQuery = cmisQuery;
        this.versionsMode = parseVersionsMode(versionsModeStr);
        this.includeContentStream = includeContentStream;
        this.maxContentSizeBytes = maxContentSizeBytes > 0 ? maxContentSizeBytes : 52428800L;
        this.includeAcls = includeAcls;
        if (!this.includeAcls) {
            log.warn("CMIS connector has include-acls=false: crawled documents will be marked public (readable by everyone)");
        }
        this.includeSecondaryTypes = includeSecondaryTypes;
        this.changeLogEnabled = changeLogEnabled;
        this.changeLogToken = changeLogToken;
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    public CmisRepositoryConnector(
            String endpointUrl,
            String repositoryId,
            String username,
            String password,
            CmisBindingType bindingType,
            CmisCrawlMode crawlMode,
            String rootFolderPath,
            String rootFolderId,
            boolean includeSubfolders,
            Set<String> excludedFolderPaths,
            String cmisQuery,
            CmisVersionsMode versionsMode,
            boolean includeContentStream,
            long maxContentSizeBytes,
            boolean includeAcls,
            boolean includeSecondaryTypes,
            boolean changeLogEnabled,
            String changeLogToken,
            int batchSize,
            int timeoutSeconds) {

        this.endpointUrl = endpointUrl;
        this.repositoryIdConfig = repositoryId;
        this.username = username;
        this.password = password;
        this.bindingType = bindingType != null ? bindingType : CmisBindingType.BROWSER;
        this.crawlMode = crawlMode != null ? crawlMode : CmisCrawlMode.FOLDER;
        this.rootFolderPath = (rootFolderPath != null && !rootFolderPath.isBlank()) ? rootFolderPath : "/";
        this.rootFolderId = rootFolderId;
        this.includeSubfolders = includeSubfolders;
        this.excludedFolderPaths = excludedFolderPaths != null ? excludedFolderPaths : Set.of();
        this.cmisQuery = cmisQuery;
        this.versionsMode = versionsMode != null ? versionsMode : CmisVersionsMode.LATEST_MAJOR;
        this.includeContentStream = includeContentStream;
        this.maxContentSizeBytes = maxContentSizeBytes > 0 ? maxContentSizeBytes : 52428800L;
        this.includeAcls = includeAcls;
        if (!this.includeAcls) {
            log.warn("CMIS connector has include-acls=false: crawled documents will be marked public (readable by everyone)");
        }
        this.includeSecondaryTypes = includeSecondaryTypes;
        this.changeLogEnabled = changeLogEnabled;
        this.changeLogToken = changeLogToken;
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    @Override
    public String getName() {
        return "CmisRepositoryConnector";
    }

    @Override
    public String sourceInstance() {
        return endpointUrl;
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info("Connecting to CMIS endpoint at URL: {} (binding: {})", endpointUrl, bindingType);
        if (this.cmisClient == null) {
            this.cmisClient = new CmisClient(endpointUrl, username, password, null, timeoutSeconds);
        }

        if (repositoryIdConfig != null && !repositoryIdConfig.isBlank()) {
            this.resolvedRepositoryId = repositoryIdConfig;
        } else {
            this.resolvedRepositoryId = cmisClient.discoverRepositoryId();
            log.info("Discovered CMIS repository ID: {}", resolvedRepositoryId);
        }

        // Test connectivity
        Map<String, CmisClient.CmisRepositoryInfo> repos = cmisClient.getRepositories();
        CmisClient.CmisRepositoryInfo info = repos.get(resolvedRepositoryId);
        if (info != null) {
            if (info.principalAnyone() != null && !info.principalAnyone().isBlank()) {
                this.principalAnyone = info.principalAnyone();
            }
            log.info("Successfully connected to CMIS repository '{}' ({}) - vendor: {}, version: {}",
                info.repositoryName(), info.repositoryId(), info.vendorName(), info.cmisVersionSupported());
        } else {
            log.info("Successfully reached CMIS service. Active repository ID: {}", resolvedRepositoryId);
        }

        this.connected = true;
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting from CMIS repository: {}", resolvedRepositoryId);
        this.cmisClient = null;
        this.connected = false;
    }

    void setCmisClient(CmisClient cmisClient, String repositoryId) {
        this.cmisClient = cmisClient;
        this.resolvedRepositoryId = repositoryId;
        this.connected = true;
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                if (!connected || cmisClient == null) {
                    connect();
                }

                if (changeLogEnabled) {
                    log.info("Executing CMIS Change Log scan for repository: {}", resolvedRepositoryId);
                    scanChangeLog(sink);
                } else if (crawlMode == CmisCrawlMode.QUERY || (basePath != null && basePath.toLowerCase().startsWith("select "))) {
                    String query = (basePath != null && basePath.toLowerCase().startsWith("select ")) ? basePath : cmisQuery;
                    log.info("Executing CMISQL query scan for repository {}: {}", resolvedRepositoryId, query);
                    scanQuery(query, sink);
                } else {
                    String startPathOrId = (basePath != null && !basePath.isBlank() && !basePath.equals("default") && !basePath.equals("-root-"))
                            ? basePath
                            : (rootFolderId != null && !rootFolderId.isBlank() ? rootFolderId : rootFolderPath);
                    log.info("Executing CMIS hierarchical folder scan starting at: {}", startPathOrId);
                    scanFolderRecursively(startPathOrId, new HashSet<>(), sink);
                }

                synchronized (sink) {
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("CMIS scan failed: {}", e.getMessage(), e);
                synchronized (sink) {
                    sink.error(e);
                }
            }
        });
    }

    @SuppressWarnings("preview")
    private void scanFolderRecursively(String folderPathOrId, Set<String> visited, FluxSink<RepositoryDocument> sink) throws Exception {
        if (!visited.add(folderPathOrId)) {
            return;
        }

        if (isExcluded(folderPathOrId)) {
            log.debug("Skipping excluded CMIS folder: {}", folderPathOrId);
            return;
        }

        int skipCount = 0;
        boolean hasMore = true;

        while (hasMore) {
            CmisClient.CmisChildrenResult childrenResult = cmisClient.getChildren(
                resolvedRepositoryId,
                folderPathOrId,
                skipCount,
                batchSize,
                includeAcls
            );

            List<JsonNode> objects = childrenResult.objects();
            if (objects.isEmpty()) {
                break;
            }

            List<JsonNode> documents = new ArrayList<>();
            List<String> subfolderPaths = new ArrayList<>();

            for (JsonNode obj : objects) {
                String baseType = CmisDocumentBuilder.getPropertyValue(obj, "cmis:baseTypeId");
                if ("cmis:folder".equalsIgnoreCase(baseType)) {
                    if (includeSubfolders) {
                        String subPath = CmisDocumentBuilder.getPropertyValue(obj, "cmis:path");
                        if (subPath == null || subPath.isBlank()) {
                            subPath = CmisDocumentBuilder.getPropertyValue(obj, "cmis:objectId");
                        }
                        if (subPath != null && !subPath.isBlank() && !isExcluded(subPath)) {
                            subfolderPaths.add(subPath);
                        }
                    }
                } else if ("cmis:document".equalsIgnoreCase(baseType) || baseType == null) {
                    if (matchesVersionPolicy(obj)) {
                        documents.add(obj);
                    }
                }
            }

            // Process documents in batch using Virtual Threads
            if (!documents.isEmpty()) {
                processDocumentBatch(documents, sink);
            }

            // Recurse subfolders
            for (String subfolder : subfolderPaths) {
                scanFolderRecursively(subfolder, visited, sink);
            }

            hasMore = childrenResult.hasMoreItems();
            skipCount += objects.size();
        }
    }

    @SuppressWarnings("preview")
    private void scanQuery(String query, FluxSink<RepositoryDocument> sink) throws Exception {
        int skipCount = 0;
        boolean hasMore = true;
        boolean searchAllVersions = (versionsMode == CmisVersionsMode.ALL);

        while (hasMore) {
            CmisClient.CmisQueryResult queryResult = cmisClient.query(
                resolvedRepositoryId,
                query,
                searchAllVersions,
                batchSize,
                skipCount
            );

            List<JsonNode> results = queryResult.results();
            if (results.isEmpty()) {
                break;
            }

            processDocumentBatch(results, sink);

            hasMore = queryResult.hasMoreItems();
            skipCount += results.size();
        }
    }

    private void scanChangeLog(FluxSink<RepositoryDocument> sink) throws Exception {
        String token = changeLogToken;
        boolean hasMore = true;

        while (hasMore) {
            CmisClient.CmisChangesResult changes = cmisClient.getContentChanges(resolvedRepositoryId, token, batchSize);
            List<CmisClient.CmisChangeEvent> events = changes.events();
            if (events.isEmpty()) {
                break;
            }

            for (CmisClient.CmisChangeEvent event : events) {
                String objectId = event.objectId();
                if ("deleted".equalsIgnoreCase(event.changeType())) {
                    log.info("Propagating CMIS deletion tombstone for object ID: {}", objectId);
                    RepositoryDocument tombstone = CmisDocumentBuilder.buildTombstone(resolvedRepositoryId, objectId);
                    synchronized (sink) {
                        sink.next(tombstone);
                    }
                } else {
                    // created or updated
                    try {
                        JsonNode docNode = cmisClient.getObject(resolvedRepositoryId, objectId, includeAcls);
                        if (matchesVersionPolicy(docNode)) {
                            RepositoryDocument doc = buildSingleDocument(docNode);
                            synchronized (sink) {
                                sink.next(doc);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch changed CMIS object {}: {}", objectId, e.getMessage());
                    }
                }
            }

            hasMore = changes.hasMoreItems();
            token = changes.latestChangeLogToken();
        }
    }

    @SuppressWarnings("preview")
    private void processDocumentBatch(List<JsonNode> documents, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode docNode : documents) {
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        RepositoryDocument doc = buildSingleDocument(docNode);
                        synchronized (sink) {
                            sink.next(doc);
                        }
                    } catch (Exception e) {
                        String docId = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:objectId");
                        log.error("Failed to build RepositoryDocument for CMIS object {}: {}", docId, e.getMessage(), e);
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private RepositoryDocument buildSingleDocument(JsonNode docNode) {
        String objectId = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:objectId");
        InputStream contentStream = null;

        if (includeContentStream && objectId != null) {
            String lengthStr = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:contentStreamLength");
            long length = 0;
            if (lengthStr != null && !lengthStr.isBlank()) {
                try {
                    length = Long.parseLong(lengthStr);
                } catch (NumberFormatException ignored) {}
            }

            if (length <= maxContentSizeBytes) {
                try {
                    contentStream = cmisClient.getContentStream(resolvedRepositoryId, objectId);
                } catch (Exception e) {
                    log.warn("Could not download content stream for CMIS document {}: {}", objectId, e.getMessage());
                }
            } else {
                log.debug("Skipping content stream for CMIS document {} (size {} exceeds max {})", objectId, length, maxContentSizeBytes);
            }
        }

        return CmisDocumentBuilder.buildDocument(resolvedRepositoryId, docNode, contentStream, includeAcls, principalAnyone);
    }

    private boolean matchesVersionPolicy(JsonNode docNode) {
        if (versionsMode == CmisVersionsMode.ALL) {
            return true;
        }

        if (versionsMode == CmisVersionsMode.LATEST_MAJOR) {
            String isMajor = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:isLatestMajorVersion");
            if (isMajor != null) {
                return Boolean.parseBoolean(isMajor);
            }
            // If not versioned or property absent, check isLatestVersion
            String isLatest = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:isLatestVersion");
            return isLatest == null || Boolean.parseBoolean(isLatest);
        }

        if (versionsMode == CmisVersionsMode.LATEST) {
            String isLatest = CmisDocumentBuilder.getPropertyValue(docNode, "cmis:isLatestVersion");
            return isLatest == null || Boolean.parseBoolean(isLatest);
        }

        return true;
    }

    private boolean isExcluded(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String excluded : excludedFolderPaths) {
            if (path.equalsIgnoreCase(excluded) || path.toLowerCase().startsWith(excluded.toLowerCase() + "/")) {
                return true;
            }
        }
        return false;
    }

    private static CmisVersionsMode parseVersionsMode(String mode) {
        if (mode == null) return CmisVersionsMode.LATEST_MAJOR;
        return switch (mode.toLowerCase()) {
            case "all" -> CmisVersionsMode.ALL;
            case "latest" -> CmisVersionsMode.LATEST;
            default -> CmisVersionsMode.LATEST_MAJOR;
        };
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
            new ConnectorSchema.SchemaField("cmis.objectId", "STRING", "Unique CMIS object ID"),
            new ConnectorSchema.SchemaField("cmis.name", "STRING", "Document name"),
            new ConnectorSchema.SchemaField("cmis.baseTypeId", "STRING", "Base CMIS type"),
            new ConnectorSchema.SchemaField("cmis.objectTypeId", "STRING", "Object type definition ID"),
            new ConnectorSchema.SchemaField("cmis.createdBy", "STRING", "Creator username"),
            new ConnectorSchema.SchemaField("cmis.creationDate", "TIMESTAMP", "Creation timestamp"),
            new ConnectorSchema.SchemaField("cmis.lastModifiedBy", "STRING", "Last modifier username"),
            new ConnectorSchema.SchemaField("cmis.lastModificationDate", "TIMESTAMP", "Modification timestamp"),
            new ConnectorSchema.SchemaField("cmis.versionLabel", "STRING", "Document version label"),
            new ConnectorSchema.SchemaField("cmis.contentStreamLength", "LONG", "Binary size in bytes"),
            new ConnectorSchema.SchemaField("cmis.contentStreamMimeType", "STRING", "MIME type"),
            new ConnectorSchema.SchemaField("cmis.contentStreamFileName", "STRING", "Original filename"),
            new ConnectorSchema.SchemaField("cmis.path", "STRING", "Folder hierarchy path")
        ));
    }

    // ---- Discovery and dry run (#203) ----

    /** Longest query a dry run accepts. */
    static final int MAX_QUERY_LENGTH = 4000;

    private void ensureConnected() {
        if (!connected || cmisClient == null) {
            try {
                connect();
            } catch (Exception e) {
                throw new DiscoveryException("Cannot connect to the CMIS repository: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Lists the tree below a folder, breadth-first. The folder must be the configured root or below it, and not
     * excluded; excluded folders are left out of the listing, as the crawler leaves them out. ACLs are always
     * requested, whatever {@code include-acls} says, since the filter decides on them.
     */
    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        try {
            String startRef = request.start() != null ? request.start() : configuredRoot();
            DiscoveryNode startNode = visibleFolder(folderInScope(startRef), startRef, filter);
            Deque<DiscoveryNode> folders = new ArrayDeque<>();
            folders.add(startNode);
            List<DiscoveryNode> nodes = new ArrayList<>();
            long hidden = 0;
            TruncationReason truncation = null;
            while (!folders.isEmpty()) {
                DiscoveryNode folder = folders.poll();
                int skipCount = 0;
                boolean hasMore = true;
                while (hasMore) {
                    if (System.nanoTime() - deadline > 0) {
                        return new DiscoveryResult(nodes, hidden, TruncationReason.TIMEOUT);
                    }
                    CmisClient.CmisChildrenResult page = cmisClient.getChildren(
                            resolvedRepositoryId, folder.sourceId(), skipCount, batchSize, true);
                    List<DiscoveryNode> candidates = new ArrayList<>();
                    for (JsonNode child : page.objects()) {
                        DiscoveryNode node = childNode(child, folder.sourceId(), folder.depth() + 1);
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
                    hasMore = page.hasMoreItems() && !page.objects().isEmpty();
                    skipCount += page.objects().size();
                }
            }
            return new DiscoveryResult(nodes, hidden, truncation);
        } catch (IOException e) {
            throw new DiscoveryException("CMIS exploration failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("CMIS exploration was interrupted.", e);
        }
    }

    /**
     * Counts what a crawl of the scope would pick up, with the crawler's rules: for a folder, its documents (and,
     * with {@code include-subfolders}, its subfolders' documents) that match the version policy, skipping excluded
     * folders; for a query, every result, as a query crawl takes them. Without a scope, the connector's own
     * configured query or root folder.
     *
     * <p>In a folder scope every document is checked against the filter, so the count is the caller's. Subfolders
     * are walked whether or not the filter shows them, since a crawl takes their documents too; samples name no
     * folder. A query returns no ACLs, so its count covers every match ({@code ESTIMATE} unless the filter shows
     * everything) and only its samples are checked.
     */
    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        String scope = request.scope();
        if (scope == null) {
            if (changeLogEnabled) {
                throw new IllegalArgumentException("This connector crawls the change log; give a folder or a query to preview.");
            }
            scope = crawlMode == CmisCrawlMode.QUERY ? cmisQuery : null;
        }
        try {
            if (isQuery(scope)) {
                return dryRunQuery(readOnlyQuery(scope), request, filter, deadline);
            }
            String folderRef = scope != null ? scope : configuredRoot();
            return dryRunFolder(visibleFolder(folderInScope(folderRef), folderRef, filter), request, filter, deadline);
        } catch (IOException e) {
            throw new DiscoveryException("CMIS dry run failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("CMIS dry run was interrupted.", e);
        }
    }

    /**
     * The folder a crawl from {@code reference} would start at. Only for a connector that crawls a folder tree: one
     * set up to crawl a query or the change log ignores the start folder in {@link #scan(String)}.
     */
    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        if (changeLogEnabled || crawlMode == CmisCrawlMode.QUERY) {
            throw new IllegalArgumentException(NOT_A_FOLDER_CRAWL);
        }
        ensureConnected();
        String folderRef = reference == null || reference.isBlank() ? configuredRoot() : reference.strip();
        try {
            // A query string is not a folder, so it is refused as missing; scan() only ever gets the folder's id.
            return visibleFolder(folderInScope(folderRef), folderRef, filter);
        } catch (IOException e) {
            throw new DiscoveryException("CMIS folder lookup failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("CMIS folder lookup was interrupted.", e);
        }
    }

    /**
     * Fetches a document the way a folder crawl would emit it. It must be filed in at least one folder the crawl
     * walks (the configured root, or below it with {@code include-subfolders}, outside excluded folders), match the
     * version policy, and be shown by the filter. Only for a connector that crawls a folder tree.
     */
    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        if (changeLogEnabled || crawlMode == CmisCrawlMode.QUERY) {
            throw new IllegalArgumentException(NOT_A_FOLDER_CRAWL);
        }
        String id = documentId == null ? "" : documentId.strip();
        if (id.isEmpty() || id.startsWith("/") || id.length() > MAX_DOCUMENT_ID_LENGTH
                || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(documentNotFound(documentId));
        }
        ensureConnected();
        try {
            JsonNode document;
            try {
                document = cmisClient.getObject(resolvedRepositoryId, id, true);
            } catch (FileNotFoundException e) {
                return new FetchedDocument.Gone(CmisDocumentBuilder.buildTombstone(resolvedRepositoryId, id).id());
            }
            String baseType = CmisDocumentBuilder.getPropertyValue(document, "cmis:baseTypeId");
            if (!"cmis:document".equalsIgnoreCase(baseType) || !id.equals(objectId(document))) {
                throw new IllegalArgumentException(documentNotFound(id));
            }
            String parentId = crawledParent(id);
            DiscoveryNode node = parentId == null ? null : documentNode(document, parentId, 1);
            if (node == null || !VisibilityFilter.decide(filter, List.of(node))[0]) {
                throw new IllegalArgumentException(documentNotFound(id));
            }
            return new FetchedDocument.Found(node, buildSingleDocument(document));
        } catch (IOException e) {
            throw new DiscoveryException("CMIS document lookup failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("CMIS document lookup was interrupted.", e);
        }
    }

    static final int MAX_DOCUMENT_ID_LENGTH = 512;

    /** The id of a folder the document is filed in that a crawl walks, or {@code null} when there is none. */
    private String crawledParent(String documentId) throws IOException, InterruptedException {
        String rootPath = configuredRootPath();
        String root = rootPath.endsWith("/") && rootPath.length() > 1 ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        for (JsonNode parent : cmisClient.getObjectParents(resolvedRepositoryId, documentId)) {
            String path = CmisDocumentBuilder.getPropertyValue(parent, "cmis:path");
            if (path != null && isWithin(path, rootPath) && !isExcluded(path)
                    && (includeSubfolders || path.equalsIgnoreCase(root))) {
                return objectId(parent);
            }
        }
        return null;
    }

    private static String documentNotFound(String reference) {
        return "No CMIS document '" + reference + "' in this connector's scope.";
    }

    static final String NOT_A_FOLDER_CRAWL =
            "This connector crawls a query or the change log, not a folder tree, so a crawl cannot start from a folder.";

    /** The starting folder as a node, refused as not found when the filter hides it. */
    private DiscoveryNode visibleFolder(JsonNode folder, String reference, VisibilityFilter filter) {
        DiscoveryNode node = folderNode(folder, null, 0);
        if (node == null) {
            throw new DiscoveryException("The CMIS folder '" + reference + "' has no object id.", null);
        }
        if (!VisibilityFilter.decide(filter, List.of(node))[0]) {
            throw new IllegalArgumentException(notFound(reference));
        }
        return node;
    }

    private static String notFound(String reference) {
        return "No CMIS folder '" + reference + "' in this connector's scope.";
    }

    private DryRunResult dryRunFolder(DiscoveryNode start, DryRunRequest request, VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        Deque<String> folders = new ArrayDeque<>();
        folders.add(start.sourceId());
        Set<String> visited = new HashSet<>();
        List<DiscoveryNode> samples = new ArrayList<>();
        long scanned = 0;
        long count = 0;
        long hidden = 0;
        while (!folders.isEmpty()) {
            String folderId = folders.poll();
            if (!visited.add(folderId)) {
                continue;
            }
            int skipCount = 0;
            boolean hasMore = true;
            while (hasMore) {
                if (System.nanoTime() - deadline > 0) {
                    return new DryRunResult(count, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.TIMEOUT);
                }
                CmisClient.CmisChildrenResult page = cmisClient.getChildren(resolvedRepositoryId, folderId, skipCount, batchSize, true);
                List<DiscoveryNode> documents = new ArrayList<>();
                for (JsonNode child : page.objects()) {
                    DiscoveryNode node = childNode(child, null, 1);
                    if (node == null) {
                        continue;
                    }
                    if (node.type() == DiscoveryNode.NodeType.FOLDER) {
                        if (includeSubfolders) {
                            folders.add(node.sourceId());
                        }
                    } else {
                        documents.add(node);
                    }
                }
                boolean[] visible = VisibilityFilter.decide(filter, documents);
                for (int i = 0; i < visible.length; i++) {
                    if (scanned >= request.maxScan()) {
                        return new DryRunResult(count, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.MAX_SCAN);
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
                hasMore = page.hasMoreItems() && !page.objects().isEmpty();
                skipCount += page.objects().size();
            }
        }
        return new DryRunResult(count, CountAccuracy.EXACT, samples, hidden, null);
    }

    /** How many query results may be read again for each sample asked for, looking for visible ones. */
    static final int SAMPLE_CANDIDATES_PER_SAMPLE = 5;

    private DryRunResult dryRunQuery(String query, DryRunRequest request, VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        boolean searchAllVersions = versionsMode == CmisVersionsMode.ALL;
        int candidateLimit = filter.showsEverything() ? request.sampleSize()
                : (int) Math.min((long) request.sampleSize() * SAMPLE_CANDIDATES_PER_SAMPLE, request.maxScan());
        List<String> candidateIds = new ArrayList<>();
        long count = 0;
        TruncationReason truncation = null;
        int skipCount = 0;
        boolean hasMore = true;
        scan:
        while (hasMore) {
            if (System.nanoTime() - deadline > 0) {
                truncation = TruncationReason.TIMEOUT;
                break;
            }
            CmisClient.CmisQueryResult page = cmisClient.query(resolvedRepositoryId, query, searchAllVersions, batchSize, skipCount);
            for (JsonNode result : page.results()) {
                if (count >= request.maxScan()) {
                    truncation = TruncationReason.MAX_SCAN;
                    break scan;
                }
                count++;
                String objectId = CmisDocumentBuilder.getPropertyValue(result, "cmis:objectId");
                if (candidateIds.size() < candidateLimit && objectId != null && !objectId.isBlank()) {
                    candidateIds.add(objectId);
                }
            }
            hasMore = page.hasMoreItems() && !page.results().isEmpty();
            skipCount += page.results().size();
        }
        // Query results carry no ACL, so each candidate sample is read again with its ACL.
        List<DiscoveryNode> candidates = new ArrayList<>();
        for (String objectId : candidateIds) {
            if (System.nanoTime() - deadline > 0) {
                truncation = truncation != null ? truncation : TruncationReason.TIMEOUT;
                break;
            }
            DiscoveryNode node = documentNode(cmisClient.getObject(resolvedRepositoryId, objectId, true), null, 1);
            if (node != null) {
                candidates.add(node);
            }
        }
        boolean[] visible = VisibilityFilter.decide(filter, candidates);
        List<DiscoveryNode> samples = new ArrayList<>();
        for (int i = 0; i < visible.length && samples.size() < request.sampleSize(); i++) {
            if (visible[i]) {
                samples.add(candidates.get(i));
            }
        }
        CountAccuracy accuracy = !filter.showsEverything() ? CountAccuracy.ESTIMATE
                : truncation == null ? CountAccuracy.EXACT : CountAccuracy.LOWER_BOUND;
        return new DryRunResult(count, accuracy, samples, filter.showsEverything() ? 0L : null, truncation);
    }

    private static boolean isQuery(String scope) {
        return scope != null && scope.regionMatches(true, 0, "select ", 0, 7);
    }

    /** CMIS QL has no statements that change data; this keeps a scope to one plain SELECT. */
    static String readOnlyQuery(String query) {
        String trimmed = query.strip();
        if (!isQuery(trimmed)) {
            throw new IllegalArgumentException("A query scope must be a CMIS SELECT statement.");
        }
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("A query scope may be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        if (trimmed.indexOf(';') >= 0) {
            throw new IllegalArgumentException("A query scope must be a single statement.");
        }
        return trimmed;
    }

    private String configuredRoot() {
        return rootFolderId != null && !rootFolderId.isBlank() ? rootFolderId : rootFolderPath;
    }

    /**
     * Reads a folder and checks that the connector may look into it: it must be the configured root or below it,
     * and not excluded. Every refusal reads the same, so that a caller cannot learn what exists outside its view.
     *
     * @throws IllegalArgumentException when it does not exist, is not a folder or is out of scope
     */
    private JsonNode folderInScope(String folderIdOrPath) throws IOException, InterruptedException {
        JsonNode folder;
        try {
            folder = cmisClient.getObject(resolvedRepositoryId, folderIdOrPath, true);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException(notFound(folderIdOrPath), e);
        }
        if (!"cmis:folder".equalsIgnoreCase(CmisDocumentBuilder.getPropertyValue(folder, "cmis:baseTypeId"))) {
            throw new IllegalArgumentException(notFound(folderIdOrPath));
        }
        String path = CmisDocumentBuilder.getPropertyValue(folder, "cmis:path");
        String rootPath = configuredRootPath();
        if (path == null || !isWithin(path, rootPath) || isExcluded(path)) {
            throw new IllegalArgumentException(notFound(folderIdOrPath));
        }
        return folder;
    }

    private String configuredRootPath() throws IOException, InterruptedException {
        if (rootFolderId != null && !rootFolderId.isBlank()) {
            String path = CmisDocumentBuilder.getPropertyValue(cmisClient.getObject(resolvedRepositoryId, rootFolderId, false), "cmis:path");
            if (path == null || path.isBlank()) {
                throw new DiscoveryException("The configured root folder '" + rootFolderId + "' has no path.", null);
            }
            return path;
        }
        return rootFolderPath;
    }

    static boolean isWithin(String path, String rootPath) {
        String root = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        if (root.isEmpty()) {
            return path.startsWith("/");
        }
        String p = path.toLowerCase(Locale.ROOT);
        String r = root.toLowerCase(Locale.ROOT);
        return p.equals(r) || p.startsWith(r + "/");
    }

    private static String objectId(JsonNode object) {
        return CmisDocumentBuilder.getPropertyValue(object, "cmis:objectId");
    }

    /** A child as a node, or {@code null} when the crawler would not take it (excluded, other version, other type). */
    private DiscoveryNode childNode(JsonNode child, String parentId, int depth) {
        String baseType = CmisDocumentBuilder.getPropertyValue(child, "cmis:baseTypeId");
        if ("cmis:folder".equalsIgnoreCase(baseType)) {
            String path = CmisDocumentBuilder.getPropertyValue(child, "cmis:path");
            return path != null && isExcluded(path) ? null : folderNode(child, parentId, depth);
        }
        if ("cmis:document".equalsIgnoreCase(baseType) || baseType == null) {
            return documentNode(child, parentId, depth);
        }
        return null;
    }

    private DiscoveryNode folderNode(JsonNode folder, String parentId, int depth) {
        String objectId = objectId(folder);
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        return new DiscoveryNode("cmis://" + resolvedRepositoryId + "/folders/" + objectId, objectId, parentId,
                nameOf(folder, objectId), CmisDocumentBuilder.getPropertyValue(folder, "cmis:path"),
                DiscoveryNode.NodeType.FOLDER, depth, null, null,
                CmisDocumentBuilder.parseTimestamp(CmisDocumentBuilder.getPropertyValue(folder, "cmis:lastModificationDate")),
                null, discoverySecurity(folder, principalAnyone));
    }

    private DiscoveryNode documentNode(JsonNode document, String parentId, int depth) {
        String objectId = objectId(document);
        if (objectId == null || objectId.isBlank() || !matchesVersionPolicy(document)) {
            return null;
        }
        Long size = null;
        String length = CmisDocumentBuilder.getPropertyValue(document, "cmis:contentStreamLength");
        if (length != null && !length.isBlank()) {
            try {
                size = Long.parseLong(length);
            } catch (NumberFormatException ignored) {
                // Unknown size.
            }
        }
        // Same id as the crawler gives the document, so a provider keyed on document ids decides on it.
        return new DiscoveryNode("cmis://" + resolvedRepositoryId + "/documents/" + objectId, objectId, parentId,
                nameOf(document, objectId), CmisDocumentBuilder.getPropertyValue(document, "cmis:path"),
                DiscoveryNode.NodeType.DOCUMENT, depth,
                CmisDocumentBuilder.getPropertyValue(document, "cmis:contentStreamMimeType"), size,
                CmisDocumentBuilder.parseTimestamp(CmisDocumentBuilder.getPropertyValue(document, "cmis:lastModificationDate")),
                null, discoverySecurity(document, principalAnyone));
    }

    private static String nameOf(JsonNode object, String fallback) {
        String name = CmisDocumentBuilder.getPropertyValue(object, "cmis:name");
        return name == null || name.isBlank() ? fallback : name;
    }

    /**
     * The object's ACL, or {@code null} when the repository returned none: discovery must not show an item whose
     * permissions it does not know.
     */
    static SecurityConfig discoverySecurity(JsonNode object) {
        return discoverySecurity(object, CmisSecurityMapper.DEFAULT_ANYONE_PRINCIPAL);
    }

    static SecurityConfig discoverySecurity(JsonNode object, String anyonePrincipal) {
        SecurityConfig security = CmisSecurityMapper.mapAcl(object.path("acl"), anyonePrincipal);
        return security.permissions().isEmpty() ? null : security;
    }
}
