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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.opencrawling.googledrive.GoogleDriveCrawlState.FileEntry;
import org.opencrawling.googledrive.GoogleDriveCrawlState.FolderEntry;
import org.opencrawling.googledrive.GoogleDriveCrawlState.State;
import org.opencrawling.googledrive.GoogleDriveCrawlState.StateStore;
import org.opencrawling.googledrive.GoogleDriveDocumentBuilder.ExportTarget;
import org.opencrawling.googledrive.GoogleDriveDocumentBuilder.FileContext;
import org.opencrawling.googledrive.GoogleDriveProperties.CrawlMode;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Drive &amp; Google Workspace Shared Drives Repository Connector (issue #339).
 *
 * <p>Implements {@link DocumentFetchingRepositoryConnector} with:
 * <ul>
 *   <li>Scoping by whole drive (My Drive or Shared Drive) or explicit folder ID(s) with {@code includeSubfolders};</li>
 *   <li>Incremental syncing via Google Drive Changes API ({@code /changes/startPageToken}, {@code /changes}) with
 *       deletions, trashed items, moves out of scope, cascading folder tombstones, and periodic full reconciliation
 *       guarded against empty listings (#396);</li>
 *   <li>Control-plane crawl state persistence via {@link StateStore} (#395) so scheduled runs execute on any replica;</li>
 *   <li>Native Google Workspace export (Docs as HTML by default for structure-aware chunking #385, Slides as plain
 *       text, Sheets and Forms excluded by default) and constant-heap binary streaming via {@link TempFileInputStream};</li>
 *   <li>Version-key skip detection ({@code version}, {@code headRevisionId}, {@code md5Checksum}) avoiding downloads of
 *       unchanged files;</li>
 *   <li>Explicit {@code TOO_LARGE} reporting for files exceeding {@code max-file-size-bytes} (#397);</li>
 *   <li>Zero-Trust Google Workspace ACL mapping failing closed when permissions cannot be resolved.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "googledrive")
public class GoogleDriveRepositoryConnector implements DocumentFetchingRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveRepositoryConnector.class);

    private static final Pattern DOC_ID_PATTERN = Pattern.compile("^googledrive://drives/([^/]+)/files/([^/]+)$");
    private static final int MAX_ANCESTOR_DEPTH = 128;

    private final GoogleDriveProperties settings;
    private final StateStore stateStore;
    private final boolean ownsClient;

    private GoogleDriveClient client;
    private GoogleDriveSecurityMapper securityMapper;
    private final Map<String, String> driveNamesById = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> metadataLookupCache = new ConcurrentHashMap<>();
    private volatile String resolvedMyDriveRootId;
    private volatile boolean connected;

    /** Used by SPI ServiceLoader discovery. */
    public GoogleDriveRepositoryConnector() {
        this(GoogleDriveProperties.fromConfiguration(Map.of()), null, null);
    }

    @Autowired
    public GoogleDriveRepositoryConnector(
            Environment environment,
            ObjectProvider<StateStore> stateStoreProvider
    ) {
        this(GoogleDriveProperties.fromEnvironment(environment),
                null,
                selectStateStore(GoogleDriveProperties.fromEnvironment(environment), stateStoreProvider));
    }

    private static StateStore selectStateStore(
            GoogleDriveProperties props,
            ObjectProvider<StateStore> provider
    ) {
        if (props.stateFile() != null && !props.stateFile().isBlank()) {
            return GoogleDriveCrawlState.localFileStore(props);
        }
        return provider != null ? provider.getIfAvailable() : null;
    }

    public GoogleDriveRepositoryConnector(GoogleDriveProperties settings) {
        this(settings, null, null);
    }

    public GoogleDriveRepositoryConnector(GoogleDriveProperties settings, StateStore stateStore) {
        this(settings, null, stateStore);
    }

    GoogleDriveRepositoryConnector(
            GoogleDriveProperties settings,
            GoogleDriveClient client,
            StateStore stateStore
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.client = client;
        this.ownsClient = (client == null);
        this.stateStore = stateStore != null ? stateStore : GoogleDriveCrawlState.localFileStore(settings);
        this.securityMapper = new GoogleDriveSecurityMapper(settings);
    }

    @Override
    public String getName() {
        return "GoogleDriveRepositoryConnector";
    }

    @Override
    public boolean supportsConcurrentProcessing() {
        return true;
    }

    @Override
    public String sourceInstance() {
        return settings.sourceInstanceUri();
    }

    public GoogleDriveProperties settings() {
        return settings;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return GoogleDriveDocumentBuilder.schema();
    }

    @Override
    public synchronized void connect() throws Exception {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Google Drive repository connector is not configured: "
                    + String.join(" ", problems));
        }
        if (client == null) {
            client = new GoogleDriveClient(settings);
        }
        securityMapper = new GoogleDriveSecurityMapper(settings);
        metadataLookupCache.clear();
        driveNamesById.clear();

        JsonNode about = client.getAbout();
        String user = about.path("user").path("emailAddress").asText(
                about.path("user").path("displayName").asText("authenticated principal"));

        if (settings.hasDriveId()) {
            JsonNode drive = client.getDrive(settings.driveId().strip());
            String driveName = drive.path("name").asText(settings.driveId().strip());
            driveNamesById.put(settings.driveId().strip(), driveName);
        }

        // Resolve canonical My Drive root ID when "root" is targeted so parent checks match opaque IDs returned by Drive.
        if (settings.effectiveRootFolderIds().stream().anyMatch("root"::equalsIgnoreCase)) {
            client.getFile("root").ifPresent(rootNode -> {
                String canonicalId = rootNode.path("id").asText(null);
                if (canonicalId != null && !canonicalId.isBlank()) {
                    resolvedMyDriveRootId = canonicalId;
                }
            });
        }

        connected = true;
        log.info("Connected to Google Drive API ({}) as '{}' ({}).", settings.apiBaseUrl(), user, settings);
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        metadataLookupCache.clear();
        if (client != null && ownsClient) {
            client.close();
            client = null;
        }
    }

    private void ensureConnected() {
        if (!connected) {
            try {
                connect();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new DiscoveryException("Cannot connect to Google Drive: " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ Crawl Scan

    private final class CrawlSession {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong tooLarge = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean limitCutShort = new AtomicBoolean();
        private int admittedCount;

        final Map<String, FolderEntry> folders = new ConcurrentHashMap<>();
        final Map<String, FileEntry> previousFiles = new ConcurrentHashMap<>();
        final Map<String, FileEntry> currentFiles = new ConcurrentHashMap<>();
        final Set<String> seenFileIds = ConcurrentHashMap.newKeySet();
        final Set<String> rootFolderIds = new LinkedHashSet<>();
        final String renderSignature = settings.renderSignature();

        synchronized boolean admit() {
            if (limitReached()) {
                limitCutShort.set(true);
                return false;
            }
            admittedCount++;
            return true;
        }

        synchronized boolean limitReached() {
            return settings.maxDocuments() > 0 && admittedCount >= settings.maxDocuments();
        }

        boolean stopRequested(FluxSink<?> sink) {
            return cancelled.get() || interrupted.get() || sink.isCancelled() || Thread.currentThread().isInterrupted();
        }
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            CrawlSession crawl = new CrawlSession();
            sink.onCancel(() -> crawl.cancelled.set(true));
            try {
                connect();
                String trimmedPath = basePath == null ? "" : basePath.strip();
                boolean hasOverride = !trimmedPath.isEmpty()
                        && !"default".equalsIgnoreCase(trimmedPath)
                        && !"/".equals(trimmedPath);

                State previousState = hasOverride ? State.empty() : stateStore.load(settings.scopeKey());
                crawl.folders.putAll(previousState.folders());
                crawl.previousFiles.putAll(previousState.files());
                crawl.currentFiles.putAll(previousState.files());

                List<String> targetRoots = hasOverride ? List.of(trimmedPath) : settings.effectiveRootFolderIds();
                crawl.rootFolderIds.addAll(canonicalizeRoots(targetRoots));

                Instant now = client.lastServerTime().orElse(Instant.now());
                boolean scheduledFullScan = !hasOverride
                        && settings.crawlMode() == CrawlMode.CHANGES
                        && previousState.startPageToken() != null
                        && settings.fullScanDue(
                                GoogleDriveDocumentBuilder.parseInstant(previousState.lastFullScanAt()), now);

                String nextStartPageToken = previousState.startPageToken();
                String nextLastFullScanAt = previousState.lastFullScanAt();
                boolean fullEnumeration = false;

                if (hasOverride) {
                    log.info("Google Drive scoped tree crawl from override folder '{}'.", trimmedPath);
                    crawlTree(targetRoots, false, crawl, sink);
                } else if (settings.crawlMode() == CrawlMode.TREE) {
                    fullEnumeration = true;
                    crawlTree(targetRoots, true, crawl, sink);
                    if (isCleanCompletion(crawl, sink) && !emptyListingGuarded(crawl)) {
                        nextLastFullScanAt = now.truncatedTo(ChronoUnit.SECONDS).toString();
                    }
                } else if (previousState.startPageToken() == null || scheduledFullScan) {
                    fullEnumeration = true;
                    log.info("Google Drive full reconciliation crawl ({}).",
                            scheduledFullScan ? "periodic full scan" : "initial Changes API bootstrap");
                    String capturedToken = client.getStartPageToken(changesDriveId());
                    crawlTree(targetRoots, true, crawl, sink);
                    if (isCleanCompletion(crawl, sink) && !emptyListingGuarded(crawl)) {
                        nextStartPageToken = capturedToken;
                        nextLastFullScanAt = now.truncatedTo(ChronoUnit.SECONDS).toString();
                    }
                } else {
                    Optional<String> advancedToken = crawlChanges(previousState.startPageToken(), crawl, sink);
                    if (advancedToken.isPresent()) {
                        if (isCleanCompletion(crawl, sink)) {
                            nextStartPageToken = advancedToken.get();
                        }
                    } else if (!crawl.stopRequested(sink)) {
                        // Invalid/expired page token -> automatic full reconciliation fallback
                        fullEnumeration = true;
                        log.warn("Google Drive Changes API page token expired or invalid; falling back to full reconciliation.");
                        String capturedToken = client.getStartPageToken(changesDriveId());
                        crawl.seenFileIds.clear();
                        crawlTree(targetRoots, true, crawl, sink);
                        if (isCleanCompletion(crawl, sink) && !emptyListingGuarded(crawl)) {
                            nextStartPageToken = capturedToken;
                            nextLastFullScanAt = now.truncatedTo(ChronoUnit.SECONDS).toString();
                        }
                    }
                }

                if (crawl.stopRequested(sink)) {
                    log.info("Google Drive scan stopped before completion ({} docs emitted); crawl state not advanced.",
                            crawl.documents.get());
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                    return;
                }

                if (!hasOverride) {
                    State updated = new State(
                            nextStartPageToken,
                            nextLastFullScanAt,
                            new LinkedHashMap<>(crawl.folders),
                            new LinkedHashMap<>(crawl.currentFiles)
                    );
                    stateStore.save(settings.scopeKey(), updated);
                }

                log.info("Google Drive scan completed ({}{}): {} documents, {} unchanged, {} too_large, "
                                + "{} tombstones, {} skipped, {} failed.",
                        fullEnumeration ? "full" : "incremental",
                        scheduledFullScan ? ", periodic reconciliation" : "",
                        crawl.documents.get(), crawl.unchanged.get(), crawl.tooLarge.get(),
                        crawl.tombstones.get(), crawl.skipped.get(), crawl.failed.get());
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (crawl.cancelled.get() || sink.isCancelled()) {
                    log.info("Google Drive scan stopped after downstream cancellation.");
                    return;
                }
                log.error("Google Drive scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                disconnect();
            }
        });
    }

    private boolean isCleanCompletion(CrawlSession crawl, FluxSink<?> sink) {
        return !crawl.stopRequested(sink) && !crawl.limitCutShort.get() && crawl.failed.get() == 0;
    }

    private boolean emptyListingGuarded(CrawlSession crawl) {
        return crawl.seenFileIds.isEmpty() && !crawl.previousFiles.isEmpty();
    }

    private String changesDriveId() {
        return settings.hasDriveId() ? settings.driveId().strip() : null;
    }

    private List<String> canonicalizeRoots(List<String> roots) {
        List<String> out = new ArrayList<>();
        for (String r : roots) {
            if ("root".equalsIgnoreCase(r) && resolvedMyDriveRootId != null) {
                out.add(resolvedMyDriveRootId);
                out.add("root");
            } else {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ TREE Enumeration & Reconciliation

    private void crawlTree(
            List<String> rootRefs,
            boolean reconcileOrphans,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        Set<String> visitedFolders = new HashSet<>();
        Deque<FolderCursor> queue = new ArrayDeque<>();

        for (String rootRef : rootRefs) {
            if (crawl.stopRequested(sink)) {
                return;
            }
            String canonicalId = resolveCanonicalFolderId(rootRef);
            String rootName = resolveRootFolderDisplayName(rootRef, canonicalId);
            String driveId = settings.hasDriveId() ? settings.driveId().strip() : "";
            crawl.folders.put(canonicalId, new FolderEntry(null, rootName, driveId));
            if (visitedFolders.add(canonicalId)) {
                queue.add(new FolderCursor(canonicalId, 0));
            }
        }

        while (!queue.isEmpty() && !crawl.stopRequested(sink) && !crawl.limitReached()) {
            FolderCursor cursor = queue.poll();
            String query = "'" + escapeQuery(cursor.folderId()) + "' in parents and trashed = false";
            String pageToken = null;
            do {
                if (crawl.stopRequested(sink) || crawl.limitReached()) {
                    break;
                }
                GoogleDriveClient.FilesPage page = client.listFiles(query, pageToken, changesDriveId());
                List<JsonNode> pageFiles = new ArrayList<>();
                for (JsonNode item : page.files()) {
                    if (crawl.stopRequested(sink)) {
                        return;
                    }
                    String itemId = item.path("id").asText(null);
                    if (itemId == null || itemId.isBlank() || item.path("trashed").asBoolean(false)) {
                        continue;
                    }
                    if (GoogleDriveDocumentBuilder.isFolder(item)) {
                        String folderName = item.path("name").asText(itemId);
                        String itemDrive = item.path("driveId").asText(settings.hasDriveId() ? settings.driveId().strip() : "");
                        crawl.folders.put(itemId, new FolderEntry(cursor.folderId(), folderName, itemDrive));
                        if (settings.includeSubfolders() && visitedFolders.add(itemId)) {
                            queue.add(new FolderCursor(itemId, cursor.depth() + 1));
                        }
                    } else {
                        pageFiles.add(item);
                    }
                }
                processFilesInParallel(pageFiles, crawl, sink);
                pageToken = page.nextPageToken();
            } while (pageToken != null && !pageToken.isBlank());
        }

        if (reconcileOrphans && isCleanCompletion(crawl, sink) && !crawl.previousFiles.isEmpty()) {
            if (crawl.seenFileIds.isEmpty()) {
                // Empty-listing guard (#396): never wipe the index if permissions were revoked or API returned empty.
                log.warn("Google Drive full enumeration returned 0 files while {} files are indexed; "
                        + "empty-listing guard prevented mass tombstoning.", crawl.previousFiles.size());
                return;
            }
            for (Map.Entry<String, FileEntry> prev : crawl.previousFiles.entrySet()) {
                if (crawl.stopRequested(sink)) {
                    return;
                }
                String fileId = prev.getKey();
                if (!crawl.seenFileIds.contains(fileId)) {
                    FileEntry entry = prev.getValue();
                    emitTombstone(entry.docId(), entry.driveId(), fileId, crawl, sink);
                    crawl.currentFiles.remove(fileId);
                }
            }
        }
    }

    private record FolderCursor(String folderId, int depth) {
    }

    // ------------------------------------------------------------------ CHANGES API Incremental Sync

    /**
     * Applies all pages of the Drive Changes API feed starting at {@code startPageToken}.
     *
     * @return the {@code newStartPageToken} if changes were processed, or {@link Optional#empty()} when the token was
     *         expired/invalid and the caller should perform a full reconciliation fallback.
     */
    private Optional<String> crawlChanges(
            String startPageToken,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        String pageToken = startPageToken;
        String newStartPageToken = null;

        while (pageToken != null && !pageToken.isBlank() && !crawl.stopRequested(sink) && !crawl.limitReached()) {
            GoogleDriveClient.ChangesPage page;
            try {
                page = client.listChanges(pageToken, changesDriveId());
            } catch (GoogleDriveApiException e) {
                if (e.isInvalidPageToken()) {
                    return Optional.empty();
                }
                throw e;
            }

            List<JsonNode> filesToProcess = new ArrayList<>();
            for (JsonNode change : page.changes()) {
                if (crawl.stopRequested(sink)) {
                    return Optional.ofNullable(newStartPageToken);
                }
                String fileId = GoogleDriveDocumentBuilder.text(change, "fileId");
                JsonNode file = change.path("file");
                if ((fileId == null || fileId.isBlank()) && file.isObject()) {
                    fileId = GoogleDriveDocumentBuilder.text(file, "id");
                }
                if (fileId == null || fileId.isBlank()) {
                    continue;
                }

                boolean removed = change.path("removed").asBoolean(false)
                        || !file.isObject()
                        || file.path("trashed").asBoolean(false)
                        || file.path("explicitlyTrashed").asBoolean(false);

                if (removed) {
                    handleRemovedChange(fileId, file, crawl, sink);
                    continue;
                }

                if (GoogleDriveDocumentBuilder.isFolder(file)) {
                    handleFolderChange(fileId, file, crawl, sink);
                    continue;
                }

                // Active file (or shortcut): check whether it is currently inside the configured scope.
                if (!isFileInScope(file, crawl)) {
                    FileEntry known = crawl.currentFiles.remove(fileId);
                    if (known != null) {
                        log.debug("Google Drive file {} ('{}') moved out of scope or became excluded; tombstoning.",
                                fileId, known.name());
                        emitTombstone(known.docId(), known.driveId(), fileId, crawl, sink);
                    } else {
                        crawl.skipped.incrementAndGet();
                    }
                    continue;
                }

                filesToProcess.add(file);
            }

            processFilesInParallel(filesToProcess, crawl, sink);

            if (page.nextPageToken() != null && !page.nextPageToken().isBlank()) {
                pageToken = page.nextPageToken();
            } else {
                newStartPageToken = page.newStartPageToken();
                pageToken = null;
            }
        }
        return Optional.of(newStartPageToken != null ? newStartPageToken : startPageToken);
    }

    private void handleRemovedChange(
            String fileId,
            JsonNode file,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) {
        if (crawl.folders.containsKey(fileId)) {
            cascadeRemoveFolder(fileId, crawl, sink);
            return;
        }
        FileEntry known = crawl.currentFiles.remove(fileId);
        if (known != null) {
            emitTombstone(known.docId(), known.driveId(), fileId, crawl, sink);
            return;
        }
        // Whole-drive crawl without folder scoping: emit tombstone for deleted non-folder files even if unseen locally.
        if (!settings.isFolderScoped() && (!file.isObject() || !GoogleDriveDocumentBuilder.isFolder(file))) {
            String driveId = file.isObject()
                    ? GoogleDriveDocumentBuilder.textOrDefault(file, "driveId", settings.driveId())
                    : settings.driveId();
            String origMime = file.isObject() ? GoogleDriveDocumentBuilder.text(file, "mimeType") : null;
            if (origMime == null || settings.acceptsMimeType(origMime, null)) {
                emitTombstone(GoogleDriveDocumentBuilder.documentId(driveId, fileId), driveId, fileId, crawl, sink);
            }
        }
    }

    private void handleFolderChange(
            String folderId,
            JsonNode folder,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        boolean wasKnown = crawl.folders.containsKey(folderId);
        boolean inScope = isFolderInScope(folder, crawl);

        if (!inScope) {
            if (wasKnown) {
                log.debug("Google Drive folder {} ('{}') moved out of scope; tombstoning known descendants.",
                        folderId, folder.path("name").asText(folderId));
                cascadeRemoveFolder(folderId, crawl, sink);
            }
            return;
        }

        String parentId = firstParent(folder);
        if (crawl.rootFolderIds.contains(folderId)) {
            parentId = null;
        }
        String name = folder.path("name").asText(folderId);
        String driveId = folder.path("driveId").asText(settings.hasDriveId() ? settings.driveId().strip() : "");
        crawl.folders.put(folderId, new FolderEntry(parentId, name, driveId));

        // If a folder moved into scope (wasn't previously tracked), crawl its subtree so existing files inside it are indexed.
        if (!wasKnown && settings.includeSubfolders()) {
            crawlSubtreeIntoScope(folderId, crawl, sink);
        }
    }

    private void crawlSubtreeIntoScope(
            String startFolderId,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(startFolderId);
        queue.add(startFolderId);
        while (!queue.isEmpty() && !crawl.stopRequested(sink) && !crawl.limitReached()) {
            String current = queue.poll();
            String query = "'" + escapeQuery(current) + "' in parents and trashed = false";
            String nextToken = null;
            do {
                GoogleDriveClient.FilesPage page = client.listFiles(query, nextToken, changesDriveId());
                List<JsonNode> files = new ArrayList<>();
                for (JsonNode item : page.files()) {
                    String itemId = item.path("id").asText(null);
                    if (itemId == null || item.path("trashed").asBoolean(false)) {
                        continue;
                    }
                    if (GoogleDriveDocumentBuilder.isFolder(item)) {
                        crawl.folders.put(itemId, new FolderEntry(
                                current,
                                item.path("name").asText(itemId),
                                item.path("driveId").asText(settings.hasDriveId() ? settings.driveId().strip() : "")));
                        if (visited.add(itemId)) {
                            queue.add(itemId);
                        }
                    } else {
                        files.add(item);
                    }
                }
                processFilesInParallel(files, crawl, sink);
                nextToken = page.nextPageToken();
            } while (nextToken != null && !nextToken.isBlank());
        }
    }

    private void cascadeRemoveFolder(String folderId, CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        List<String> descendantFiles = GoogleDriveCrawlState.descendantFileIds(crawl.folders, crawl.currentFiles, folderId);
        for (String childFileId : descendantFiles) {
            FileEntry entry = crawl.currentFiles.remove(childFileId);
            if (entry != null) {
                emitTombstone(entry.docId(), entry.driveId(), childFileId, crawl, sink);
            }
        }
        Set<String> subtreeFolders = GoogleDriveCrawlState.subtreeFolderIds(crawl.folders, folderId);
        subtreeFolders.forEach(crawl.folders::remove);
    }

    // ------------------------------------------------------------------ Scope & File Processing

    private boolean isFolderInScope(JsonNode folder, CrawlSession crawl) throws IOException, InterruptedException {
        String id = folder.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return false;
        }
        if (settings.hasDriveId()) {
            String itemDrive = GoogleDriveDocumentBuilder.text(folder, "driveId");
            if (itemDrive != null && !itemDrive.equals(settings.driveId().strip())) {
                return false;
            }
        }
        if (crawl.rootFolderIds.contains(id)) {
            return true;
        }
        if (!settings.includeSubfolders()) {
            return false;
        }
        return hasInScopeAncestor(firstParent(folder), crawl, true);
    }

    private boolean isFileInScope(JsonNode file, CrawlSession crawl) throws IOException, InterruptedException {
        if (settings.hasDriveId()) {
            String itemDrive = GoogleDriveDocumentBuilder.text(file, "driveId");
            if (itemDrive == null || !itemDrive.equals(settings.driveId().strip())) {
                return false;
            }
        }
        String parentId = firstParent(file);
        if (settings.isFolderScoped()) {
            if (parentId == null || parentId.isBlank()) {
                return false;
            }
            if (!settings.includeSubfolders()) {
                if (!crawl.rootFolderIds.contains(parentId)) {
                    return false;
                }
            } else if (!hasInScopeAncestor(parentId, crawl, true)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasInScopeAncestor(String startParentId, CrawlSession crawl, boolean allowRemoteLookup)
            throws IOException, InterruptedException {
        if (startParentId == null || startParentId.isBlank()) {
            return !settings.isFolderScoped();
        }
        String current = startParentId;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; current != null && !current.isBlank() && depth < MAX_ANCESTOR_DEPTH && visited.add(current); depth++) {
            if (crawl.rootFolderIds.contains(current)) {
                return true;
            }
            FolderEntry cached = crawl.folders.get(current);
            if (cached != null) {
                current = cached.parentId();
                continue;
            }
            if (!allowRemoteLookup) {
                return false;
            }
            Optional<JsonNode> fetched = lookupFileMetadata(current);
            if (fetched.isEmpty() || fetched.get().path("trashed").asBoolean(false)) {
                return false;
            }
            JsonNode parentNode = fetched.get();
            String nextParent = firstParent(parentNode);
            if (crawl.rootFolderIds.contains(current)) {
                return true;
            }
            current = nextParent;
        }
        return false;
    }

    @SuppressWarnings("preview")
    private void processFilesInParallel(
            List<JsonNode> files,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws InterruptedException {
        if (files.isEmpty() || crawl.stopRequested(sink)) {
            return;
        }
        if (files.size() == 1 || settings.parallelism() <= 1) {
            for (JsonNode file : files) {
                if (crawl.stopRequested(sink) || !crawl.admit()) {
                    break;
                }
                handleFileSafely(file, crawl, sink);
            }
            return;
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode file : files) {
                if (crawl.stopRequested(sink) || !crawl.admit()) {
                    break;
                }
                permits.acquire();
                if (crawl.stopRequested(sink)) {
                    permits.release();
                    break;
                }
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        if (!crawl.stopRequested(sink)) {
                            handleFileSafely(file, crawl, sink);
                        }
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            crawl.failed.incrementAndGet();
            log.error("Parallel Google Drive file processing error: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void handleFileSafely(JsonNode file, CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        try {
            handleFile(file, crawl, sink);
        } catch (InterruptedException e) {
            crawl.interrupted.set(true);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!crawl.stopRequested(sink)) {
                crawl.failed.incrementAndGet();
                log.error("Failed to crawl Google Drive file {} ('{}'): {}",
                        file.path("id").asText("?"), file.path("name").asText("?"), e.getMessage());
            }
        }
    }

    private void handleFile(JsonNode rawFile, CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        if (crawl.stopRequested(sink)) {
            return;
        }
        String rawFileId = rawFile.path("id").asText("");
        if (rawFileId.isBlank()) {
            return;
        }

        // Resolve shortcut if configured
        JsonNode file = rawFile;
        String shortcutSourceId = null;
        if (GoogleDriveDocumentBuilder.isShortcut(rawFile)) {
            if (!settings.followShortcuts()) {
                crawl.skipped.incrementAndGet();
                return;
            }
            Optional<JsonNode> target = resolveShortcutTarget(rawFile);
            if (target.isEmpty() || GoogleDriveDocumentBuilder.isFolder(target.get())
                    || target.get().path("trashed").asBoolean(false)) {
                crawl.skipped.incrementAndGet();
                return;
            }
            shortcutSourceId = rawFileId;
            file = target.get();
        }

        String fileId = file.path("id").asText("");
        if (fileId.isBlank()) {
            return;
        }

        String originalMime = GoogleDriveDocumentBuilder.text(file, "mimeType");
        boolean isWorkspaceDoc = GoogleDriveDocumentBuilder.isGoogleWorkspaceMime(originalMime);
        ExportTarget exportTarget = null;
        if (isWorkspaceDoc) {
            if (!settings.exportGoogleDocs()) {
                handleExcludedFile(fileId, crawl, sink);
                return;
            }
            Optional<ExportTarget> resolved = GoogleDriveDocumentBuilder.resolveExportTarget(originalMime, settings);
            if (resolved.isEmpty()) {
                // Non-exportable workspace app (Forms, Sites, Maps, etc.)
                handleExcludedFile(fileId, crawl, sink);
                return;
            }
            exportTarget = resolved.get();
        }

        if (!settings.acceptsMimeType(originalMime, exportTarget != null ? exportTarget.mimeType() : null)) {
            handleExcludedFile(fileId, crawl, sink);
            return;
        }

        crawl.seenFileIds.add(fileId);

        // Fetch permissions first (fail-closed: never emit with public ACL if permission fetch fails!)
        GoogleDriveSecurityMapper.Mapped security = settings.includeAcls()
                ? securityMapper.map(client.listPermissions(fileId))
                : GoogleDriveSecurityMapper.publicAccess();

        String parentId = firstParent(rawFile);
        if (parentId == null) {
            parentId = firstParent(file);
        }
        FileContext context = resolveFileContext(file, parentId, exportTarget, shortcutSourceId, crawl.folders, crawl.rootFolderIds);

        String versionKey = GoogleDriveDocumentBuilder.versionKey(file);
        FileEntry known = crawl.currentFiles.get(fileId);
        if (known != null
                && versionKey != null && versionKey.equals(known.versionKey())
                && security.fingerprint().equals(known.aclFingerprint())
                && crawl.renderSignature.equals(known.renderSignature())
                && Objects.equals(context.fullPath(), known.path())) {
            crawl.unchanged.incrementAndGet();
            return;
        }

        // Check declared binary file size before downloading
        long declaredSize = file.path("size").asLong(-1L);
        if (settings.maxFileSizeBytes() > 0 && declaredSize > settings.maxFileSizeBytes()) {
            reportTooLarge(file, fileId, parentId, context, exportTarget, declaredSize,
                    "File size " + declaredSize + " bytes exceeds max-file-size-bytes (" + settings.maxFileSizeBytes() + " bytes)",
                    versionKey, security, crawl, sink);
            return;
        }

        // Stream binary download or native Google Workspace export into a temporary file
        Path tempFile = Files.createTempFile("oc-gdrive-", exportTarget != null && !exportTarget.extension().isEmpty()
                ? exportTarget.extension() : ".bin");
        long writtenBytes;
        try {
            if (exportTarget != null) {
                writtenBytes = client.exportFile(fileId, exportTarget.mimeType(), tempFile, settings.maxFileSizeBytes());
            } else {
                writtenBytes = client.downloadFile(fileId, tempFile, settings.maxFileSizeBytes());
            }
        } catch (GoogleDriveApiException e) {
            Files.deleteIfExists(tempFile);
            if (e.isExportSizeLimitExceeded()) {
                reportTooLarge(file, fileId, parentId, context, exportTarget, declaredSize,
                        "Google Drive export size limit exceeded (10 MB API cap): " + e.getMessage(),
                        versionKey, security, crawl, sink);
                return;
            }
            throw e;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        if (writtenBytes < 0) {
            Files.deleteIfExists(tempFile);
            reportTooLarge(file, fileId, parentId, context, exportTarget, declaredSize,
                    "Streamed content exceeds max-file-size-bytes (" + settings.maxFileSizeBytes() + " bytes)",
                    versionKey, security, crawl, sink);
            return;
        }

        InputStream contentStream = new TempFileInputStream(tempFile);
        RepositoryDocument document;
        try {
            document = GoogleDriveDocumentBuilder.build(
                    file, context, exportTarget, settings, contentStream, writtenBytes, security);
        } catch (RuntimeException e) {
            contentStream.close();
            throw e;
        }

        emit(sink, document);
        crawl.documents.incrementAndGet();
        crawl.currentFiles.put(fileId, new FileEntry(
                document.id(),
                context.driveId(),
                parentId,
                file.path("name").asText(fileId),
                context.fullPath(),
                versionKey,
                security.fingerprint(),
                crawl.renderSignature,
                "INDEXED"
        ));
    }

    private void handleExcludedFile(String fileId, CrawlSession crawl, FluxSink<RepositoryDocument> sink) {
        FileEntry known = crawl.currentFiles.remove(fileId);
        if (known != null) {
            emitTombstone(known.docId(), known.driveId(), fileId, crawl, sink);
        } else {
            crawl.skipped.incrementAndGet();
        }
    }

    private void reportTooLarge(
            JsonNode file,
            String fileId,
            String parentId,
            FileContext context,
            ExportTarget exportTarget,
            long reportedSizeBytes,
            String reason,
            String versionKey,
            GoogleDriveSecurityMapper.Mapped security,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) {
        log.warn("Google Drive file {} ('{}') reported as TOO_LARGE: {}", fileId, file.path("name").asText(fileId), reason);
        RepositoryDocument tooLargeDoc = GoogleDriveDocumentBuilder.buildTooLarge(
                file, context, exportTarget, settings, reportedSizeBytes, reason, security);
        emit(sink, tooLargeDoc);
        crawl.tooLarge.incrementAndGet();
        crawl.currentFiles.put(fileId, new FileEntry(
                tooLargeDoc.id(),
                context.driveId(),
                parentId,
                file.path("name").asText(fileId),
                context.fullPath(),
                versionKey,
                security.fingerprint(),
                crawl.renderSignature,
                GoogleDriveDocumentBuilder.STATUS_TOO_LARGE
        ));
    }

    private Optional<JsonNode> resolveShortcutTarget(JsonNode shortcutFile) throws IOException, InterruptedException {
        Set<String> seen = new HashSet<>();
        JsonNode current = shortcutFile;
        for (int hops = 0; hops < 8 && GoogleDriveDocumentBuilder.isShortcut(current); hops++) {
            String currentId = current.path("id").asText("");
            if (!seen.add(currentId)) {
                return Optional.empty();
            }
            String targetId = current.path("shortcutDetails").path("targetId").asText(null);
            if (targetId == null || targetId.isBlank()) {
                return Optional.empty();
            }
            Optional<JsonNode> target = client.getFile(targetId);
            if (target.isEmpty()) {
                return Optional.empty();
            }
            current = target.get();
        }
        return GoogleDriveDocumentBuilder.isShortcut(current) ? Optional.empty() : Optional.of(current);
    }

    // ------------------------------------------------------------------ Pre-Crawl Discovery & On-Demand Fetching

    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        try {
            List<DiscoveryNode> startNodes = resolveExploreStarts(request.start(), filter);
            Deque<DiscoveryNode> queue = new ArrayDeque<>(startNodes);
            List<DiscoveryNode> visibleNodes = new ArrayList<>();
            long hiddenCount = 0L;
            TruncationReason truncation = null;

            while (!queue.isEmpty()) {
                if (System.nanoTime() - deadline > 0) {
                    return new DiscoveryResult(visibleNodes, hiddenCount, TruncationReason.TIMEOUT);
                }
                DiscoveryNode current = queue.poll();
                String query = "'" + escapeQuery(current.sourceId()) + "' in parents and trashed = false";
                String pageToken = null;
                do {
                    if (System.nanoTime() - deadline > 0) {
                        return new DiscoveryResult(visibleNodes, hiddenCount, TruncationReason.TIMEOUT);
                    }
                    GoogleDriveClient.FilesPage page = client.listFiles(query, pageToken, changesDriveId());
                    List<DiscoveryNode> candidates = new ArrayList<>();
                    for (JsonNode item : page.files()) {
                        boolean isFolder = GoogleDriveDocumentBuilder.isFolder(item);
                        if (!isFolder && !request.includeDocuments()) {
                            continue;
                        }
                        if (!isFolder && !isDiscoveryDocumentAccepted(item)) {
                            continue;
                        }
                        candidates.add(toDiscoveryNode(item, current.sourceId(), current.path(), current.depth() + 1));
                    }
                    boolean[] visible = VisibilityFilter.decide(filter, candidates);
                    for (int i = 0; i < visible.length; i++) {
                        if (!visible[i]) {
                            hiddenCount++;
                            continue;
                        }
                        DiscoveryNode child = candidates.get(i);
                        if (visibleNodes.size() >= request.maxNodes()) {
                            return new DiscoveryResult(visibleNodes, hiddenCount, TruncationReason.MAX_NODES);
                        }
                        visibleNodes.add(child);
                        if (child.type() == DiscoveryNode.NodeType.FOLDER) {
                            if (!settings.includeSubfolders()) {
                                continue;
                            }
                            if (child.depth() < request.maxDepth()) {
                                queue.add(child);
                            } else {
                                truncation = TruncationReason.MAX_DEPTH;
                            }
                        }
                    }
                    pageToken = page.nextPageToken();
                } while (pageToken != null && !pageToken.isBlank());
            }
            return new DiscoveryResult(visibleNodes, hiddenCount, truncation);
        } catch (IOException e) {
            throw new DiscoveryException("Google Drive discovery exploration failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Google Drive discovery exploration was interrupted.", e);
        }
    }

    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        try {
            List<DiscoveryNode> startNodes = resolveExploreStarts(request.scope(), filter);
            Deque<DiscoveryNode> queue = new ArrayDeque<>(startNodes);
            Set<String> visitedFolders = new HashSet<>();
            startNodes.forEach(n -> visitedFolders.add(n.sourceId()));

            List<DiscoveryNode> samples = new ArrayList<>();
            long scanned = 0L;
            long matched = 0L;
            long hidden = 0L;

            while (!queue.isEmpty()) {
                if (System.nanoTime() - deadline > 0) {
                    return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.TIMEOUT);
                }
                DiscoveryNode folder = queue.poll();
                String query = "'" + escapeQuery(folder.sourceId()) + "' in parents and trashed = false";
                String pageToken = null;
                do {
                    if (System.nanoTime() - deadline > 0) {
                        return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.TIMEOUT);
                    }
                    GoogleDriveClient.FilesPage page = client.listFiles(query, pageToken, changesDriveId());
                    List<DiscoveryNode> folderCandidates = new ArrayList<>();
                    List<DiscoveryNode> docCandidates = new ArrayList<>();

                    for (JsonNode item : page.files()) {
                        if (GoogleDriveDocumentBuilder.isFolder(item)) {
                            if (settings.includeSubfolders()) {
                                folderCandidates.add(toDiscoveryNode(item, folder.sourceId(), folder.path(), folder.depth() + 1));
                            }
                        } else if (isDiscoveryDocumentAccepted(item)) {
                            docCandidates.add(toDiscoveryNode(item, folder.sourceId(), folder.path(), folder.depth() + 1));
                        }
                    }

                    // Only descend into folders the caller can see!
                    if (!folderCandidates.isEmpty()) {
                        boolean[] folderVis = VisibilityFilter.decide(filter, folderCandidates);
                        for (int i = 0; i < folderVis.length; i++) {
                            if (folderVis[i] && visitedFolders.add(folderCandidates.get(i).sourceId())) {
                                queue.add(folderCandidates.get(i));
                            }
                        }
                    }

                    if (!docCandidates.isEmpty()) {
                        boolean[] docVis = VisibilityFilter.decide(filter, docCandidates);
                        for (int i = 0; i < docVis.length; i++) {
                            if (scanned >= request.maxScan()) {
                                return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.MAX_SCAN);
                            }
                            scanned++;
                            if (!docVis[i]) {
                                hidden++;
                                continue;
                            }
                            matched++;
                            if (samples.size() < request.sampleSize()) {
                                samples.add(docCandidates.get(i));
                            }
                        }
                    }
                    pageToken = page.nextPageToken();
                } while (pageToken != null && !pageToken.isBlank());
            }
            return new DryRunResult(matched, CountAccuracy.EXACT, samples, hidden, null);
        } catch (IOException e) {
            throw new DiscoveryException("Google Drive dry run failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Google Drive dry run was interrupted.", e);
        }
    }

    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        ensureConnected();
        try {
            String targetRef = (reference == null || reference.isBlank() || "/".equals(reference.strip()))
                    ? settings.effectiveRootFolderIds().getFirst()
                    : reference.strip();
            return resolveVisibleFolder(targetRef, filter);
        } catch (IOException e) {
            throw new DiscoveryException("Google Drive crawlStart resolution failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Google Drive crawlStart resolution was interrupted.", e);
        }
    }

    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        ensureConnected();
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("No Google Drive document '" + documentId + "' in this connector's scope.");
        }
        String trimmed = documentId.strip();
        String fileId = trimmed;
        String hintDriveId = settings.driveId();
        Matcher m = DOC_ID_PATTERN.matcher(trimmed);
        if (m.matches()) {
            hintDriveId = GoogleDriveDocumentBuilder.MY_DRIVE_SEGMENT.equals(m.group(1)) ? "" : m.group(1);
            fileId = m.group(2);
        }
        String canonicalGoneId = GoogleDriveDocumentBuilder.documentId(hintDriveId, fileId);

        try {
            Optional<JsonNode> opt = client.getFile(fileId);
            if (opt.isEmpty() || opt.get().path("trashed").asBoolean(false)
                    || opt.get().path("explicitlyTrashed").asBoolean(false)) {
                return new FetchedDocument.Gone(canonicalGoneId);
            }
            JsonNode file = opt.get();
            if (GoogleDriveDocumentBuilder.isFolder(file)) {
                throw new IllegalArgumentException("No Google Drive document '" + documentId + "' in this connector's scope.");
            }
            if (!isDiscoveryDocumentAccepted(file)) {
                throw new IllegalArgumentException("No Google Drive document '" + documentId + "' in this connector's scope.");
            }
            if (!isFileInConfiguredScope(file)) {
                throw new IllegalArgumentException("No Google Drive document '" + documentId + "' in this connector's scope.");
            }

            // Fail-closed: unreadable permissions yield security = null, which VisibilityFilter rejects.
            SecurityConfig securityConfig = resolveDiscoverySecurity(fileId);
            String originalMime = GoogleDriveDocumentBuilder.text(file, "mimeType");
            ExportTarget exportTarget = GoogleDriveDocumentBuilder.isGoogleWorkspaceMime(originalMime)
                    ? GoogleDriveDocumentBuilder.resolveExportTarget(originalMime, settings).orElse(null)
                    : null;

            String parentId = firstParent(file);
            Set<String> rootSet = new LinkedHashSet<>(canonicalizeRoots(settings.effectiveRootFolderIds()));
            FileContext context = resolveFileContext(file, parentId, exportTarget, null, Map.of(), rootSet);
            String docId = GoogleDriveDocumentBuilder.documentId(context.driveId(), fileId);
            Long sizeBytes = file.hasNonNull("size") ? file.path("size").asLong() : null;
            Instant modified = GoogleDriveDocumentBuilder.parseInstant(GoogleDriveDocumentBuilder.text(file, "modifiedTime"));

            DiscoveryNode node = new DiscoveryNode(
                    docId,
                    fileId,
                    parentId,
                    file.path("name").asText(fileId),
                    context.fullPath(),
                    DiscoveryNode.NodeType.DOCUMENT,
                    1,
                    exportTarget != null ? exportTarget.mimeType() : originalMime,
                    sizeBytes,
                    modified,
                    null,
                    securityConfig
            );

            if (!VisibilityFilter.decide(filter, List.of(node))[0]) {
                throw new IllegalArgumentException("No Google Drive document '" + documentId + "' in this connector's scope.");
            }

            GoogleDriveSecurityMapper.Mapped mappedSecurity = settings.includeAcls()
                    ? securityMapper.map(client.listPermissions(fileId))
                    : GoogleDriveSecurityMapper.publicAccess();

            long declaredSize = file.path("size").asLong(-1L);
            if (settings.maxFileSizeBytes() > 0 && declaredSize > settings.maxFileSizeBytes()) {
                RepositoryDocument tooLargeDoc = GoogleDriveDocumentBuilder.buildTooLarge(
                        file, context, exportTarget, settings, declaredSize,
                        "File size " + declaredSize + " bytes exceeds max-file-size-bytes (" + settings.maxFileSizeBytes() + " bytes)",
                        mappedSecurity);
                return new FetchedDocument.Found(node, tooLargeDoc);
            }

            Path tempFile = Files.createTempFile("oc-gdrive-", exportTarget != null && !exportTarget.extension().isEmpty()
                    ? exportTarget.extension() : ".bin");
            long writtenBytes;
            try {
                writtenBytes = exportTarget != null
                        ? client.exportFile(fileId, exportTarget.mimeType(), tempFile, settings.maxFileSizeBytes())
                        : client.downloadFile(fileId, tempFile, settings.maxFileSizeBytes());
            } catch (GoogleDriveApiException e) {
                Files.deleteIfExists(tempFile);
                if (e.isExportSizeLimitExceeded()) {
                    RepositoryDocument tooLargeDoc = GoogleDriveDocumentBuilder.buildTooLarge(
                            file, context, exportTarget, settings, declaredSize,
                            "Google Drive export size limit exceeded: " + e.getMessage(), mappedSecurity);
                    return new FetchedDocument.Found(node, tooLargeDoc);
                }
                throw e;
            } catch (IOException | InterruptedException | RuntimeException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }

            if (writtenBytes < 0) {
                Files.deleteIfExists(tempFile);
                RepositoryDocument tooLargeDoc = GoogleDriveDocumentBuilder.buildTooLarge(
                        file, context, exportTarget, settings, declaredSize,
                        "Streamed content exceeds max-file-size-bytes (" + settings.maxFileSizeBytes() + " bytes)",
                        mappedSecurity);
                return new FetchedDocument.Found(node, tooLargeDoc);
            }

            InputStream stream = new TempFileInputStream(tempFile);
            try {
                RepositoryDocument doc = GoogleDriveDocumentBuilder.build(
                        file, context, exportTarget, settings, stream, writtenBytes, mappedSecurity);
                return new FetchedDocument.Found(node, doc);
            } catch (RuntimeException e) {
                stream.close();
                throw e;
            }
        } catch (IOException e) {
            throw new DiscoveryException("Google Drive document fetch failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Google Drive document fetch was interrupted.", e);
        }
    }

    private List<DiscoveryNode> resolveExploreStarts(String startRef, VisibilityFilter filter)
            throws IOException, InterruptedException {
        if (startRef == null || startRef.isBlank() || "/".equals(startRef.strip())) {
            List<DiscoveryNode> visibleRoots = new ArrayList<>();
            for (String rootId : settings.effectiveRootFolderIds()) {
                try {
                    visibleRoots.add(resolveVisibleFolder(rootId, filter));
                } catch (IllegalArgumentException hiddenRoot) {
                    if (settings.effectiveRootFolderIds().size() == 1) {
                        throw hiddenRoot;
                    }
                }
            }
            if (visibleRoots.isEmpty()) {
                throw new IllegalArgumentException("No Google Drive folder '/' in this connector's scope.");
            }
            return visibleRoots;
        }
        return List.of(resolveVisibleFolder(startRef.strip(), filter));
    }

    private DiscoveryNode resolveVisibleFolder(String folderRef, VisibilityFilter filter)
            throws IOException, InterruptedException {
        Optional<JsonNode> opt = client.getFile(folderRef);
        if (opt.isEmpty() || opt.get().path("trashed").asBoolean(false)
                || !GoogleDriveDocumentBuilder.isFolder(opt.get())) {
            throw new IllegalArgumentException("No Google Drive folder '" + folderRef + "' in this connector's scope.");
        }
        JsonNode folder = opt.get();
        if (!isFolderInConfiguredScope(folder)) {
            throw new IllegalArgumentException("No Google Drive folder '" + folderRef + "' in this connector's scope.");
        }
        String folderId = folder.path("id").asText(folderRef);
        String name = folder.path("name").asText(folderId);
        String driveId = folder.path("driveId").asText(settings.driveId());
        SecurityConfig sec = resolveDiscoverySecurity(folderId);
        DiscoveryNode node = new DiscoveryNode(
                GoogleDriveDocumentBuilder.documentId(driveId, folderId),
                folderId,
                null,
                name,
                "/" + name,
                DiscoveryNode.NodeType.FOLDER,
                0,
                GoogleDriveProperties.MIME_FOLDER,
                null,
                GoogleDriveDocumentBuilder.parseInstant(GoogleDriveDocumentBuilder.text(folder, "modifiedTime")),
                null,
                sec
        );
        if (!VisibilityFilter.decide(filter, List.of(node))[0]) {
            throw new IllegalArgumentException("No Google Drive folder '" + folderRef + "' in this connector's scope.");
        }
        return node;
    }

    private boolean isFolderInConfiguredScope(JsonNode folder) throws IOException, InterruptedException {
        if (settings.hasDriveId()) {
            String itemDrive = GoogleDriveDocumentBuilder.text(folder, "driveId");
            if (itemDrive != null && !itemDrive.equals(settings.driveId().strip())) {
                return false;
            }
        }
        Set<String> rootSet = new LinkedHashSet<>(canonicalizeRoots(settings.effectiveRootFolderIds()));
        String folderId = folder.path("id").asText("");
        if (rootSet.contains(folderId)) {
            return true;
        }
        if (!settings.includeSubfolders()) {
            return false;
        }
        if (!settings.isFolderScoped()) {
            return true;
        }
        return ancestorMatchesRoots(firstParent(folder), rootSet);
    }

    private boolean isFileInConfiguredScope(JsonNode file) throws IOException, InterruptedException {
        if (settings.hasDriveId()) {
            String itemDrive = GoogleDriveDocumentBuilder.text(file, "driveId");
            if (itemDrive == null || !itemDrive.equals(settings.driveId().strip())) {
                return false;
            }
        }
        if (!settings.isFolderScoped()) {
            return true;
        }
        Set<String> rootSet = new LinkedHashSet<>(canonicalizeRoots(settings.effectiveRootFolderIds()));
        String parentId = firstParent(file);
        if (parentId == null || parentId.isBlank()) {
            return false;
        }
        if (!settings.includeSubfolders()) {
            return rootSet.contains(parentId);
        }
        return ancestorMatchesRoots(parentId, rootSet);
    }

    private boolean ancestorMatchesRoots(String startParentId, Set<String> rootSet)
            throws IOException, InterruptedException {
        String current = startParentId;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; current != null && !current.isBlank() && depth < MAX_ANCESTOR_DEPTH && visited.add(current); depth++) {
            if (rootSet.contains(current)) {
                return true;
            }
            Optional<JsonNode> opt = lookupFileMetadata(current);
            if (opt.isEmpty() || opt.get().path("trashed").asBoolean(false)) {
                return false;
            }
            current = firstParent(opt.get());
        }
        return false;
    }

    private boolean isDiscoveryDocumentAccepted(JsonNode item) {
        String origMime = GoogleDriveDocumentBuilder.text(item, "mimeType");
        if (GoogleDriveDocumentBuilder.isShortcut(item)) {
            return settings.followShortcuts();
        }
        ExportTarget target = null;
        if (GoogleDriveDocumentBuilder.isGoogleWorkspaceMime(origMime)) {
            if (!settings.exportGoogleDocs()) {
                return false;
            }
            Optional<ExportTarget> resolved = GoogleDriveDocumentBuilder.resolveExportTarget(origMime, settings);
            if (resolved.isEmpty()) {
                return false;
            }
            target = resolved.get();
        }
        return settings.acceptsMimeType(origMime, target != null ? target.mimeType() : null);
    }

    private DiscoveryNode toDiscoveryNode(JsonNode item, String parentId, String parentPath, int depth)
            throws InterruptedException {
        String itemId = item.path("id").asText();
        String name = item.path("name").asText(itemId);
        String driveId = item.path("driveId").asText(settings.driveId());
        boolean isFolder = GoogleDriveDocumentBuilder.isFolder(item);
        String origMime = GoogleDriveDocumentBuilder.text(item, "mimeType");
        String effectiveMime = origMime;
        if (!isFolder && GoogleDriveDocumentBuilder.isGoogleWorkspaceMime(origMime)) {
            effectiveMime = GoogleDriveDocumentBuilder.resolveExportTarget(origMime, settings)
                    .map(ExportTarget::mimeType).orElse(origMime);
        }
        String base = (parentPath == null || parentPath.isBlank() || "/".equals(parentPath)) ? "" : parentPath;
        String path = base + "/" + name;
        Long sizeBytes = !isFolder && item.hasNonNull("size") ? item.path("size").asLong() : null;
        Instant modified = GoogleDriveDocumentBuilder.parseInstant(GoogleDriveDocumentBuilder.text(item, "modifiedTime"));
        SecurityConfig security = resolveDiscoverySecurity(itemId);

        return new DiscoveryNode(
                GoogleDriveDocumentBuilder.documentId(driveId, itemId),
                itemId,
                parentId,
                name,
                path,
                isFolder ? DiscoveryNode.NodeType.FOLDER : DiscoveryNode.NodeType.DOCUMENT,
                depth,
                effectiveMime,
                sizeBytes,
                modified,
                null,
                security
        );
    }

    /**
     * Resolves a node's {@link SecurityConfig} for discovery. Returns {@code null} (fail-closed -> denied) whenever
     * permissions cannot be read from Google Drive.
     */
    private SecurityConfig resolveDiscoverySecurity(String fileId) throws InterruptedException {
        if (!settings.includeAcls()) {
            return SecurityConfig.createPublic();
        }
        try {
            return securityMapper.map(client.listPermissions(fileId)).security();
        } catch (IOException e) {
            log.warn("Could not resolve permissions for Google Drive item '{}' during discovery (failing closed): {}",
                    fileId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ Helpers

    private FileContext resolveFileContext(
            JsonNode file,
            String parentId,
            ExportTarget exportTarget,
            String shortcutSourceId,
            Map<String, FolderEntry> knownFolders,
            Set<String> rootIds
    ) throws IOException, InterruptedException {
        String driveId = GoogleDriveDocumentBuilder.text(file, "driveId");
        if (driveId == null || driveId.isBlank()) {
            driveId = settings.hasDriveId() ? settings.driveId().strip() : "";
        }
        String driveName = resolveDriveName(driveId);

        List<String> breadcrumbs = resolveBreadcrumbs(parentId, knownFolders, rootIds);
        String folderPath = breadcrumbs.isEmpty() ? "/" : "/" + String.join("/", breadcrumbs);
        String effectiveName = GoogleDriveDocumentBuilder.effectiveName(
                file.path("name").asText(file.path("id").asText("untitled")), exportTarget);
        String fullPath = "/".equals(folderPath) ? "/" + effectiveName : folderPath + "/" + effectiveName;
        return new FileContext(driveId, driveName, breadcrumbs, folderPath, fullPath, shortcutSourceId);
    }

    private List<String> resolveBreadcrumbs(
            String parentId,
            Map<String, FolderEntry> knownFolders,
            Set<String> rootIds
    ) throws IOException, InterruptedException {
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        List<String> fromState = GoogleDriveCrawlState.breadcrumbs(knownFolders, parentId);
        if (fromState != null) {
            return fromState;
        }
        // Walk up via Drive API with memoization when folder wasn't cached yet (e.g. incremental Changes API or fetchDocument).
        List<String> segments = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = parentId;
        for (int depth = 0; current != null && !current.isBlank() && depth < MAX_ANCESTOR_DEPTH && visited.add(current); depth++) {
            FolderEntry cached = knownFolders.get(current);
            if (cached != null) {
                if (cached.name() != null && !cached.name().isBlank()) {
                    segments.add(cached.name());
                }
                if (cached.parentId() == null || cached.parentId().isBlank()) {
                    break;
                }
                current = cached.parentId();
                continue;
            }
            Optional<JsonNode> opt = lookupFileMetadata(current);
            if (opt.isEmpty()) {
                break;
            }
            JsonNode folderNode = opt.get();
            String name = folderNode.path("name").asText("");
            String nextParent = firstParent(folderNode);
            boolean isRoot = rootIds.contains(current) || current.equals(resolvedMyDriveRootId);
            if (!name.isBlank() && !(isRoot && "My Drive".equalsIgnoreCase(name))) {
                segments.add(name);
            }
            if (isRoot || nextParent == null || nextParent.isBlank()) {
                break;
            }
            current = nextParent;
        }
        Collections.reverse(segments);
        return List.copyOf(segments);
    }

    private Optional<JsonNode> lookupFileMetadata(String fileId) throws IOException, InterruptedException {
        JsonNode cached = metadataLookupCache.get(fileId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<JsonNode> fetched = client.getFile(fileId);
        fetched.ifPresent(node -> metadataLookupCache.put(fileId, node));
        return fetched;
    }

    private String resolveCanonicalFolderId(String folderRef) throws IOException, InterruptedException {
        if ("root".equalsIgnoreCase(folderRef) && resolvedMyDriveRootId != null) {
            return resolvedMyDriveRootId;
        }
        return folderRef;
    }

    private String resolveRootFolderDisplayName(String rootRef, String canonicalId)
            throws IOException, InterruptedException {
        if ("root".equalsIgnoreCase(rootRef) || canonicalId.equals(resolvedMyDriveRootId)) {
            return "";
        }
        if (settings.hasDriveId() && canonicalId.equals(settings.driveId().strip())) {
            return "";
        }
        return lookupFileMetadata(canonicalId)
                .map(node -> node.path("name").asText(""))
                .orElse("");
    }

    private String resolveDriveName(String driveId) {
        if (driveId == null || driveId.isBlank()) {
            return "My Drive";
        }
        return driveNamesById.computeIfAbsent(driveId, id -> {
            try {
                return client.getDrive(id).path("name").asText(id);
            } catch (Exception e) {
                return id;
            }
        });
    }

    private static String firstParent(JsonNode file) {
        JsonNode parents = file.path("parents");
        if (parents.isArray() && !parents.isEmpty()) {
            String p = parents.get(0).asText(null);
            return p != null && !p.isBlank() ? p.strip() : null;
        }
        return null;
    }

    private static String escapeQuery(String literal) {
        return literal.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void emitTombstone(
            String docId,
            String driveId,
            String fileId,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) {
        emit(sink, GoogleDriveDocumentBuilder.buildTombstone(docId, driveId, fileId));
        crawl.tombstones.incrementAndGet();
    }

    private static void emit(FluxSink<RepositoryDocument> sink, RepositoryDocument document) {
        synchronized (sink) {
            if (!sink.isCancelled()) {
                sink.next(document);
            }
        }
    }
}
