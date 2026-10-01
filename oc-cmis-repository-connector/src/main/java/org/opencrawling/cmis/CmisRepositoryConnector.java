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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
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
public class CmisRepositoryConnector implements RepositoryConnector {

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

        return CmisDocumentBuilder.buildDocument(resolvedRepositoryId, docNode, contentStream, includeAcls);
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
}
