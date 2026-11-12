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
package org.opencrawling.sharepoint;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.opencrawling.sharepoint.SharePointProperties.CrawlMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Microsoft SharePoint repository connector (issue #126): crawls the document libraries of a SharePoint Online /
 * Microsoft 365 site through the Microsoft Graph API v1.0, app-only (OAuth2 client credentials).
 *
 * <p>Crawl modes, per document library (drive):
 * <ul>
 *   <li>{@code DELTA} (default): the Graph delta query. The first run enumerates the library; later runs continue from the
 *       stored delta link and see only new, changed and deleted items. Deleted items ({@code deleted} facet) become
 *       DELETE tombstones, a deleted folder takes its known files with it, and a delta link Graph no longer accepts
 *       (410 Gone) restarts the enumeration and tombstones the files that did not come back.</li>
 *   <li>{@code TREE}: a folder traversal from {@code root-folder-path}. With state it is also the permission reconciliation
 *       pass: every file's permissions are read, and a file is emitted again only when its eTag or its ACL fingerprint
 *       changed (delta does not report permission-only changes without {@code Sites.FullControl.All}); files that vanished
 *       get tombstones after a complete listing.</li>
 * </ul>
 * Each file's content is downloaded (pre-authenticated download URL, size-capped) to a temporary file; its list item
 * columns and its permissions become OIS metadata and security (see {@link SharePointSecurityMapper}). Concurrency is
 * bounded by {@code parallelism}; Graph throttling is honoured through {@link GraphClient}.
 *
 * <p>As a Spring bean it is active with {@code spring.opencrawling.connector.type=sharepoint} and reads
 * {@code spring.opencrawling.connector.sharepoint.*}; jobs build it from their connector configuration instead.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "sharepoint")
public class SharePointRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(SharePointRepositoryConnector.class);

    /** Shown when connecting and by the connection check. */
    public static final String PREREQUISITES_NOTICE = "This connector needs a Microsoft Entra ID app registration with a client "
            + "secret and admin-consented Microsoft Graph application permission Sites.Read.All (or Sites.Selected with read "
            + "access to the site). See the Prerequisites in the connector's README.";

    private final SharePointProperties settings;
    private GraphClient client;
    private String siteId;
    private String host;
    private String siteWebUrl;
    private SharePointSecurityMapper mapper;

    /** Used by SPI discovery only. */
    public SharePointRepositoryConnector() {
        this(SharePointProperties.fromConfiguration(Map.of()));
    }

    @Autowired
    public SharePointRepositoryConnector(Environment environment) {
        this(SharePointProperties.fromEnvironment(environment));
    }

    public SharePointRepositoryConnector(SharePointProperties settings) {
        this.settings = settings;
    }

    SharePointRepositoryConnector(SharePointProperties settings, GraphClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public String getName() {
        return "SharePointRepositoryConnector";
    }

    @Override
    public String sourceInstance() {
        // configured by site-id only: the site's web URL is known once connect() resolved it
        return settings.siteUrl() != null ? settings.siteUrl() : siteWebUrl;
    }

    public SharePointProperties settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SharePoint repository connector is not configured: " + String.join(" ", problems)
                    + " " + PREREQUISITES_NOTICE);
        }
        if (client == null) {
            client = new GraphClient(settings);
        }
        JsonNode site = settings.siteId() != null ? client.getSite(settings.siteId())
                : client.getSiteByPath(settings.hostname(), settings.sitePath());
        siteId = site.path("id").asText(settings.siteId());
        siteWebUrl = site.path("webUrl").asText(settings.siteUrl());
        host = siteWebUrl != null ? URI.create(siteWebUrl).getHost() : siteId.split(",")[0];
        mapper = new SharePointSecurityMapper(settings, new SharePointPrincipals(settings, siteId));
        log.info("Connected to SharePoint site '{}' ({}) through {} ({}).", site.path("displayName").asText(siteWebUrl), siteId,
                settings.graphUrl(), settings);
    }

    @Override
    public synchronized void disconnect() {
        // app-only tokens are not sessions: nothing to end
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                connect();
                Crawl crawl = new Crawl();
                String path = basePath == null ? "" : basePath.strip();
                boolean override = !path.isEmpty() && !"default".equalsIgnoreCase(path) && !"/".equals(path);
                for (JsonNode drive : drives()) {
                    if (crawl.limitReached()) {
                        break;
                    }
                    Library library = new Library(drive.path("id").asText(), drive.path("name").asText());
                    if (override) {
                        log.info("A job path replaces the configured root folder, so this run is a non-incremental tree crawl.");
                        crawlTree(library, path, false, crawl, sink);
                    } else if (settings.crawlMode() == CrawlMode.TREE) {
                        crawlTree(library, settings.rootFolderPath(), settings.incremental(), crawl, sink);
                    } else {
                        crawlDelta(library, crawl, sink);
                    }
                }
                log.info("SharePoint scan of '{}' finished: {} documents, {} unchanged, {} tombstones, {} skipped, {} failed.",
                        siteWebUrl, crawl.documents, crawl.unchanged, crawl.tombstones, crawl.skipped, crawl.failed);
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("SharePoint scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                disconnect();
            }
        });
    }

    /** The document libraries to crawl: the configured id, the configured names, or every document library of the site. */
    List<JsonNode> drives() throws IOException, InterruptedException {
        if (settings.driveId() != null) {
            return List.of(client.getDrive(settings.driveId()));
        }
        List<JsonNode> libraries = client.listDrives(siteId).stream()
                .filter(d -> "documentLibrary".equalsIgnoreCase(d.path("driveType").asText("documentLibrary")))
                .toList();
        if (settings.driveNames().isEmpty()) {
            return libraries;
        }
        List<JsonNode> selected = new ArrayList<>();
        for (String name : settings.driveNames()) {
            JsonNode match = libraries.stream().filter(d -> d.path("name").asText().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The site has no document library named '" + name + "'; it has: "
                            + libraries.stream().map(d -> d.path("name").asText()).toList()));
            selected.add(match);
        }
        return selected;
    }

    private record Library(String id, String name) {
    }

    /** State of one scan. */
    private final class Crawl {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        /** False when a listing was cut short (max-documents). */
        volatile boolean complete = true;
        private int accepted;

        /** Counts one more file against {@code maxDocuments}; false once the limit is reached. */
        synchronized boolean admit() {
            if (limitReached()) {
                complete = false;
                return false;
            }
            accepted++;
            return true;
        }

        synchronized boolean limitReached() {
            return settings.maxDocuments() > 0 && accepted >= settings.maxDocuments();
        }
    }

    /** Per library: the crawl state, and whether list columns must be read item by item. */
    private final class Run {
        final Library library;
        final SharePointCrawlState state;
        final String pathPrefix;
        volatile boolean fieldsPerItem;
        /** List columns can't be read in this library (the per-item call answered 400, 403 or 404): files go without them. */
        volatile boolean fieldsUnavailable;
        /** Folder paths looked up from Graph when the state does not know the folder yet (delta pages omit paths). */
        final Map<String, String> folderPaths = new ConcurrentHashMap<>();

        Run(Library library, SharePointCrawlState state, String pathPrefix) {
            this.library = library;
            this.state = state;
            this.pathPrefix = pathPrefix;
        }
    }

    // ------------------------------------------------------------------ DELTA mode

    private void crawlDelta(Library library, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Path file = SharePointCrawlState.file(settings, library.id());
        SharePointCrawlState state = settings.incremental() ? SharePointCrawlState.load(file) : SharePointCrawlState.empty();
        Run run = new Run(library, state, "");
        boolean fields = settings.includeCustomFields();
        String url = state.deltaLink() != null ? state.deltaLink() : client.deltaUrl(library.id(), fields);
        boolean reconcile = false;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        String deltaLink = null;
        long failedBefore = crawl.failed.get();
        log.info("SharePoint delta crawl of library '{}' {}.", library.name(),
                state.deltaLink() != null ? "from the stored delta link" : "from the beginning (full enumeration)");
        while (url != null && !crawl.limitReached()) {
            GraphClient.Page page;
            try {
                page = client.page(url);
            } catch (GraphApiException e) {
                if (e.isResyncRequired()) {
                    log.warn("Graph no longer accepts the delta link of library '{}' ({}); enumerating it again.", library.name(), e.code());
                    url = e.location() != null ? e.location() : client.deltaUrl(library.id(), fields && !run.fieldsPerItem);
                    reconcile = true;
                    seen.clear();
                    continue;
                }
                if (e.status() == 400 && url.contains("$expand") && !run.fieldsPerItem) {
                    log.info("Graph refused list item columns in the delta query of '{}'; reading them item by item.", library.name());
                    run.fieldsPerItem = true;
                    url = stripExpand(url);
                    continue;
                }
                throw e;
            }
            applyDeltaPage(page.items(), run, seen, crawl, sink);
            if (page.nextLink() != null) {
                url = page.nextLink();
            } else {
                deltaLink = page.deltaLink();
                url = null;
            }
        }
        boolean complete = url == null && crawl.complete;
        if (reconcile && complete) {
            // a full enumeration after a 410: files we knew that did not come back are gone
            for (String id : state.fileIds()) {
                if (!seen.contains(id)) {
                    tombstone(run, id, sink, crawl);
                    state.remove(id);
                }
            }
        }
        if (settings.incremental()) {
            if (complete && crawl.failed.get() == failedBefore && deltaLink != null) {
                state.deltaLink(deltaLink);
            } else {
                log.info("Delta crawl of '{}' incomplete or with failures: the delta link is not advanced.", library.name());
            }
            state.save(file);
        }
    }

    /** Applies a delta page: the root, folders and deletions first (they shape paths and the state), then the files. */
    private void applyDeltaPage(List<JsonNode> items, Run run, Set<String> seen, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws InterruptedException {
        SharePointCrawlState state = run.state;
        List<JsonNode> files = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText(null);
            if (id == null) {
                continue;
            }
            seen.add(id);
            if (item.has("deleted") || item.has("@removed")) {
                SharePointCrawlState.Item known = state.get(id);
                if (known != null && known.folder()) {
                    for (String descendant : state.descendants(id)) {
                        SharePointCrawlState.Item d = state.get(descendant);
                        if (d != null && !d.folder()) {
                            tombstone(run, descendant, sink, crawl);
                        }
                        state.remove(descendant);
                    }
                } else if (!item.has("folder")) {
                    tombstone(run, id, sink, crawl);
                }
                state.remove(id);
            } else if (item.has("root")) {
                state.put(id, new SharePointCrawlState.Item(null, "", true, GraphClient.text(item, "eTag"), null));
            } else if (item.has("folder")) {
                state.put(id, new SharePointCrawlState.Item(parentId(item), item.path("name").asText(""), true,
                        GraphClient.text(item, "eTag"), null));
            } else if (item.has("file")) {
                files.add(item);
            } else {
                crawl.skipped.incrementAndGet(); // notebooks (package), remote items
            }
        }
        processFiles(files, run, crawl, sink);
    }

    static String stripExpand(String url) {
        return url.replaceAll("([?&])\\$expand=[^&]*&?", "$1").replaceAll("[?&]$", "");
    }

    // ------------------------------------------------------------------ TREE mode

    private void crawlTree(Library library, String rootPath, boolean incremental, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        Path file = SharePointCrawlState.file(settings, library.id());
        SharePointCrawlState previous = incremental ? SharePointCrawlState.load(file) : SharePointCrawlState.empty();
        String prefix = rootPath == null || rootPath.isBlank() || "/".equals(rootPath.strip()) ? ""
                : "/" + rootPath.strip().replaceAll("^/+|/+$", "");
        Run run = new Run(library, previous, prefix);
        JsonNode root = client.getItemByPath(library.id(), rootPath);
        String rootId = root.path("id").asText();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        previous.put(rootId, new SharePointCrawlState.Item(null, "", true, GraphClient.text(root, "eTag"), null));
        long failedBefore = crawl.failed.get();
        log.info("SharePoint tree crawl of library '{}' from '{}'{}.", library.name(), prefix.isEmpty() ? "/" : prefix,
                incremental ? " (incremental: unchanged files and unchanged permissions are skipped)" : "");
        ArrayDeque<String> folders = new ArrayDeque<>(List.of(rootId));
        boolean fields = settings.includeCustomFields();
        while (!folders.isEmpty() && !crawl.limitReached()) {
            String folderId = folders.poll();
            String url = client.childrenUrl(library.id(), folderId, fields && !run.fieldsPerItem);
            while (url != null && !crawl.limitReached()) {
                GraphClient.Page page;
                try {
                    page = client.page(url);
                } catch (GraphApiException e) {
                    if (e.status() == 400 && url.contains("$expand") && !run.fieldsPerItem) {
                        run.fieldsPerItem = true;
                        url = stripExpand(url);
                        continue;
                    }
                    throw e;
                }
                List<JsonNode> files = new ArrayList<>();
                for (JsonNode item : page.items()) {
                    String id = item.path("id").asText();
                    seen.add(id);
                    if (item.has("folder")) {
                        previous.put(id, new SharePointCrawlState.Item(folderId, item.path("name").asText(""), true,
                                GraphClient.text(item, "eTag"), null));
                        if (settings.includeSubfolders()) {
                            folders.add(id);
                        }
                    } else if (item.has("file")) {
                        files.add(item);
                    } else {
                        crawl.skipped.incrementAndGet();
                    }
                }
                processFiles(files, run, crawl, sink);
                url = page.nextLink();
            }
        }
        boolean complete = folders.isEmpty() && crawl.complete && crawl.failed.get() == failedBefore;
        if (incremental) {
            if (complete) {
                for (String id : previous.fileIds()) {
                    if (!seen.contains(id)) {
                        tombstone(run, id, sink, crawl);
                        previous.remove(id);
                    }
                }
            } else {
                log.info("Tree crawl of '{}' incomplete or with failures: no deletes are inferred in this run.", library.name());
            }
            previous.save(file);
        }
    }

    // ------------------------------------------------------------------ files

    @SuppressWarnings("preview")
    private void processFiles(List<JsonNode> files, Run run, Crawl crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        if (files.isEmpty()) {
            return;
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode file : files) {
                if (!crawl.admit()) {
                    break;
                }
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handleFile(file, run, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("SharePoint item {} ({}) could not be crawled: {}", file.path("id").asText("?"),
                                file.path("name").asText("?"), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private void handleFile(JsonNode item, Run run, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        String id = item.path("id").asText();
        String driveId = run.library.id();
        SharePointCrawlState.Item known = run.state.get(id);
        String eTag = GraphClient.text(item, "eTag");
        String parent = parentId(item);
        SharePointSecurityMapper.Mapped security = settings.includeAcls()
                ? mapper.map(client.getPermissions(driveId, id))
                : SharePointSecurityMapper.publicAccess();
        SharePointCrawlState.Item now = new SharePointCrawlState.Item(parent, item.path("name").asText(""), false, eTag,
                security.fingerprint());
        if (known != null && eTag != null && eTag.equals(known.eTag()) && security.fingerprint().equals(known.acl())) {
            run.state.put(id, now);
            crawl.unchanged.incrementAndGet();
            return;
        }
        JsonNode fields = item.path("listItem").path("fields");
        if (settings.includeCustomFields() && !fields.isObject() && run.fieldsPerItem && !run.fieldsUnavailable) {
            try {
                fields = client.getListItemFields(driveId, id);
            } catch (GraphApiException e) {
                if (e.status() != 400 && e.status() != 403 && e.status() != 404) {
                    throw e;
                }
                // columns are metadata: without them the file is still indexed, with its content and permissions
                run.fieldsUnavailable = true;
                log.warn("List columns of library '{}' can't be read (HTTP {}); its files are indexed without them.",
                        run.library.name(), e.status());
            }
        }
        Download download = download(driveId, item);
        RepositoryDocument document;
        try {
            SharePointDocumentBuilder.Context context = new SharePointDocumentBuilder.Context(host, siteId, siteWebUrl, driveId,
                    run.library.name(), path(run, item, parent, client));
            document = SharePointDocumentBuilder.build(context, item, fields.isObject() ? fields : null,
                    settings.includeCustomFields(), settings.fieldPrefix(), download.stream(), download.length(), security);
        } catch (RuntimeException e) {
            download.close();
            throw e;
        }
        emit(sink, document);
        crawl.documents.incrementAndGet();
        run.state.put(id, now);
    }

    /**
     * The item's path in the library: its folder from the known folders, else from {@code parentReference.path}, else
     * (delta pages omit paths and come in no parent-first order) from the parent folder looked up once per run.
     */
    private static String path(Run run, JsonNode item, String parent, GraphClient client) throws IOException, InterruptedException {
        String folder = parent == null ? null : run.state.folderPath(parent);
        if (folder != null) {
            folder = run.pathPrefix + ("/".equals(folder) ? "" : folder);
        } else {
            folder = referencePath(item);
            if (folder == null && parent != null) {
                folder = run.folderPaths.get(parent);
                if (folder == null) {
                    JsonNode parentItem = client.getItem(run.library.id(), parent);
                    folder = parentItem.has("root") ? "" : referencePath(parentItem) + "/" + parentItem.path("name").asText("");
                    folder = folder.replaceFirst("^/+", "/");
                    run.folderPaths.put(parent, folder);
                }
            }
        }
        if (folder == null) {
            return null;
        }
        String name = item.path("name").asText("");
        return (folder.isEmpty() || "/".equals(folder) ? "" : folder) + "/" + name;
    }

    /** {@code /drives/{id}/root:/Folder/Sub} → {@code /Folder/Sub}; {@code ""} for the root; null when absent. */
    static String referencePath(JsonNode item) {
        String reference = item.path("parentReference").path("path").asText(null);
        if (reference == null) {
            return null;
        }
        int root = reference.indexOf("root:");
        return root < 0 ? null : java.net.URLDecoder.decode(reference.substring(root + 5), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String parentId(JsonNode item) {
        return GraphClient.text(item.path("parentReference"), "id");
    }

    private record Download(InputStream stream, long length) {
        static final Download NONE = new Download(null, -1);

        void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private Download download(String driveId, JsonNode item) throws IOException, InterruptedException {
        String id = item.path("id").asText();
        if (!settings.includeContentStream()) {
            return Download.NONE;
        }
        long size = item.path("size").asLong(-1);
        if (size > settings.maxFileSizeBytes()) {
            log.info("SharePoint item {} ({} bytes) exceeds max-file-size-bytes; metadata only.", id, size);
            return Download.NONE;
        }
        Path temp = Files.createTempFile("oc-sharepoint-", ".bin");
        try {
            long length = client.download(driveId, id, GraphClient.text(item, "@microsoft.graph.downloadUrl"), temp,
                    settings.maxFileSizeBytes());
            if (length < 0) {
                Files.deleteIfExists(temp);
                log.info("SharePoint item {} exceeds max-file-size-bytes; metadata only.", id);
                return Download.NONE;
            }
            return new Download(new TempFileInputStream(temp), length);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private void tombstone(Run run, String itemId, FluxSink<RepositoryDocument> sink, Crawl crawl) {
        emit(sink, SharePointDocumentBuilder.tombstone(SharePointDocumentBuilder.documentId(host, run.library.id(), itemId),
                run.library.id(), itemId));
        crawl.tombstones.incrementAndGet();
    }

    private static void emit(FluxSink<RepositoryDocument> sink, RepositoryDocument document) {
        synchronized (sink) {
            sink.next(document);
        }
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        String prefix = settings.fieldPrefix();
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("sharepoint.itemId", "STRING", "Drive item id"),
                new ConnectorSchema.SchemaField("sharepoint.name", "STRING", "File name"),
                new ConnectorSchema.SchemaField("sharepoint.webUrl", "STRING", "URL of the file in SharePoint"),
                new ConnectorSchema.SchemaField("sharepoint.path", "STRING", "Path of the file in its library"),
                new ConnectorSchema.SchemaField("sharepoint.siteId", "STRING", "Site id"),
                new ConnectorSchema.SchemaField("sharepoint.siteUrl", "STRING", "Site URL"),
                new ConnectorSchema.SchemaField("sharepoint.driveId", "STRING", "Document library (drive) id"),
                new ConnectorSchema.SchemaField("sharepoint.driveName", "STRING", "Document library name"),
                new ConnectorSchema.SchemaField("sharepoint.size", "LONG", "File size in bytes"),
                new ConnectorSchema.SchemaField("sharepoint.createdDateTime", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("sharepoint.createdBy", "STRING", "Created by"),
                new ConnectorSchema.SchemaField("sharepoint.lastModifiedDateTime", "TIMESTAMP", "Last modification timestamp"),
                new ConnectorSchema.SchemaField("sharepoint.lastModifiedBy", "STRING", "Last modified by"),
                new ConnectorSchema.SchemaField("sharepoint.etag", "STRING", "eTag of the item"),
                new ConnectorSchema.SchemaField("sharepoint.cTag", "STRING", "cTag of the content"),
                new ConnectorSchema.SchemaField(prefix + "<InternalName>", "STRING",
                        "Every list item column of the library, by internal column name")
        ));
    }
}
