/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.alfresco;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opencrawling.alfresco.AlfrescoProperties.DeletionTracking;
import org.opencrawling.alfresco.AlfrescoProperties.VersionMode;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DocumentFetchingRepositoryConnector;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.TruncationReason;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * High-performance Alfresco Content Services (ACS) Repository Connector (#425).
 *
 * <p>Supports three crawl modes:
 * <ol>
 *   <li>{@link AlfrescoCrawlMode#FOLDER}: hierarchical folder traversal with bounded Java 25 Virtual Thread
 *       parallelism, skip-unchanged incremental state, and orphan reconciliation.</li>
 *   <li>{@link AlfrescoCrawlMode#SEARCH_DELTA}: incremental Search API delta crawling anchored to
 *       {@code cm:modified:[<watermark> TO MAX]}, paired with Trashcan/Audit deletion feeds and optional periodic
 *       full scans ({@code full-scan-interval} / {@code full-scan-window}) for ACL-only changes and hard deletes.</li>
 *   <li>{@link AlfrescoCrawlMode#QUERY}: full Search API query ingestion (AFTS, CMIS SQL, or Lucene).</li>
 * </ol>
 * Integrates fail-closed Zero-Trust ACL mapping, OAuth2/OIDC/Ticket/Bearer/Basic auth, version modes
 * ({@code CURRENT}, {@code LATEST_MAJOR}, {@code ALL}), rendition selection, rich selection filters, and
 * pre-crawl discovery / on-demand fetching.
 */
@Component
public class AlfrescoRepositoryConnector implements DocumentFetchingRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoRepositoryConnector.class);

    private static final Pattern MAJOR_VERSION = Pattern.compile("^(\\d+)\\.0$");
    private static final String CRAWL_INCLUDE_BASE = "path,properties,aspectNames";
    private static final String DISCOVERY_INCLUDE = "path,permissions,properties,aspectNames";

    private final AlfrescoProperties settings;
    private final AlfrescoCrawlState.StateStore stateStore;
    private final List<Pattern> excludedNamePatterns;
    private final ObjectMapper objectMapper;

    private volatile AlfrescoClient client;

    public AlfrescoRepositoryConnector() {
        this(AlfrescoProperties.forLegacyConstructor(
                AlfrescoProperties.DEFAULT_URL, "admin", "admin", 100, AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, Set.of("Data Dictionary"),
                "TYPE:'cm:content'", "afts", true, true, 52_428_800L, Set.of(), 30));
    }

    public AlfrescoRepositoryConnector(String url, String username, String password, int batchSize) {
        this(AlfrescoProperties.forLegacyConstructor(
                url, username, password, batchSize, AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, Set.of("Data Dictionary"),
                "TYPE:'cm:content'", "afts", true, true, 52_428_800L, Set.of(), 30));
    }

    public AlfrescoRepositoryConnector(String url, String username, String password, int batchSize,
                                       String excludedFoldersConfig) {
        this(AlfrescoProperties.forLegacyConstructor(
                url, username, password, batchSize, AlfrescoCrawlMode.FOLDER,
                "/", "", "", true, parseDelimitedSet(excludedFoldersConfig, Set.of("Data Dictionary")),
                "TYPE:'cm:content'", "afts", true, true, 52_428_800L, Set.of(), 30));
    }

    public AlfrescoRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            AlfrescoCrawlMode crawlMode,
            String rootFolderPath,
            String rootFolderId,
            String siteId,
            boolean includeSubfolders,
            Set<String> excludedFolders,
            String searchQuery,
            String queryLanguage,
            boolean includeAcls,
            boolean includeContentStream,
            long maxContentSizeBytes,
            Set<String> mimeTypeFilter,
            int timeoutSeconds) {
        this(AlfrescoProperties.forLegacyConstructor(
                url, username, password, batchSize, crawlMode, rootFolderPath, rootFolderId, siteId,
                includeSubfolders, excludedFolders, searchQuery, queryLanguage,
                includeAcls, includeContentStream, maxContentSizeBytes, mimeTypeFilter, timeoutSeconds));
    }

    @Autowired
    public AlfrescoRepositoryConnector(Environment environment) {
        this(AlfrescoProperties.fromEnvironment(environment), AlfrescoCrawlState.LOCAL_FILE_STORE);
    }

    public AlfrescoRepositoryConnector(AlfrescoProperties settings) {
        this(settings, AlfrescoCrawlState.LOCAL_FILE_STORE);
    }

    AlfrescoRepositoryConnector(AlfrescoProperties settings, AlfrescoCrawlState.StateStore stateStore) {
        this.settings = settings != null ? settings : AlfrescoProperties.fromConfiguration(Map.of());
        this.stateStore = stateStore != null ? stateStore : AlfrescoCrawlState.LOCAL_FILE_STORE;
        this.excludedNamePatterns = this.settings.compiledNamePatterns();
        this.objectMapper = new ObjectMapper();
    }

    public AlfrescoProperties settings() {
        return settings;
    }

    private static Set<String> parseDelimitedSet(String input, Set<String> defaultSet) {
        if (input != null && !input.isBlank()) {
            return Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return defaultSet;
    }

    @Override
    public String getName() {
        return "AlfrescoConnector";
    }

    @Override
    public String sourceInstance() {
        return settings.coreApiUrl();
    }

    @Override
    public boolean supportsConcurrentProcessing() {
        return true;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return AlfrescoDocumentBuilder.schema();
    }

    @Override
    public synchronized void connect() throws Exception {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid Alfresco connector configuration: " + String.join(" ", problems));
        }
        log.info("Connecting to Alfresco Content Services at URL: {} (auth: {}, mode: {})",
                settings.coreApiUrl(), settings.authType(), settings.crawlMode());
        if (this.client == null) {
            this.client = new AlfrescoClient(settings);
        }
        this.client.checkConnection();
        log.info("Successfully connected to Alfresco Content Services. Session initialized.");
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting from Alfresco Content Services.");
        AlfrescoClient current = this.client;
        this.client = null;
        if (current != null) {
            current.close();
        }
    }

    void setHttpClient(HttpClient httpClient) {
        this.client = new AlfrescoClient(settings, httpClient, objectMapper);
    }

    private AlfrescoClient ensureClient() throws Exception {
        AlfrescoClient current = this.client;
        if (current == null) {
            connect();
            current = this.client;
        }
        return current;
    }

    // ------------------------------------------------------------------ Scan & Incremental Lifecycle

    private static final class CrawlSession {
        final Map<String, AlfrescoCrawlState.Entry> previousNodes;
        final Map<String, AlfrescoCrawlState.Entry> nextNodes = new ConcurrentHashMap<>();
        final Set<String> seenNodeIds = ConcurrentHashMap.newKeySet();
        final Set<String> removedNodeIds = ConcurrentHashMap.newKeySet();
        final Semaphore permits;
        final AtomicLong documents = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicInteger processedNodes = new AtomicInteger();
        final AtomicBoolean cancelled;
        final Instant now;
        final String renderSignature;

        CrawlSession(Map<String, AlfrescoCrawlState.Entry> previousNodes, int parallelism,
                     Instant now, String renderSignature, AtomicBoolean cancelled) {
            this.previousNodes = previousNodes != null ? previousNodes : Map.of();
            this.permits = new Semaphore(Math.max(1, parallelism));
            this.now = now;
            this.renderSignature = renderSignature;
            this.cancelled = cancelled != null ? cancelled : new AtomicBoolean();
        }

        boolean stopRequested(FluxSink<RepositoryDocument> sink) {
            return cancelled.get() || sink.isCancelled() || Thread.currentThread().isInterrupted();
        }

        boolean limitReached(int maxDocuments) {
            return maxDocuments > 0 && processedNodes.get() >= maxDocuments;
        }
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            sink.onCancel(() -> cancelled.set(true));
            try {
                AlfrescoClient activeClient = ensureClient();
                Instant serverNow = activeClient.serverTimeOrNow();

                boolean hasOverride = isExplicitOverride(basePath);
                boolean useState = settings.incrementalEnabled() && !hasOverride;
                AlfrescoCrawlState.State previousState = useState
                        ? stateStore.load(settings)
                        : AlfrescoCrawlState.State.empty();

                CrawlSession crawl = new CrawlSession(
                        previousState.nodes(), settings.parallelism(), serverNow, settings.renderSignature(), cancelled);

                boolean periodicFullScan = useState
                        && settings.crawlMode() == AlfrescoCrawlMode.SEARCH_DELTA
                        && previousState.watermark() != null
                        && settings.fullScanDue(
                                AlfrescoDocumentBuilder.parseTimestamp(previousState.lastFullScanAt()),
                                serverNow);

                boolean fullEnumeration = !hasOverride && (settings.crawlMode() == AlfrescoCrawlMode.FOLDER
                        || settings.crawlMode() == AlfrescoCrawlMode.QUERY
                        || previousState.watermark() == null
                        || periodicFullScan);

                if (isQueryString(basePath)) {
                    QueryInfo queryInfo = resolveQueryInfo(basePath);
                    log.info("Executing Alfresco Search REST API query scan (language: '{}'): {}",
                            queryInfo.language(), queryInfo.query());
                    scanQuery(activeClient, queryInfo.query(), queryInfo.language(), false, crawl, sink);
                } else if (settings.crawlMode() == AlfrescoCrawlMode.QUERY) {
                    QueryInfo queryInfo = resolveQueryInfo(basePath);
                    log.info("Executing Alfresco Search REST API query scan (language: '{}'): {}",
                            queryInfo.language(), queryInfo.query());
                    scanQuery(activeClient, queryInfo.query(), queryInfo.language(), false, crawl, sink);
                } else if (settings.crawlMode() == AlfrescoCrawlMode.SEARCH_DELTA) {
                    String effectiveQuery = buildDeltaBaseQuery(activeClient);
                    if (!fullEnumeration && previousState.watermark() != null && !previousState.watermark().isBlank()) {
                        String deltaQuery = "(" + effectiveQuery + ") AND cm:modified:[\""
                                + previousState.watermark() + "\" TO MAX]";
                        log.info("Executing Alfresco SEARCH_DELTA incremental scan: {}", deltaQuery);
                        scanQuery(activeClient, deltaQuery, "afts", true, crawl, sink);
                        processIncrementalDeletions(activeClient, previousState, crawl, sink);
                    } else {
                        log.info("Executing Alfresco SEARCH_DELTA {} full enumeration: {}",
                                periodicFullScan ? "periodic" : "initial", effectiveQuery);
                        scanQuery(activeClient, effectiveQuery, "afts", true, crawl, sink);
                    }
                } else {
                    scanConfiguredFolders(activeClient, basePath, hasOverride, crawl, sink);
                }

                // Orphan reconciliation after a complete, uncancelled, failure-free full enumeration
                if (useState && fullEnumeration
                        && settings.deletionTracking() != DeletionTracking.NONE
                        && !crawl.stopRequested(sink)
                        && crawl.failed.get() == 0
                        && !crawl.limitReached(settings.maxDocuments())
                        && !crawl.previousNodes.isEmpty()) {
                    reconcileVanishedNodes(crawl, sink);
                }

                // Persist state atomically only on complete, failure-free, uncancelled runs
                if (useState && !crawl.stopRequested(sink) && crawl.failed.get() == 0
                        && !crawl.limitReached(settings.maxDocuments())) {
                    Map<String, AlfrescoCrawlState.Entry> merged = new LinkedHashMap<>(crawl.previousNodes);
                    crawl.removedNodeIds.forEach(merged::remove);
                    merged.putAll(crawl.nextNodes);

                    String nextWatermark = serverNow.minusSeconds(settings.clockSkewSeconds())
                            .truncatedTo(ChronoUnit.SECONDS).toString();
                    String nextFullScanAt = fullEnumeration
                            ? serverNow.truncatedTo(ChronoUnit.SECONDS).toString()
                            : previousState.lastFullScanAt();

                    stateStore.save(settings, new AlfrescoCrawlState.State(
                            nextWatermark,
                            nextWatermark,
                            nextWatermark,
                            nextFullScanAt,
                            merged));
                }

                log.info("Alfresco scan of {} completed ({} enumeration{}): {} documents, {} unchanged, "
                                + "{} tombstones, {} skipped, {} failed.",
                        settings.coreApiUrl(),
                        fullEnumeration ? "full" : "incremental",
                        periodicFullScan ? ", periodic full scan" : "",
                        crawl.documents.get(), crawl.unchanged.get(),
                        crawl.tombstones.get(), crawl.skipped.get(), crawl.failed.get());

                synchronized (sink) {
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                }
            } catch (Exception e) {
                log.error("Alfresco scan failed: {}", e.getMessage(), e);
                synchronized (sink) {
                    if (!sink.isCancelled()) {
                        sink.error(e);
                    }
                }
            }
        });
    }

    private boolean isExplicitOverride(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return false;
        }
        String trimmed = basePath.trim();
        return !"-root-".equals(trimmed) && !"/".equals(trimmed) && !"default".equalsIgnoreCase(trimmed);
    }

    private void scanConfiguredFolders(AlfrescoClient activeClient, String basePath, boolean hasOverride,
                                       CrawlSession crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        if (hasOverride) {
            String trimmed = basePath.trim();
            if (trimmed.startsWith("/")) {
                String rel = withoutEdgeSlashes(trimmed);
                log.info("Executing Alfresco scoped folder scan starting at relativePath='{}' (recursive={})",
                        rel, settings.includeSubfolders());
                scanFolder(activeClient, "-root-", rel.isEmpty() ? null : rel, crawl, sink);
            } else {
                log.info("Executing Alfresco scoped folder scan starting at nodeId='{}' (recursive={})",
                        trimmed, settings.includeSubfolders());
                scanFolder(activeClient, trimmed, null, crawl, sink);
            }
            return;
        }

        if (!settings.rootFolderId().isBlank()) {
            log.info("Executing Alfresco hierarchical folder scan starting at nodeId='{}' (recursive={})",
                    settings.rootFolderId(), settings.includeSubfolders());
            scanFolder(activeClient, settings.rootFolderId(), null, crawl, sink);
            return;
        }

        if (!settings.siteIds().isEmpty()) {
            for (String site : settings.siteIds()) {
                if (crawl.stopRequested(sink) || crawl.limitReached(settings.maxDocuments())) {
                    break;
                }
                String rel = "Sites/" + site + "/documentLibrary";
                log.info("Executing Alfresco site scan starting at '{}' (recursive={})", rel, settings.includeSubfolders());
                scanFolder(activeClient, "-root-", rel, crawl, sink);
            }
            return;
        }

        String rootPath = settings.rootFolderPath();
        if (rootPath != null && !rootPath.isBlank() && !"-root-".equals(rootPath) && !"/".equals(rootPath)) {
            if (rootPath.startsWith("/")) {
                String rel = withoutEdgeSlashes(rootPath);
                log.info("Executing Alfresco hierarchical folder scan starting at relativePath='{}' (recursive={})",
                        rel, settings.includeSubfolders());
                scanFolder(activeClient, "-root-", rel.isEmpty() ? null : rel, crawl, sink);
            } else {
                log.info("Executing Alfresco hierarchical folder scan starting at nodeId='{}' (recursive={})",
                        rootPath, settings.includeSubfolders());
                scanFolder(activeClient, rootPath, null, crawl, sink);
            }
        } else {
            log.info("Executing Alfresco hierarchical folder scan starting at nodeId='-root-' (recursive={})",
                    settings.includeSubfolders());
            scanFolder(activeClient, "-root-", null, crawl, sink);
        }
    }

    private String buildDeltaBaseQuery(AlfrescoClient activeClient) throws IOException, InterruptedException {
        String base = settings.searchQuery();
        if (!settings.nodeTypes().isEmpty() && "TYPE:'cm:content'".equalsIgnoreCase(base.strip())) {
            base = settings.nodeTypes().stream()
                    .map(t -> "TYPE:'" + escapeAfts(t) + "'")
                    .collect(Collectors.joining(" OR ", "(", ")"));
        }
        List<String> clauses = new ArrayList<>();
        clauses.add("(" + base + ")");
        if (!settings.siteIds().isEmpty()) {
            String siteClause = settings.siteIds().stream()
                    .map(s -> "SITE:'" + escapeAfts(s) + "'")
                    .collect(Collectors.joining(" OR ", "(", ")"));
            clauses.add(siteClause);
        } else if (!settings.rootFolderId().isBlank()) {
            clauses.add("ANCESTOR:'workspace://SpacesStore/" + escapeAfts(settings.rootFolderId()) + "'");
        } else if (settings.rootFolderPath() != null && !settings.rootFolderPath().isBlank()
                && !"/".equals(settings.rootFolderPath()) && !"-root-".equals(settings.rootFolderPath())) {
            String rel = withoutEdgeSlashes(settings.rootFolderPath());
            Optional<JsonNode> root = settings.rootFolderPath().startsWith("/")
                    ? activeClient.getNode("-root-", null, rel)
                    : activeClient.getNode(settings.rootFolderPath(), null, null);
            if (root.isPresent()) {
                String id = root.get().path("id").asText("");
                if (!id.isBlank()) {
                    clauses.add("ANCESTOR:'workspace://SpacesStore/" + escapeAfts(id) + "'");
                }
            }
        }
        return String.join(" AND ", clauses);
    }

    private static String escapeAfts(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void scanQuery(AlfrescoClient activeClient, String query, String queryLanguage, boolean sortByModified,
                           CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        int skipCount = 0;
        boolean hasMore = true;

        List<String> includes = new ArrayList<>(List.of("path", "properties", "aspectNames"));
        if (settings.includeAcls()) {
            includes.add("permissions");
        }
        List<Map<String, Object>> sort = sortByModified
                ? List.of(Map.of("type", "FIELD", "field", "cm:modified", "ascending", true))
                : List.of();

        while (hasMore && !crawl.stopRequested(sink) && !crawl.limitReached(settings.maxDocuments())) {
            JsonNode root = activeClient.search(query, queryLanguage, skipCount, settings.batchSize(), includes, sort);
            JsonNode listNode = root.path("list");
            JsonNode entriesNode = listNode.path("entries");

            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                break;
            }

            List<JsonNode> fileEntries = new ArrayList<>();
            for (JsonNode entryWrapper : entriesNode) {
                JsonNode entry = entryWrapper.path("entry");
                if (entry.path("isFile").asBoolean(true)) {
                    fileEntries.add(entry);
                }
            }

            processFileEntriesInParallel(activeClient, fileEntries, crawl, sink);

            JsonNode paginationNode = listNode.path("pagination");
            hasMore = paginationNode.path("hasMoreItems").asBoolean(false);
            if (hasMore) {
                int count = paginationNode.path("count").asInt(entriesNode.size());
                if (count <= 0) {
                    break;
                }
                skipCount += count;
            }
        }
    }

    @SuppressWarnings("preview")
    private void scanFolder(AlfrescoClient activeClient, String nodeId, String relativePath,
                            CrawlSession crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        if (crawl.stopRequested(sink) || crawl.limitReached(settings.maxDocuments())) {
            return;
        }
        String include = settings.includeAcls() ? CRAWL_INCLUDE_BASE + ",permissions" : CRAWL_INCLUDE_BASE;

        try (var scope = StructuredTaskScope.open()) {
            int skipCount = 0;
            boolean hasMore = true;

            while (hasMore && !crawl.stopRequested(sink) && !crawl.limitReached(settings.maxDocuments())) {
                JsonNode responseNode;
                try {
                    responseNode = activeClient.listChildren(nodeId, relativePath, skipCount, settings.batchSize(), include);
                } catch (Exception e) {
                    crawl.failed.incrementAndGet();
                    log.error("Error fetching children page for nodeId={}, relativePath={}, skipCount={}: {}",
                            nodeId, relativePath, skipCount, e.getMessage());
                    break;
                }

                JsonNode listNode = responseNode.path("list");
                JsonNode entriesNode = listNode.path("entries");
                if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                    break;
                }

                List<JsonNode> fileEntries = new ArrayList<>();
                for (JsonNode entryWrapper : entriesNode) {
                    if (crawl.stopRequested(sink) || crawl.limitReached(settings.maxDocuments())) {
                        break;
                    }
                    JsonNode entry = entryWrapper.path("entry");
                    String childId = entry.path("id").asText();
                    String childName = entry.path("name").asText();
                    boolean isFolder = entry.path("isFolder").asBoolean(false);
                    boolean isFile = entry.path("isFile").asBoolean(false);

                    if (isFolder) {
                        String folderPath = AlfrescoDocumentBuilder.pathOf(entry);
                        if (settings.isFolderExcluded(childName, childId, folderPath)) {
                            log.info("Skipping excluded folder '{}' (id: {})", childName, childId);
                            continue;
                        }
                        if (settings.includeSubfolders()) {
                            scope.fork(ObservabilityTask.observed(() -> {
                                scanFolder(activeClient, childId, null, crawl, sink);
                                return null;
                            }));
                        } else {
                            log.debug("Skipping subfolder '{}' because includeSubfolders is false", childName);
                        }
                    } else if (isFile) {
                        fileEntries.add(entry);
                    }
                }

                if (!fileEntries.isEmpty()) {
                    processFileEntriesInParallel(activeClient, fileEntries, crawl, sink);
                }

                JsonNode paginationNode = listNode.path("pagination");
                hasMore = paginationNode.path("hasMoreItems").asBoolean(false);
                if (hasMore) {
                    int count = paginationNode.path("count").asInt(entriesNode.size());
                    if (count <= 0) {
                        break;
                    }
                    skipCount += count;
                }
            }

            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            crawl.failed.incrementAndGet();
            throw new RuntimeException("Folder scan failed for nodeId=" + nodeId
                    + (relativePath != null ? " (" + relativePath + ")" : ""), e.getCause());
        }
    }

    @SuppressWarnings("preview")
    private void processFileEntriesInParallel(AlfrescoClient activeClient, List<JsonNode> entries,
                                              CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws InterruptedException {
        if (entries.isEmpty()) {
            return;
        }
        if (entries.size() == 1 || settings.parallelism() <= 1) {
            for (JsonNode entry : entries) {
                if (crawl.stopRequested(sink) || crawl.limitReached(settings.maxDocuments())) {
                    break;
                }
                handleFileEntry(activeClient, entry, crawl, sink);
            }
            return;
        }
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode entry : entries) {
                if (crawl.stopRequested(sink) || crawl.limitReached(settings.maxDocuments())) {
                    break;
                }
                scope.fork(ObservabilityTask.observed(() -> {
                    crawl.permits.acquire();
                    try {
                        if (!crawl.stopRequested(sink) && !crawl.limitReached(settings.maxDocuments())) {
                            handleFileEntry(activeClient, entry, crawl, sink);
                        }
                    } finally {
                        crawl.permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            crawl.failed.incrementAndGet();
            log.error("Parallel Alfresco file processing error: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void handleFileEntry(AlfrescoClient activeClient, JsonNode entry,
                                 CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        String nodeId = entry.path("id").asText("");
        String name = entry.path("name").asText("");
        if (nodeId.isBlank()) {
            return;
        }
        crawl.seenNodeIds.add(nodeId);
        AlfrescoCrawlState.Entry known = crawl.previousNodes.get(nodeId);

        try {
            // 1. Check structural, path, MIME, type, aspect, date, and name selection filters
            String exclusion = exclusionReason(entry, crawl.now);
            List<String> tags = null;
            if (exclusion == null && (settings.includeTags() || settings.filtersByTags())) {
                tags = activeClient.listTags(nodeId);
                if (!settings.acceptsTags(tags)) {
                    exclusion = "tags";
                }
            }
            if (exclusion != null) {
                if (known != null) {
                    log.debug("Alfresco node {} ('{}') left crawl scope ({}); tombstoning.", nodeId, name, exclusion);
                    emitTombstones(known.ids(), crawl, sink);
                    crawl.removedNodeIds.add(nodeId);
                } else {
                    crawl.skipped.incrementAndGet();
                }
                return;
            }

            if (settings.maxDocuments() > 0 && crawl.processedNodes.incrementAndGet() > settings.maxDocuments()) {
                return;
            }

            // 2. Map security and check incremental skip BEFORE any binary download / version / comment calls
            AlfrescoSecurityMapper.SecurityResult securityResult = settings.includeAcls()
                    ? AlfrescoSecurityMapper.mapSecurity(entry.path("permissions"), settings.securityPolicy())
                    : new AlfrescoSecurityMapper.SecurityResult(SecurityConfig.createPublic(), "public", List.of(), List.of(), true);

            String modifiedAt = entry.path("modifiedAt").asText("");
            String versionLabel = AlfrescoDocumentBuilder.versionLabel(entry);
            long sizeInBytes = entry.path("content").path("sizeInBytes").asLong(0L);
            String aclFingerprint = securityResult.fingerprint();
            String fullPath = AlfrescoDocumentBuilder.pathOf(entry);

            if (known != null
                    && !modifiedAt.isEmpty() && modifiedAt.equals(known.modifiedAt())
                    && versionLabel.equals(known.versionLabel())
                    && sizeInBytes == known.sizeInBytes()
                    && aclFingerprint.equals(known.aclFingerprint())
                    && crawl.renderSignature.equals(known.renderSignature())) {
                crawl.unchanged.incrementAndGet();
                crawl.nextNodes.put(nodeId, known);
                return;
            }

            List<String> comments = settings.includeComments() ? activeClient.listComments(nodeId) : List.of();
            List<String> emittedIds = new ArrayList<>();

            // 3. Emit current, latest major, or all versions
            if (settings.versionMode() == VersionMode.ALL) {
                List<JsonNode> versions = activeClient.listVersions(nodeId);
                if (versions.isEmpty()) {
                    emitSingleNodeDocument(activeClient, entry, null, false, tags, comments, securityResult, emittedIds, crawl, sink);
                } else {
                    for (JsonNode ver : versions) {
                        if (crawl.stopRequested(sink)) {
                            return;
                        }
                        emitSingleNodeDocument(activeClient, entry, ver, true, tags, comments, securityResult, emittedIds, crawl, sink);
                    }
                }
            } else if (settings.versionMode() == VersionMode.LATEST_MAJOR) {
                List<JsonNode> versions = activeClient.listVersions(nodeId);
                JsonNode major = findLatestMajorVersion(versions);
                emitSingleNodeDocument(activeClient, entry, major, false, tags, comments, securityResult, emittedIds, crawl, sink);
            } else {
                emitSingleNodeDocument(activeClient, entry, null, false, tags, comments, securityResult, emittedIds, crawl, sink);
            }

            // 4. Tombstone any previously emitted IDs no longer produced (e.g. version-mode switch or purged version)
            if (known != null) {
                Set<String> currentSet = new HashSet<>(emittedIds);
                List<String> stale = known.ids().stream().filter(id -> !currentSet.contains(id)).toList();
                if (!stale.isEmpty()) {
                    emitTombstones(stale, crawl, sink);
                }
            }

            crawl.nextNodes.put(nodeId, new AlfrescoCrawlState.Entry(
                    modifiedAt, versionLabel, sizeInBytes, aclFingerprint, crawl.renderSignature, emittedIds, fullPath));
        } catch (Exception e) {
            crawl.failed.incrementAndGet();
            log.error("Error processing Alfresco nodeId={} (name={}): {}", nodeId, name, e.getMessage());
        }
    }

    private void emitSingleNodeDocument(
            AlfrescoClient activeClient,
            JsonNode entry,
            JsonNode versionEntry,
            boolean multiVersion,
            List<String> tags,
            List<String> comments,
            AlfrescoSecurityMapper.SecurityResult securityResult,
            List<String> emittedIds,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        String nodeId = entry.path("id").asText();
        String versionId = versionEntry != null ? versionEntry.path("id").asText("") : "";
        boolean isHistoricalVersion = versionEntry != null && !versionId.isBlank()
                && (multiVersion || !versionId.equals(AlfrescoDocumentBuilder.versionLabel(entry)));

        JsonNode contentNode = versionEntry != null && versionEntry.has("content")
                ? versionEntry.path("content")
                : entry.path("content");
        long sizeInBytes = contentNode != null && !contentNode.isMissingNode()
                ? contentNode.path("sizeInBytes").asLong(0L)
                : 0L;
        String effectiveMime = contentNode != null && !contentNode.isMissingNode()
                ? contentNode.path("mimeType").asText("")
                : "";
        String effectiveRendition = null;
        InputStream stream = null;

        if (settings.includeContentStream() && contentNode != null && !contentNode.isMissingNode()) {
            // Try preferred rendition for live content when configured
            if (!isHistoricalVersion && !settings.preferRendition().isBlank()) {
                Optional<String> renditionMime = activeClient.renditionMimeTypeIfCreated(nodeId, settings.preferRendition());
                if (renditionMime.isPresent()) {
                    try {
                        stream = activeClient.downloadContent(nodeId, null, settings.preferRendition(), settings.maxContentSizeBytes());
                        if (stream != null) {
                            effectiveRendition = settings.preferRendition();
                            if (!renditionMime.get().isBlank()) {
                                effectiveMime = renditionMime.get();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Rendition '{}' unavailable for node {}, falling back to primary content: {}",
                                settings.preferRendition(), nodeId, e.getMessage());
                    }
                }
            }

            if (stream == null) {
                if (settings.maxContentSizeBytes() > 0 && sizeInBytes > settings.maxContentSizeBytes()) {
                    log.warn("Skipping content stream for Alfresco node '{}' ({} bytes) exceeding maxContentSizeBytes ({} bytes)",
                            nodeId, sizeInBytes, settings.maxContentSizeBytes());
                } else {
                    try {
                        stream = activeClient.downloadContent(
                                nodeId,
                                isHistoricalVersion ? versionId : null,
                                null,
                                settings.maxContentSizeBytes());
                    } catch (Exception e) {
                        log.warn("Failed to download content stream for node {}: {}", nodeId, e.getMessage());
                    }
                }
            }
        }

        RepositoryDocument doc = AlfrescoDocumentBuilder.buildDocument(
                settings, entry, versionEntry, multiVersion,
                effectiveRendition, effectiveMime, sizeInBytes,
                tags, comments, securityResult, stream);

        synchronized (sink) {
            if (!sink.isCancelled()) {
                sink.next(doc);
            }
        }
        emittedIds.add(doc.id());
        crawl.documents.incrementAndGet();
    }

    private JsonNode findLatestMajorVersion(List<JsonNode> versions) {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        // Alfresco returns versions newest-first; pick first MAJOR or *.0 version
        for (JsonNode ver : versions) {
            String vType = ver.path("properties").path("cm:versionType").asText("");
            String id = ver.path("id").asText("");
            if ("MAJOR".equalsIgnoreCase(vType) || MAJOR_VERSION.matcher(id).matches()) {
                return ver;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Deletion Feeds & Reconciliation

    private void processIncrementalDeletions(AlfrescoClient activeClient, AlfrescoCrawlState.State previousState,
                                             CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        if (settings.deletionTracking() == DeletionTracking.NONE || crawl.previousNodes.isEmpty()) {
            return;
        }
        if (settings.deletionTracking() == DeletionTracking.TRASHCAN
                || settings.deletionTracking() == DeletionTracking.RECONCILE) {
            pollTrashcan(activeClient, previousState, crawl, sink);
        } else if (settings.deletionTracking() == DeletionTracking.AUDIT) {
            pollAuditEntries(activeClient, previousState, crawl, sink);
        }
    }

    private void pollTrashcan(AlfrescoClient activeClient, AlfrescoCrawlState.State previousState,
                              CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String sinceStr = previousState.trashWatermark() != null
                ? previousState.trashWatermark()
                : previousState.watermark();
        Instant cutoff = AlfrescoDocumentBuilder.parseTimestamp(sinceStr);

        int skipCount = 0;
        boolean hasMore = true;
        while (hasMore && !crawl.stopRequested(sink)) {
            Optional<JsonNode> opt = activeClient.listDeletedNodes(skipCount, settings.batchSize());
            if (opt.isEmpty()) {
                log.debug("Alfresco /deleted-nodes unavailable (403/404); skipping Trashcan deletion polling.");
                return;
            }
            JsonNode list = opt.get().path("list");
            JsonNode entries = list.path("entries");
            if (!entries.isArray() || entries.isEmpty()) {
                break;
            }
            boolean reachedCutoff = false;
            int count = 0;
            for (JsonNode wrapper : entries) {
                count++;
                JsonNode entry = wrapper.path("entry");
                Instant archivedAt = AlfrescoDocumentBuilder.parseTimestamp(entry.path("archivedAt").asText(""));
                if (cutoff != null && archivedAt != null && archivedAt.isBefore(cutoff)) {
                    reachedCutoff = true;
                    break;
                }
                String deletedId = entry.path("id").asText("");
                boolean isFolder = entry.path("isFolder").asBoolean(false);
                if (!deletedId.isBlank() && crawl.previousNodes.containsKey(deletedId)
                        && crawl.removedNodeIds.add(deletedId)) {
                    emitTombstones(crawl.previousNodes.get(deletedId).ids(), crawl, sink);
                }
                if (isFolder) {
                    String deletedFolderPath = AlfrescoDocumentBuilder.pathOf(entry);
                    if (deletedFolderPath != null && !deletedFolderPath.isBlank()) {
                        String prefix = deletedFolderPath.endsWith("/") ? deletedFolderPath : deletedFolderPath + "/";
                        for (Map.Entry<String, AlfrescoCrawlState.Entry> tracked : crawl.previousNodes.entrySet()) {
                            String trackedPath = tracked.getValue().path();
                            if (trackedPath != null && trackedPath.startsWith(prefix)
                                    && crawl.removedNodeIds.add(tracked.getKey())) {
                                emitTombstones(tracked.getValue().ids(), crawl, sink);
                            }
                        }
                    }
                }
            }
            if (reachedCutoff) {
                break;
            }
            hasMore = count > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
            skipCount += count;
        }
    }

    private void pollAuditEntries(AlfrescoClient activeClient, AlfrescoCrawlState.State previousState,
                                  CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String sinceStr = previousState.auditWatermark() != null
                ? previousState.auditWatermark()
                : previousState.watermark();
        Instant cutoff = AlfrescoDocumentBuilder.parseTimestamp(sinceStr);

        int skipCount = 0;
        boolean hasMore = true;
        Set<String> recheckNodeIds = new HashSet<>();
        while (hasMore && !crawl.stopRequested(sink)) {
            Optional<JsonNode> opt = activeClient.listAuditEntries(
                    settings.auditApplication(), null, skipCount, settings.batchSize());
            if (opt.isEmpty()) {
                log.debug("Alfresco audit application '{}' unavailable; skipping audit polling.", settings.auditApplication());
                return;
            }
            JsonNode list = opt.get().path("list");
            JsonNode entries = list.path("entries");
            if (!entries.isArray() || entries.isEmpty()) {
                break;
            }
            int count = 0;
            for (JsonNode wrapper : entries) {
                count++;
                JsonNode entry = wrapper.path("entry");
                Instant createdAt = AlfrescoDocumentBuilder.parseTimestamp(entry.path("createdAt").asText(""));
                if (cutoff != null && createdAt != null && createdAt.isBefore(cutoff)) {
                    continue;
                }
                JsonNode values = entry.path("values");
                String action = values.path("/alfresco-access/transaction/action").asText("").toLowerCase(Locale.ROOT);
                String nodeRef = values.path("/alfresco-access/transaction/node").asText("");
                String nodeId = extractUuid(nodeRef);
                if (nodeId == null || !crawl.previousNodes.containsKey(nodeId)) {
                    continue;
                }
                if (action.contains("delete")) {
                    if (crawl.removedNodeIds.add(nodeId)) {
                        emitTombstones(crawl.previousNodes.get(nodeId).ids(), crawl, sink);
                    }
                } else if (action.contains("permission") || action.contains("acl") || action.contains("inherit")) {
                    recheckNodeIds.add(nodeId);
                }
            }
            hasMore = count > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
            skipCount += count;
        }

        // Re-check nodes whose permissions changed in Audit log (ACL changes don't bump cm:modified in ACS)
        String include = settings.includeAcls() ? CRAWL_INCLUDE_BASE + ",permissions" : CRAWL_INCLUDE_BASE;
        for (String nodeId : recheckNodeIds) {
            if (crawl.stopRequested(sink) || crawl.seenNodeIds.contains(nodeId) || crawl.removedNodeIds.contains(nodeId)) {
                continue;
            }
            Optional<JsonNode> nodeOpt = activeClient.getNode(nodeId, include, null);
            if (nodeOpt.isEmpty()) {
                if (crawl.removedNodeIds.add(nodeId)) {
                    emitTombstones(crawl.previousNodes.get(nodeId).ids(), crawl, sink);
                }
            } else {
                handleFileEntry(activeClient, nodeOpt.get(), crawl, sink);
            }
        }
    }

    private static String extractUuid(String nodeRef) {
        if (nodeRef == null || nodeRef.isBlank()) {
            return null;
        }
        int slash = nodeRef.lastIndexOf('/');
        return slash >= 0 && slash < nodeRef.length() - 1 ? nodeRef.substring(slash + 1).strip() : nodeRef.strip();
    }

    private void reconcileVanishedNodes(CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        if (crawl.seenNodeIds.isEmpty()) {
            // Empty-enumeration guard (#396, #425): never mass-delete on an empty listing
            log.warn("Alfresco full enumeration of {} returned 0 documents while {} are indexed; "
                            + "no tombstones emitted for vanished documents.",
                    settings.coreApiUrl(), crawl.previousNodes.size());
            return;
        }
        for (Map.Entry<String, AlfrescoCrawlState.Entry> prev : crawl.previousNodes.entrySet()) {
            if (crawl.stopRequested(sink)) {
                return;
            }
            String nodeId = prev.getKey();
            if (!crawl.seenNodeIds.contains(nodeId) && crawl.removedNodeIds.add(nodeId)) {
                emitTombstones(prev.getValue().ids(), crawl, sink);
            }
        }
    }

    private void emitTombstones(List<String> docIds, CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        if (docIds == null) {
            return;
        }
        for (String docId : docIds) {
            if (crawl.stopRequested(sink)) {
                return;
            }
            RepositoryDocument tombstone = AlfrescoDocumentBuilder.buildTombstone(settings, docId);
            synchronized (sink) {
                if (!sink.isCancelled()) {
                    sink.next(tombstone);
                }
            }
            crawl.tombstones.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ Selection Filters

    /** Returns a short exclusion reason when {@code entry} is outside configured filters, or {@code null} if accepted. */
    private String exclusionReason(JsonNode entry, Instant now) {
        JsonNode contentNode = entry.path("content");
        if (settings.includeContentStream() && (contentNode == null || contentNode.isMissingNode())) {
            return "no-content";
        }
        String mimeType = (contentNode != null && !contentNode.isMissingNode())
                ? contentNode.path("mimeType").asText("")
                : "";
        if (!settings.matchesMimeType(mimeType)) {
            return "mime-type";
        }
        if (!settings.acceptsNodeType(entry.path("nodeType").asText(""))) {
            return "node-type";
        }
        List<String> aspects = AlfrescoDocumentBuilder.aspectNames(entry);
        if (!settings.acceptsAspects(aspects)) {
            return "aspects";
        }
        String name = entry.path("name").asText("");
        if (!name.isEmpty() && excludedNamePatterns.stream().anyMatch(p -> p.matcher(name).find())) {
            return "name-pattern";
        }
        // Check whether any ancestor folder is excluded (important for SEARCH / SEARCH_DELTA results)
        for (JsonNode ancestor : entry.path("path").path("elements")) {
            if (settings.isFolderExcluded(ancestor.path("name").asText(""), ancestor.path("id").asText(""), null)) {
                return "excluded-folder";
            }
        }
        Optional<Instant> createdAfter = settings.createdAfterInstant(now);
        if (createdAfter.isPresent()) {
            Instant created = AlfrescoDocumentBuilder.parseTimestamp(entry.path("createdAt").asText(""));
            if (created != null && created.isBefore(createdAfter.get())) {
                return "created-before";
            }
        }
        Optional<Instant> modifiedAfter = settings.modifiedAfterInstant(now);
        if (modifiedAfter.isPresent()) {
            Instant modified = AlfrescoDocumentBuilder.parseTimestamp(entry.path("modifiedAt").asText(""));
            if (modified != null && modified.isBefore(modifiedAfter.get())) {
                return "modified-before";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Query Resolution Helpers

    private boolean isQueryString(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String trimmed = path.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("select ")
                || lower.startsWith("afts:")
                || lower.startsWith("cmis:")
                || lower.startsWith("lucene:")
                || trimmed.startsWith("TYPE:")
                || trimmed.startsWith("PATH:")
                || trimmed.startsWith("ASPECT:")
                || trimmed.startsWith("TAG:")
                || trimmed.startsWith("TEXT:")
                || trimmed.startsWith("@");
    }

    record QueryInfo(String query, String language) {}

    private QueryInfo resolveQueryInfo(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank() || "-root-".equals(rawQuery) || "/".equals(rawQuery)
                || "default".equalsIgnoreCase(rawQuery.trim())) {
            return new QueryInfo(settings.searchQuery(), settings.queryLanguage());
        }
        String trimmed = rawQuery.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("select ")) {
            return new QueryInfo(trimmed, "cmis");
        }
        if (lower.startsWith("afts:")) {
            return new QueryInfo(trimmed.substring(5).trim(), "afts");
        }
        if (lower.startsWith("cmis:")) {
            return new QueryInfo(trimmed.substring(5).trim(), "cmis");
        }
        if (lower.startsWith("lucene:")) {
            return new QueryInfo(trimmed.substring(7).trim(), "lucene");
        }
        return new QueryInfo(trimmed, settings.queryLanguage() != null ? settings.queryLanguage() : "afts");
    }

    // ------------------------------------------------------------------ Discovery, Dry Run & Fetch Document (#203)

    /** Longest query a dry run accepts. */
    static final int MAX_QUERY_LENGTH = 4000;
    /** A node reference that is not a path: a node id or an alias such as {@code -root-}. */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final Set<String> QUERY_LANGUAGES = Set.of("afts", "cmis", "lucene");

    static final String NOT_A_FOLDER_CRAWL =
            "This connector crawls a query, not a folder tree, so a crawl cannot start from a folder.";

    private AlfrescoClient ensureConnectedForDiscovery() {
        try {
            return ensureClient();
        } catch (Exception e) {
            throw new DiscoveryException("Cannot connect to Alfresco: " + e.getMessage(), e);
        }
    }

    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        AlfrescoClient activeClient = ensureConnectedForDiscovery();
        try {
            String startRef = request.start() != null ? request.start() : configuredRootRef();
            DiscoveryNode startNode = visibleFolder(folderInScope(activeClient, startRef), startRef, filter);
            Deque<DiscoveryNode> folders = new ArrayDeque<>();
            folders.add(startNode);
            List<DiscoveryNode> nodes = new ArrayList<>();
            long hidden = 0;
            TruncationReason truncation = null;
            Instant now = Instant.now();

            while (!folders.isEmpty()) {
                DiscoveryNode folder = folders.poll();
                int skipCount = 0;
                boolean hasMore = true;
                while (hasMore) {
                    if (System.nanoTime() - deadline > 0) {
                        return new DiscoveryResult(nodes, hidden, TruncationReason.TIMEOUT);
                    }
                    JsonNode list = activeClient.listChildren(
                            folder.sourceId(), null, skipCount, settings.batchSize(), DISCOVERY_INCLUDE).path("list");
                    List<DiscoveryNode> candidates = new ArrayList<>();
                    int entries = 0;
                    for (JsonNode wrapper : list.path("entries")) {
                        entries++;
                        DiscoveryNode node = childNode(wrapper.path("entry"), folder.sourceId(), folder.depth() + 1, now);
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
                    hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
                    skipCount += entries;
                }
            }
            return new DiscoveryResult(nodes, hidden, truncation);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco exploration failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco exploration was interrupted.", e);
        }
    }

    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        AlfrescoClient activeClient = ensureConnectedForDiscovery();
        String scope = request.scope();
        try {
            if ((scope == null && settings.crawlMode() == AlfrescoCrawlMode.QUERY) || isQueryString(scope)) {
                QueryInfo query = readOnlyQuery(scope == null
                        ? new QueryInfo(settings.searchQuery(), settings.queryLanguage())
                        : resolveQueryInfo(scope));
                return dryRunQuery(activeClient, query, request, filter, deadline);
            }
            String folderRef = scope != null ? scope : configuredRootRef();
            return dryRunFolder(activeClient, visibleFolder(folderInScope(activeClient, folderRef), folderRef, filter),
                    request, filter, deadline);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco dry run failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco dry run was interrupted.", e);
        }
    }

    private DryRunResult dryRunFolder(AlfrescoClient activeClient, DiscoveryNode start, DryRunRequest request,
                                      VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        Deque<String> folders = new ArrayDeque<>();
        folders.add(start.sourceId());
        Set<String> visited = new HashSet<>();
        Tally tally = new Tally(request);
        Instant now = Instant.now();

        while (!folders.isEmpty()) {
            String folderId = folders.poll();
            if (!visited.add(folderId)) {
                continue;
            }
            int skipCount = 0;
            boolean hasMore = true;
            while (hasMore) {
                if (System.nanoTime() - deadline > 0) {
                    return tally.result(TruncationReason.TIMEOUT);
                }
                JsonNode list = activeClient.listChildren(
                        folderId, null, skipCount, settings.batchSize(), DISCOVERY_INCLUDE).path("list");
                List<DiscoveryNode> documents = new ArrayList<>();
                int entries = 0;
                for (JsonNode wrapper : list.path("entries")) {
                    entries++;
                    DiscoveryNode node = childNode(wrapper.path("entry"), null, 1, now);
                    if (node == null) {
                        continue;
                    }
                    if (node.type() == DiscoveryNode.NodeType.FOLDER) {
                        if (settings.includeSubfolders()) {
                            folders.add(node.sourceId());
                        }
                    } else {
                        documents.add(node);
                    }
                }
                if (!tally.add(documents, filter)) {
                    return tally.result(TruncationReason.MAX_SCAN);
                }
                hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
                skipCount += entries;
            }
        }
        return tally.result(null);
    }

    private DryRunResult dryRunQuery(AlfrescoClient activeClient, QueryInfo query, DryRunRequest request,
                                     VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        Tally tally = new Tally(request);
        int skipCount = 0;
        boolean hasMore = true;
        while (hasMore) {
            if (System.nanoTime() - deadline > 0) {
                return tally.result(TruncationReason.TIMEOUT);
            }
            JsonNode list;
            try {
                list = activeClient.search(query.query(), query.language(), skipCount, settings.batchSize(),
                        List.of("path", "permissions"), List.of()).path("list");
            } catch (AlfrescoApiException e) {
                if (e.statusCode() == 400) {
                    throw new IllegalArgumentException("Alfresco did not accept the query.");
                }
                throw new IOException("Alfresco search returned HTTP " + e.statusCode() + ".");
            }
            List<DiscoveryNode> documents = new ArrayList<>();
            int entries = 0;
            for (JsonNode wrapper : list.path("entries")) {
                entries++;
                JsonNode entry = wrapper.path("entry");
                if (entry.path("isFile").asBoolean(true)
                        && settings.matchesMimeType(entry.path("content").path("mimeType").asText(""))) {
                    DiscoveryNode node = documentNode(entry, null, 1);
                    if (node != null) {
                        documents.add(node);
                    }
                }
            }
            if (!tally.add(documents, filter)) {
                return tally.result(TruncationReason.MAX_SCAN);
            }
            hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
            skipCount += entries;
        }
        return tally.result(null);
    }

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
            return new DryRunResult(count, truncation == null ? CountAccuracy.EXACT : CountAccuracy.LOWER_BOUND,
                    samples, hidden, truncation);
        }
    }

    static QueryInfo readOnlyQuery(QueryInfo query) {
        String language = query.language() == null ? "" : query.language().toLowerCase(Locale.ROOT);
        if (!QUERY_LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("A query scope must be AFTS, CMIS or Lucene.");
        }
        if (query.query() == null || query.query().isBlank()) {
            throw new IllegalArgumentException("A query scope must not be empty.");
        }
        if (query.query().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("A query scope may be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        return new QueryInfo(query.query(), language);
    }

    private String configuredRootRef() {
        if (!settings.rootFolderId().isBlank()) {
            return settings.rootFolderId();
        }
        if (!settings.siteIds().isEmpty()) {
            return "/Sites/" + settings.siteIds().getFirst() + "/documentLibrary";
        }
        return settings.rootFolderPath() == null || settings.rootFolderPath().isBlank() ? "/" : settings.rootFolderPath();
    }

    private static String notFound(String reference) {
        return "No Alfresco folder '" + reference + "' in this connector's scope.";
    }

    private JsonNode folderInScope(AlfrescoClient activeClient, String reference) throws IOException, InterruptedException {
        JsonNode folder = discoveryNode(activeClient, reference);
        JsonNode root = discoveryNode(activeClient, configuredRootRef());
        if (folder == null || root == null || !folder.path("isFolder").asBoolean(false)) {
            throw new IllegalArgumentException(notFound(reference));
        }
        String rootId = root.path("id").asText();
        if (!rootId.equals(folder.path("id").asText())) {
            boolean belowRoot = false;
            for (JsonNode ancestor : folder.path("path").path("elements")) {
                if (belowRoot && settings.isFolderExcluded(ancestor.path("name").asText(""), ancestor.path("id").asText(""), null)) {
                    throw new IllegalArgumentException(notFound(reference));
                }
                if (rootId.equals(ancestor.path("id").asText())) {
                    belowRoot = true;
                }
            }
            if (!belowRoot || settings.isFolderExcluded(
                    folder.path("name").asText(""), folder.path("id").asText(""), AlfrescoDocumentBuilder.pathOf(folder))) {
                throw new IllegalArgumentException(notFound(reference));
            }
        }
        return folder;
    }

    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        if (settings.crawlMode() == AlfrescoCrawlMode.QUERY) {
            throw new IllegalArgumentException(NOT_A_FOLDER_CRAWL);
        }
        AlfrescoClient activeClient = ensureConnectedForDiscovery();
        String folderRef = reference == null || reference.isBlank() ? configuredRootRef() : reference.strip();
        try {
            return visibleFolder(folderInScope(activeClient, folderRef), folderRef, filter);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco folder lookup failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco folder lookup was interrupted.", e);
        }
    }

    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        if (settings.crawlMode() == AlfrescoCrawlMode.QUERY) {
            throw new IllegalArgumentException(NOT_A_FOLDER_CRAWL);
        }
        String id = documentId == null ? "" : documentId.strip();
        if (!NODE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(documentNotFound(documentId));
        }
        AlfrescoClient activeClient = ensureConnectedForDiscovery();
        try {
            Optional<JsonNode> opt = activeClient.getNode(id, DISCOVERY_INCLUDE, null);
            if (opt.isEmpty()) {
                return new FetchedDocument.Gone(id);
            }
            JsonNode entry = opt.get();
            JsonNode root = discoveryNode(activeClient, configuredRootRef());
            if (root == null || !entry.path("isFile").asBoolean(false) || !id.equals(entry.path("id").asText())
                    || !crawledFrom(entry, root.path("id").asText())
                    || exclusionReason(entry, Instant.now()) != null) {
                throw new IllegalArgumentException(documentNotFound(id));
            }
            List<String> tags = List.of();
            if (settings.includeTags() || settings.filtersByTags()) {
                tags = activeClient.listTags(id);
                if (!settings.acceptsTags(tags)) {
                    throw new IllegalArgumentException(documentNotFound(id));
                }
            }
            DiscoveryNode node = documentNode(entry, entry.path("parentId").asText(null), 1);
            if (node == null || !VisibilityFilter.decide(filter, List.of(node))[0]) {
                throw new IllegalArgumentException(documentNotFound(id));
            }
            AlfrescoSecurityMapper.SecurityResult securityResult = settings.includeAcls()
                    ? AlfrescoSecurityMapper.mapSecurity(entry.path("permissions"), settings.securityPolicy())
                    : new AlfrescoSecurityMapper.SecurityResult(SecurityConfig.createPublic(), "public", List.of(), List.of(), true);
            List<String> comments = settings.includeComments() ? activeClient.listComments(id) : List.of();

            JsonNode contentNode = entry.path("content");
            long sizeInBytes = contentNode != null && !contentNode.isMissingNode()
                    ? contentNode.path("sizeInBytes").asLong(0L) : 0L;
            String mimeType = contentNode != null && !contentNode.isMissingNode()
                    ? contentNode.path("mimeType").asText("") : "";
            InputStream stream = null;
            String effectiveRendition = null;
            if (settings.includeContentStream() && contentNode != null && !contentNode.isMissingNode()) {
                if (!settings.preferRendition().isBlank()) {
                    Optional<String> rMime = activeClient.renditionMimeTypeIfCreated(id, settings.preferRendition());
                    if (rMime.isPresent()) {
                        try {
                            stream = activeClient.downloadContent(id, null, settings.preferRendition(), settings.maxContentSizeBytes());
                            if (stream != null) {
                                effectiveRendition = settings.preferRendition();
                                if (!rMime.get().isBlank()) {
                                    mimeType = rMime.get();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (stream == null && (settings.maxContentSizeBytes() == 0 || sizeInBytes <= settings.maxContentSizeBytes())) {
                    try {
                        stream = activeClient.downloadContent(id, null, null, settings.maxContentSizeBytes());
                    } catch (Exception e) {
                        log.warn("Failed to download content stream for node {}: {}", id, e.getMessage());
                    }
                }
            }

            RepositoryDocument doc = AlfrescoDocumentBuilder.buildDocument(
                    settings, entry, null, false, effectiveRendition, mimeType, sizeInBytes,
                    tags, comments, securityResult, stream);
            return new FetchedDocument.Found(node, doc);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco document lookup failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco document lookup was interrupted.", e);
        }
    }

    private boolean crawledFrom(JsonNode entry, String rootId) {
        boolean belowRoot = false;
        String parentId = null;
        for (JsonNode ancestor : entry.path("path").path("elements")) {
            String ancestorId = ancestor.path("id").asText("");
            if (belowRoot && settings.isFolderExcluded(ancestor.path("name").asText(""), ancestorId, null)) {
                return false;
            }
            if (rootId.equals(ancestorId)) {
                belowRoot = true;
            }
            parentId = ancestorId;
        }
        return belowRoot && (settings.includeSubfolders() || rootId.equals(parentId));
    }

    private static String documentNotFound(String reference) {
        return "No Alfresco document '" + reference + "' in this connector's scope.";
    }

    private DiscoveryNode visibleFolder(JsonNode folder, String reference, VisibilityFilter filter) {
        DiscoveryNode node = folderNode(folder, null, 0);
        if (node == null || !VisibilityFilter.decide(filter, List.of(node))[0]) {
            throw new IllegalArgumentException(notFound(reference));
        }
        return node;
    }

    /** The path without its leading and trailing slashes, in linear time (no regex over caller input). */
    static String withoutEdgeSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    private JsonNode discoveryNode(AlfrescoClient activeClient, String reference) throws IOException, InterruptedException {
        String ref = reference == null ? "/" : reference.strip();
        if (ref.startsWith("/")) {
            String relative = withoutEdgeSlashes(ref);
            return activeClient.getNode("-root-", "path,permissions", relative.isEmpty() ? null : relative).orElse(null);
        }
        if (NODE_ID.matcher(ref).matches()) {
            return activeClient.getNode(ref, "path,permissions", null).orElse(null);
        }
        return null;
    }

    private DiscoveryNode childNode(JsonNode entry, String parentId, int depth, Instant now) {
        if (entry.path("isFolder").asBoolean(false)) {
            String folderPath = AlfrescoDocumentBuilder.pathOf(entry);
            return settings.isFolderExcluded(entry.path("name").asText(""), entry.path("id").asText(""), folderPath)
                    ? null
                    : folderNode(entry, parentId, depth);
        }
        if (entry.path("isFile").asBoolean(false)) {
            if (exclusionReason(entry, now) != null) {
                return null;
            }
            return documentNode(entry, parentId, depth);
        }
        return null;
    }

    private DiscoveryNode folderNode(JsonNode entry, String parentId, int depth) {
        String id = entry.path("id").asText("");
        if (id.isBlank()) {
            return null;
        }
        return new DiscoveryNode("alfresco-folder:" + id, id, parentId, entry.path("name").asText(id),
                AlfrescoDocumentBuilder.pathOf(entry),
                DiscoveryNode.NodeType.FOLDER, depth, null, null, modifiedAt(entry), null,
                discoverySecurity(entry.path("permissions"), settings.securityPolicy()));
    }

    private DiscoveryNode documentNode(JsonNode entry, String parentId, int depth) {
        String id = entry.path("id").asText("");
        if (id.isBlank()) {
            return null;
        }
        JsonNode content = entry.path("content");
        String mimeType = content.path("mimeType").asText("");
        return new DiscoveryNode(id, id, parentId, entry.path("name").asText(id),
                AlfrescoDocumentBuilder.pathOf(entry),
                DiscoveryNode.NodeType.DOCUMENT, depth, mimeType.isBlank() ? null : mimeType,
                content.has("sizeInBytes") ? content.path("sizeInBytes").asLong() : null, modifiedAt(entry), null,
                discoverySecurity(entry.path("permissions"), settings.securityPolicy()));
    }

    static Instant modifiedAt(JsonNode entry) {
        return AlfrescoDocumentBuilder.parseTimestamp(entry.path("modifiedAt").asText(""));
    }

    /**
     * Unified discovery security mapping: delegates directly to {@link AlfrescoSecurityMapper#mapSecurity} so search
     * and discovery always agree by construction.
     */
    static SecurityConfig discoverySecurity(JsonNode permissions) {
        return discoverySecurity(permissions, AlfrescoSecurityMapper.Policy.DEFAULT);
    }

    static SecurityConfig discoverySecurity(JsonNode permissions, AlfrescoSecurityMapper.Policy policy) {
        AlfrescoSecurityMapper.SecurityResult res = AlfrescoSecurityMapper.mapSecurity(permissions, policy);
        return res.aclString().isEmpty() ? null : res.securityConfig();
    }
}
