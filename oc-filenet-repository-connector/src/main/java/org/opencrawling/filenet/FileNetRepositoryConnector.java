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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.filenet.FileNetProperties.DeletionTracking;
import org.opencrawling.filenet.FileNetProperties.VersionMode;
import org.opencrawling.observability.concurrency.ObservabilityTask;
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
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * IBM FileNet P8 repository connector (issue #125): crawls one object store through the FileNet Content Services GraphQL
 * API.
 *
 * <p>Every crawl is a {@code documents(from, where)} search per configured document class, paged with
 * {@code moreDocuments(token)}: the class's documents (subclasses included) that match the version mode
 * ({@code [IsCurrentVersion] = True}, {@code [VersionStatus] = 1}, or every version), the configured CE SQL condition and,
 * in {@code folder} crawl mode, {@code This INSUBFOLDER '<root-folder-path>'} ({@code INFOLDER} without subfolders). A
 * search returns each document with its content elements, folder filings, effective permissions and class properties in
 * one round trip. Content elements are downloaded to temporary files (deleted when the stream is closed), one OIS document
 * per element.
 *
 * <p>Incremental crawls (the default) search {@code [DateLastModified] > <watermark>} and skip documents whose version,
 * update sequence number and permissions are unchanged. Deletions are found by {@code deletion-tracking}: {@code reconcile}
 * lists the current ids ({@code repositoryRows}) and compares them with the crawl state, {@code audit} reads
 * {@code DeletionEvent}s (deletion auditing must be enabled), and a full crawl always tombstones what it no longer finds. A
 * document whose tracked version disappeared is looked up again first: when another version became current it is
 * re-indexed, otherwise it gets a DELETE tombstone.
 *
 * <p>As a Spring bean it is active with {@code spring.opencrawling.connector.type=filenet} and reads
 * {@code spring.opencrawling.connector.filenet.*}; jobs build it from their connector configuration instead.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "filenet")
public class FileNetRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(FileNetRepositoryConnector.class);

    /** Shown when connecting and by the connection check: OpenCrawling ships no FileNet licence. */
    public static final String LICENCE_NOTICE = "This connector requires a licensed IBM FileNet P8 Content Platform Engine with "
            + "the Content Services GraphQL API deployed, and an account with read access to the object store; OpenCrawling "
            + "does not include IBM FileNet or any IBM licence. See the Prerequisites in the connector's README.";

    private static final DateTimeFormatter CE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final FileNetProperties settings;
    private final FileNetDocumentBuilder builder;
    private final FileNetSecurityMapper securityMapper;
    private FileNetClient client;
    private String objectStoreName;

    /** Used by SPI discovery only. */
    public FileNetRepositoryConnector() {
        this(FileNetProperties.fromConfiguration(Map.of()));
    }

    @Autowired
    public FileNetRepositoryConnector(Environment environment) {
        this(FileNetProperties.fromEnvironment(environment));
    }

    public FileNetRepositoryConnector(FileNetProperties settings) {
        this(settings, null);
    }

    FileNetRepositoryConnector(FileNetProperties settings, FileNetClient client) {
        this.settings = settings;
        this.client = client;
        this.builder = new FileNetDocumentBuilder(settings);
        this.securityMapper = new FileNetSecurityMapper(settings);
    }

    @Override
    public String getName() {
        return "FileNetRepositoryConnector";
    }

    public FileNetProperties settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info(LICENCE_NOTICE);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("FileNet repository connector is not configured: " + String.join(" ", problems)
                    + " " + LICENCE_NOTICE);
        }
        if (client == null) {
            client = newClient(settings);
        }
        JsonNode info = connectionInfo(client, settings);
        objectStoreName = info.path("objectStore").path("symbolicName").asText(settings.objectStore());
        log.info("Connected to FileNet object store '{}' through Content Services GraphQL {} at {} ({}).", objectStoreName,
                info.path("_apiInfo").path("productVersion").asText("?"), client.getEndpoint(), settings);
    }

    /**
     * The API and object store information ({@code _apiInfo}, {@code objectStore}); fails when the endpoint, the
     * credentials or the object store are wrong. Also used by the connection check.
     */
    public static JsonNode connectionInfo(FileNetClient client, FileNetProperties settings) throws IOException, InterruptedException {
        JsonNode data = client.execute("connect", Map.of("os", settings.objectStore()));
        if (data.path("objectStore").isMissingNode() || data.path("objectStore").isNull()) {
            throw new FileNetApiException(200, "FileNet object store '" + settings.objectStore() + "' was not found.");
        }
        return data;
    }

    /** A client for these settings; {@code oauth2} without an access token uses the client-credentials grant. */
    public static FileNetClient newClient(FileNetProperties settings) {
        Supplier<String> bearer = null;
        if (settings.authType().equals("bearer") || settings.authType().equals("oauth2")) {
            bearer = settings.oauth2AccessToken() != null ? settings::oauth2AccessToken
                    : new FileNetOAuth2TokenSource(settings.oauth2TokenUrl(), settings.oauth2ClientId(),
                            settings.oauth2ClientSecret(), settings.oauth2Scope(), settings.timeout());
        }
        // redirects are not followed: credentials must not travel to another host
        return new FileNetClient(settings, settings.timeout(), settings.maxRetries(), Duration.ofMillis(500),
                HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), bearer);
    }

    @Override
    public synchronized void disconnect() {
        // the API is stateless: nothing to close
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                connect();
                String path = basePath == null ? "" : basePath.strip();
                boolean override = path.startsWith("/") && !path.equals("/");
                Crawl crawl = new Crawl();
                if (override) {
                    log.info("The job path {} replaces the configured scope; this run is a full, non-incremental crawl of it.",
                            path);
                    crawlScope(path, crawl, sink);
                } else if (settings.incrementalEnabled()) {
                    crawlIncrementally(crawl, sink);
                } else {
                    crawlScope(null, crawl, sink);
                }
                log.info("FileNet scan of object store '{}' finished: {} documents, {} unchanged, {} tombstones, {} skipped, "
                                + "{} failed.", objectStoreName, crawl.documents, crawl.unchanged, crawl.tombstones, crawl.skipped,
                        crawl.failed);
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("FileNet scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                disconnect();
            }
        });
    }

    /** State of one scan. */
    private final class Crawl {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicBoolean unknownMarkingsLogged = new AtomicBoolean();
        /** The previous run's objects (incremental), the entries recorded by this run, and the keys handled. */
        Map<String, FileNetCrawlState.Entry> previous = Map.of();
        final Map<String, FileNetCrawlState.Entry> next = new ConcurrentHashMap<>();
        final Set<String> handled = ConcurrentHashMap.newKeySet();
        /** False when a listing was cut short (max-documents). */
        volatile boolean complete = true;
        private int accepted;

        synchronized boolean admit() {
            if (settings.maxDocuments() > 0 && accepted >= settings.maxDocuments()) {
                complete = false;
                return false;
            }
            accepted++;
            return true;
        }
    }

    // ------------------------------------------------------------------ searches

    /** The CE SQL conditions of the configured scope, without the incremental watermark: one (from, where) per class. */
    List<String[]> searches(String folderOverride) {
        List<String> clauses = new ArrayList<>();
        switch (settings.versionMode()) {
            case CURRENT -> clauses.add("[IsCurrentVersion] = True");
            case RELEASED -> clauses.add("[VersionStatus] = 1");
            case ALL -> {
            }
        }
        String folder = folderOverride != null ? folderOverride
                : settings.crawlMode() == FileNetProperties.CrawlMode.FOLDER ? settings.rootFolderPath() : null;
        if (folder != null && !folder.equals("/")) {
            clauses.add("This " + (settings.includeSubfolders() ? "INSUBFOLDER" : "INFOLDER") + " '"
                    + folder.replace("'", "''") + "'");
        }
        if (settings.where() != null) {
            clauses.add("(" + settings.where() + ")");
        }
        settings.modifiedAfterInstant().ifPresent(i -> clauses.add("[DateLastModified] >= " + ceTimestamp(i)));
        List<String[]> searches = new ArrayList<>();
        Optional<String[]> sql = settings.parsedSql();
        if (sql.isPresent()) {
            List<String> all = new ArrayList<>(clauses);
            if (sql.get()[1] != null) {
                all.add("(" + sql.get()[1] + ")");
            }
            searches.add(new String[]{sql.get()[0], String.join(" AND ", all)});
        } else {
            for (String cls : settings.documentClasses()) {
                searches.add(new String[]{cls, String.join(" AND ", clauses)});
            }
        }
        return searches;
    }

    /** A CE SQL timestamp literal (unquoted), e.g. {@code 20260101T000000Z}. */
    static String ceTimestamp(Instant instant) {
        return CE_TIMESTAMP.format(instant);
    }

    /** A full crawl of the scope: everything the previous state knows and this run no longer finds is tombstoned. */
    private void crawlScope(String folderOverride, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        for (String[] search : searches(folderOverride)) {
            search(search[0], search[1], crawl, sink);
        }
    }

    private void crawlIncrementally(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Path file = FileNetCrawlState.file(settings);
        FileNetCrawlState.State state = FileNetCrawlState.load(file);
        crawl.previous = state.objects();
        Instant start = Instant.now();
        Optional<Instant> watermark = Optional.ofNullable(state.watermark()).map(Instant::parse);
        log.info("FileNet incremental crawl: {} known documents, {}.", crawl.previous.size(),
                watermark.map(w -> "changes after " + w).orElse("first run, full crawl"));
        for (String[] search : searches(null)) {
            String where = search[1];
            if (watermark.isPresent()) {
                String since = "[DateLastModified] > " + ceTimestamp(watermark.get());
                where = where.isEmpty() ? since : where + " AND " + since;
            }
            search(search[0], where, crawl, sink);
        }
        String eventWatermark = state.eventWatermark();
        boolean ok = crawl.failed.get() == 0 && crawl.complete;
        if (ok) {
            if (watermark.isEmpty()) {
                tombstoneUnseen(crawl, sink); // a full run: whatever it did not find is gone
            } else if (settings.deletionTracking() == DeletionTracking.RECONCILE) {
                reconcile(crawl, sink);
            } else if (settings.deletionTracking() == DeletionTracking.AUDIT) {
                eventWatermark = readDeletionEvents(Optional.ofNullable(eventWatermark).map(Instant::parse)
                        .orElse(watermark.get()), crawl, sink);
            }
        }
        // entries this run did not touch carry over; a failed run keeps the previous watermark
        Map<String, FileNetCrawlState.Entry> objects = new LinkedHashMap<>(crawl.previous);
        objects.keySet().removeIf(k -> crawl.handled.contains(k) && !crawl.next.containsKey(k));
        objects.putAll(crawl.next);
        String nextWatermark = crawl.failed.get() == 0 && crawl.complete
                ? start.minusSeconds(settings.clockSkewSeconds()).toString() : state.watermark();
        if (eventWatermark == null && watermark.isEmpty() && ok) {
            eventWatermark = start.minusSeconds(settings.clockSkewSeconds()).toString();
        }
        FileNetCrawlState.save(file, new FileNetCrawlState.State(nextWatermark, eventWatermark, objects));
    }

    /** One paged search: {@code documents(from, where)} then {@code moreDocuments(token)}. */
    private void search(String from, String where, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        log.info("FileNet search: FROM {} WHERE {} (page {}).", from, where.isEmpty() ? "(all)" : where, settings.batchSize());
        Map<String, Object> variables = flags();
        variables.put("os", settings.objectStore());
        variables.put("from", from);
        variables.put("where", where.isEmpty() ? null : where);
        variables.put("pageSize", settings.batchSize());
        JsonNode set = client.execute("documents", variables).path("documents");
        while (true) {
            List<JsonNode> docs = new ArrayList<>();
            set.path("documents").forEach(docs::add);
            process(docs, crawl, sink);
            String token = set.path("pageInfo").path("token").asText("");
            if (token.isEmpty() || !crawl.complete) {
                return;
            }
            Map<String, Object> more = flags();
            more.put("token", token);
            set = client.execute("more-documents", more).path("moreDocuments");
        }
    }

    private Map<String, Object> flags() {
        Map<String, Object> v = new HashMap<>();
        v.put("withFolders", settings.includeFolderPaths());
        v.put("withAcls", settings.includeAcls());
        v.put("withProperties", settings.includeCustomProperties());
        v.put("withMarkings", settings.markedDocuments() == FileNetProperties.MarkedDocuments.EXCLUDE);
        return v;
    }

    // ------------------------------------------------------------------ documents

    @SuppressWarnings("preview")
    private void process(List<JsonNode> docs, Crawl crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode doc : docs) {
                if (!crawl.admit()) {
                    log.info("FileNet scan stopped at max-documents={}.", settings.maxDocuments());
                    break;
                }
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handle(doc, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("FileNet document {} could not be crawled: {}", doc.path("id").asText(), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private void handle(JsonNode doc, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        String key = builder.stateKey(doc);
        crawl.handled.add(key);
        if (settings.markedDocuments() == FileNetProperties.MarkedDocuments.EXCLUDE) {
            FileNetSecurityMapper.Markings markings = FileNetSecurityMapper.markings(doc);
            if (markings != FileNetSecurityMapper.Markings.NONE) {
                if (markings == FileNetSecurityMapper.Markings.UNKNOWN && crawl.unknownMarkingsLogged.compareAndSet(false, true)) {
                    log.warn("FileNet document {} did not report its ActiveMarkings; documents whose markings cannot be read "
                            + "are left out (marked-documents=exclude).", doc.path("id").asText());
                }
                crawl.skipped.incrementAndGet();
                FileNetCrawlState.Entry known = crawl.previous.get(key);
                if (known != null) {
                    tombstone(key, known, crawl, sink); // a marking applied since the last run: out of the index
                }
                return;
            }
        }
        FileNetSecurityMapper.Mapped security = securityMapper.map(doc.path("permissions"));
        String versionId = doc.path("id").asText();
        int usn = doc.path("updateSequenceNumber").asInt(-1);
        FileNetCrawlState.Entry known = crawl.previous.get(key);
        if (known != null && known.versionId().equalsIgnoreCase(versionId) && known.updateSequenceNumber() == usn
                && known.acl().equals(security.fingerprint())) {
            crawl.unchanged.incrementAndGet();
            crawl.next.put(key, known);
            return;
        }
        List<String> folders = folderPaths(doc);
        List<JsonNode> elements = new ArrayList<>();
        doc.path("contentElements").forEach(elements::add);
        List<String> ids = new ArrayList<>();
        if (elements.isEmpty()) {
            RepositoryDocument d = builder.build(doc, null, 0, null, folders, security);
            sink.next(d);
            ids.add(d.id());
        } else {
            for (JsonNode element : elements) {
                InputStream content = settings.includeContentStream() ? content(doc, element) : null;
                RepositoryDocument d = builder.build(doc, element, elements.size(), content, folders, security);
                sink.next(d);
                ids.add(d.id());
            }
        }
        crawl.documents.incrementAndGet();
        if (known != null) {
            for (String old : known.ids()) {
                if (!ids.contains(old)) { // a content element that is gone
                    sink.next(FileNetDocumentBuilder.tombstone(old, settings.objectStore(), versionId));
                    crawl.tombstones.incrementAndGet();
                }
            }
        }
        crawl.next.put(key, new FileNetCrawlState.Entry(versionId, usn, security.fingerprint(), List.copyOf(ids)));
    }

    /** An element's content as a temporary file; null for a content reference or content over the size limit. */
    private InputStream content(JsonNode doc, JsonNode element) throws IOException, InterruptedException {
        String url = element.path("downloadUrl").asText("");
        if (url.isEmpty()) {
            return null; // a ContentReference: only the location is indexed
        }
        long max = settings.maxContentSizeBytes();
        if (max > 0 && element.path("contentSize").asLong(0) > max) {
            log.info("FileNet document {} element {} is larger than {} bytes; indexed by metadata only.",
                    doc.path("id").asText(), element.path("elementSequenceNumber").asInt(), max);
            return null;
        }
        Optional<Path> file = client.download(url, max);
        if (file.isEmpty()) {
            log.info("FileNet document {} element {} exceeded {} bytes while downloading; indexed by metadata only.",
                    doc.path("id").asText(), element.path("elementSequenceNumber").asInt(), max);
            return null;
        }
        return new TempFileInputStream(file.get());
    }

    /** The folders a document is filed in; a list longer than one page is continued with {@code moreFolders}. */
    private List<String> folderPaths(JsonNode doc) throws IOException, InterruptedException {
        List<String> paths = new ArrayList<>();
        JsonNode set = doc.path("foldersFiledIn");
        while (set.isObject()) {
            set.path("folders").forEach(f -> {
                String p = f.path("pathName").asText("");
                if (!p.isEmpty()) {
                    paths.add(p);
                }
            });
            String token = set.path("pageInfo").path("token").asText("");
            if (token.isEmpty()) {
                break;
            }
            set = client.execute("more-folders", Map.of("token", token)).path("moreFolders");
        }
        return paths;
    }

    // ------------------------------------------------------------------ deletions

    /** A full run: every known document this run did not find is tombstoned. */
    private void tombstoneUnseen(Crawl crawl, FluxSink<RepositoryDocument> sink) {
        for (Map.Entry<String, FileNetCrawlState.Entry> e : crawl.previous.entrySet()) {
            if (!crawl.handled.contains(e.getKey())) {
                tombstone(e.getKey(), e.getValue(), crawl, sink);
            }
        }
    }

    private void tombstone(String key, FileNetCrawlState.Entry entry, Crawl crawl, FluxSink<RepositoryDocument> sink) {
        for (String id : entry.ids()) {
            sink.next(FileNetDocumentBuilder.tombstone(id, settings.objectStore(), entry.versionId()));
            crawl.tombstones.incrementAndGet();
        }
        crawl.handled.add(key);
        crawl.next.remove(key);
    }

    /**
     * Incremental {@code reconcile} tracking: the versions in scope now ({@code SELECT [Id]}) against the crawl state; a
     * known document whose version is no longer listed is looked up again.
     */
    private void reconcile(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Set<String> current = new HashSet<>();
        for (String[] search : searches(null)) {
            String sql = "SELECT [Id] FROM " + search[0] + (search[1].isEmpty() ? "" : " WHERE " + search[1]);
            for (JsonNode row : rows(sql)) {
                value(row, "Id").ifPresent(id -> current.add(id.toUpperCase(java.util.Locale.ROOT)));
            }
        }
        List<String> vanished = new ArrayList<>();
        for (Map.Entry<String, FileNetCrawlState.Entry> e : crawl.previous.entrySet()) {
            if (!crawl.handled.contains(e.getKey())
                    && !current.contains(e.getValue().versionId().toUpperCase(java.util.Locale.ROOT))) {
                vanished.add(e.getKey());
            }
        }
        log.info("FileNet reconciliation: {} versions in scope, {} known documents no longer listed.", current.size(),
                vanished.size());
        recheck(vanished, crawl, sink);
    }

    /**
     * Incremental {@code audit} tracking: {@code DeletionEvent}s since the event watermark; a known document whose
     * version was deleted is looked up again. Returns the new event watermark.
     */
    private String readDeletionEvents(Instant since, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String sql = settings.deletionEventSql().replace("{since}", ceTimestamp(since));
        Map<String, String> byVersion = new HashMap<>();
        crawl.previous.forEach((k, e) -> byVersion.put(e.versionId().toUpperCase(java.util.Locale.ROOT), k));
        Set<String> affected = new LinkedHashSet<>();
        Instant last = since;
        for (JsonNode row : rows(sql)) {
            Optional<String> source = value(row, "SourceObjectId");
            if (source.isPresent() && byVersion.containsKey(source.get().toUpperCase(java.util.Locale.ROOT))) {
                String key = byVersion.get(source.get().toUpperCase(java.util.Locale.ROOT));
                if (!crawl.handled.contains(key)) {
                    affected.add(key);
                }
            }
            Optional<Instant> created = value(row, "DateCreated").flatMap(FileNetDocumentBuilder::instant);
            if (created.isPresent() && created.get().isAfter(last)) {
                last = created.get();
            }
        }
        log.info("FileNet audit: deletion events since {} affect {} known documents.", since, affected.size());
        recheck(new ArrayList<>(affected), crawl, sink);
        return last.toString();
    }

    /**
     * Documents whose tracked version is gone: in {@code all} mode the version itself was deleted; otherwise the series
     * is searched again, and either its version in scope is re-indexed or the document is tombstoned.
     */
    private void recheck(List<String> keys, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        for (String key : keys) {
            FileNetCrawlState.Entry entry = crawl.previous.get(key);
            if (settings.versionMode() == VersionMode.ALL) {
                tombstone(key, entry, crawl, sink);
                continue;
            }
            List<JsonNode> found = new ArrayList<>();
            for (String[] search : searches(null)) {
                String where = "[VersionSeries] = " + key + (search[1].isEmpty() ? "" : " AND " + search[1]);
                Map<String, Object> variables = flags();
                variables.put("os", settings.objectStore());
                variables.put("from", search[0]);
                variables.put("where", where);
                variables.put("pageSize", 1);
                client.execute("documents", variables).path("documents").path("documents").forEach(found::add);
                if (!found.isEmpty()) {
                    break;
                }
            }
            if (found.isEmpty()) {
                tombstone(key, entry, crawl, sink);
            } else {
                process(found, crawl, sink); // another version is in scope now
            }
        }
    }

    /** Every row of a {@code repositoryRows} SQL query, paged with {@code moreRepositoryRows}. */
    private List<JsonNode> rows(String sql) throws IOException, InterruptedException {
        List<JsonNode> rows = new ArrayList<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("os", settings.objectStore());
        variables.put("sql", sql);
        variables.put("pageSize", Math.max(settings.batchSize(), 500));
        JsonNode set = client.execute("rows", variables).path("repositoryRows");
        while (true) {
            set.path("repositoryRows").forEach(rows::add);
            String token = set.path("pageInfo").path("token").asText("");
            if (token.isEmpty()) {
                return rows;
            }
            set = client.execute("more-rows", Map.of("token", token)).path("moreRepositoryRows");
        }
    }

    private static Optional<String> value(JsonNode row, String property) {
        for (JsonNode p : row.path("properties")) {
            if (p.path("id").asText().equalsIgnoreCase(property) && !p.path("value").isNull()
                    && !p.path("value").isMissingNode()) {
                return Optional.of(p.path("value").asText());
            }
        }
        return Optional.empty();
    }
}
