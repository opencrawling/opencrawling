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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.documentum.DocumentumDql.Cursor;
import org.opencrawling.documentum.DocumentumProperties.DeletionTracking;
import org.opencrawling.documentum.DocumentumProperties.VersionMode;
import org.opencrawling.documentum.DocumentumSecurityMapper.Acl;
import org.opencrawling.documentum.DocumentumSecurityMapper.Principal;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenText Documentum repository connector (issue #124): crawls one repository through Documentum REST Services.
 *
 * <p>Every crawl is a DQL listing per configured type ({@code SELECT <listing attributes> FROM <type>}, subtypes included,
 * with {@code (ALL)} unless only CURRENT versions are crawled) of the objects matching the configured condition and, in
 * {@code folder} crawl mode, {@code FOLDER('<root-cabinet-path>', DESCEND)}. Listings are ordered by
 * {@code r_modify_date, r_object_id} and paged with a keyset cursor (see {@link DocumentumDql}). For each listed object
 * the permission set is resolved (cached, read with DQL from {@code dm_acl}) and mapped; an object whose version,
 * {@code i_vstamp} and mapped permissions are unchanged since the last run is skipped, otherwise its attributes are read
 * ({@code objects/<id>}), its folder paths resolved, and its primary content, or a preferred rendition, downloaded to a
 * temporary file (deleted when the stream is closed).
 *
 * <p>In {@code latest_major} version mode each listed chronicle is resolved to its highest {@code n.0} version in scope.
 *
 * <p>Incremental crawls (the default) list {@code r_modify_date >= <watermark>}, then compare the {@code i_vstamp} of every
 * permission set in use with the crawl state: an edited permission set changes no document, so the documents using it are
 * listed again. Deletions are found by {@code deletion-tracking}: {@code reconcile} lists the ids in scope and compares
 * them with the crawl state, {@code audit} reads {@code dm_audittrail} events ({@code dm_destroy}, {@code dm_prune}), and
 * a full crawl always tombstones what it no longer finds. A document whose tracked object disappeared is looked up again
 * first: when another version is in scope now it is re-indexed, otherwise it gets a DELETE tombstone.
 *
 * <p>As a Spring bean it is active with {@code spring.opencrawling.connector.type=documentum} and reads
 * {@code spring.opencrawling.connector.documentum.*}; jobs build it from their connector configuration instead.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "documentum")
public class DocumentumRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(DocumentumRepositoryConnector.class);

    /** Shown when connecting and by the connection check: OpenCrawling ships no Documentum licence. */
    public static final String LICENCE_NOTICE = "This connector requires a licensed OpenText Documentum Content Server with "
            + "Documentum REST Services deployed, and an account that can read the documents to crawl (a superuser, or one "
            + "with at least READ on them); OpenCrawling does not include OpenText Documentum or any OpenText licence. See the "
            + "Prerequisites in the connector's README.";

    private static final Pattern MAJOR = Pattern.compile("^(\\d+)\\.0$");
    private static final int ACL_BATCH = 50;
    private static final Duration AUDIT_LOOKBACK = Duration.ofHours(26);
    /** {@code user_privileges} superuser bit and {@code user_xprivileges} View Audit bit (DFC IDfUser constants). */
    static final int SUPERUSER = 16;
    static final int VIEW_AUDIT = 32;

    private final DocumentumProperties settings;
    private final DocumentumDocumentBuilder builder;
    private final DocumentumSecurityMapper securityMapper;
    private final DocumentumDql dql;
    private DocumentumClient client;
    private ZoneId serverZone = ZoneOffset.UTC;
    private final Map<String, Optional<Acl>> acls = new ConcurrentHashMap<>();
    private final Map<String, Optional<Principal>> principals = new ConcurrentHashMap<>();
    private final Map<String, List<String>> folderPaths = new ConcurrentHashMap<>();

    /** Used by SPI discovery only. */
    public DocumentumRepositoryConnector() {
        this(DocumentumProperties.fromConfiguration(Map.of()));
    }

    @Autowired
    public DocumentumRepositoryConnector(Environment environment) {
        this(DocumentumProperties.fromEnvironment(environment));
    }

    public DocumentumRepositoryConnector(DocumentumProperties settings) {
        this(settings, null);
    }

    DocumentumRepositoryConnector(DocumentumProperties settings, DocumentumClient client) {
        this.settings = settings;
        this.client = client;
        this.builder = new DocumentumDocumentBuilder(settings);
        this.securityMapper = new DocumentumSecurityMapper(settings);
        this.dql = new DocumentumDql(settings);
    }

    @Override
    public String getName() {
        return "DocumentumRepositoryConnector";
    }

    public DocumentumProperties settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info(LICENCE_NOTICE);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Documentum repository connector is not configured: " + String.join(" ", problems)
                    + " " + LICENCE_NOTICE);
        }
        if (client == null) {
            client = newClient(settings);
        }
        Info info = connectionInfo(client, settings);
        if (settings.incrementalEnabled() && settings.deletionTracking() == DeletionTracking.AUDIT) {
            requireAuditAccess(client);
        }
        if (!info.dateLiteralsVerified()) {
            log.warn("Documentum date literals could not be verified (no cabinet visible); incremental crawls assume DQL "
                    + "reads them in {}.", settings.serverTimeZone() != null ? settings.serverTimeZone() : "the REST offsets");
        }
        serverZone = settings.serverTimeZone() != null ? settings.serverTimeZone() : info.serverOffset();
        acls.clear();
        principals.clear();
        folderPaths.clear();
        log.info("Connected to Documentum repository '{}' (Content Server {}) through REST Services at {}; DQL dates in {} "
                + "({}).", info.repository().path("name").asText(settings.repository()),
                info.repository().path("servers").path(0).path("version").asText("?"), client.getEndpoint(), serverZone,
                settings);
    }

    /**
     * What a connection check finds: the repository resource, the time zone offset of the REST server's clock, and whether
     * a date literal written the way the connector writes them matched a known object's modification time.
     */
    public record Info(JsonNode repository, ZoneOffset serverOffset, boolean dateLiteralsVerified) {
    }

    /**
     * The repository and the server's time zone offset; fails when the URL, the credentials, the repository or, in folder
     * crawl mode, the root path are wrong, and when DQL reads date literals in another time zone than the connector writes
     * them in. That last check matters: a date literal off by some hours makes a keyset cursor or a watermark skip
     * documents without any error, so a cabinet is looked up again by its exact {@code r_modify_date}. Also used by the
     * connection check.
     */
    public static Info connectionInfo(DocumentumClient client, DocumentumProperties settings)
            throws IOException, InterruptedException {
        JsonNode repository = client.repository();
        if (settings.crawlMode() == DocumentumProperties.CrawlMode.FOLDER
                && client.dql(DocumentumDql.folderExists(settings.rootCabinetPath()), 1, 1).rows().isEmpty()) {
            throw new DocumentumApiException(404, null, "Documentum folder '" + settings.rootCabinetPath()
                    + "' was not found, or the account cannot see it.");
        }
        DocumentumClient.Page page;
        try { // also the page size every listing asks for, which the server may refuse (rest.paging.max.size)
            page = client.dql(DocumentumDql.PROBE, 1, settings.batchSize());
        } catch (DocumentumApiException e) {
            if (e.status() != 400) {
                throw e;
            }
            throw new DocumentumApiException(400, e.code(), "Documentum refused a DQL page of " + settings.batchSize()
                    + " items (" + e.getMessage() + "). Lower batch-size to at most the REST server's "
                    + "rest.paging.max.size.");
        }
        ZoneOffset offset = DocumentumDocumentBuilder.offsetDateTime(page.updated()).map(OffsetDateTime::getOffset)
                .orElse(ZoneOffset.UTC);
        boolean verified = false;
        if (!page.rows().isEmpty()) {
            JsonNode cabinet = page.rows().getFirst();
            Optional<OffsetDateTime> modified = DocumentumDocumentBuilder.offsetDateTime(
                    DocumentumDocumentBuilder.text(cabinet, "r_modify_date"));
            if (modified.isPresent()) {
                ZoneId zone = settings.serverTimeZone() != null ? settings.serverTimeZone() : modified.get().getOffset();
                String id = cabinet.path("r_object_id").asText();
                if (client.dql(DocumentumDql.probe(id, modified.get().toInstant(), zone), 1, 1).rows().isEmpty()) {
                    throw new DocumentumApiException(0, null, "Documentum reads DQL date literals in another time zone "
                            + "than " + zone + ": cabinet " + id + " modified at " + modified.get() + " is not found as "
                            + DocumentumDql.date(modified.get().toInstant(), zone) + ". Set server-time-zone to the Content "
                            + "Server's time zone (auto uses the offset the REST server writes in each date).");
                }
                verified = true;
            }
        }
        return new Info(repository, offset, verified);
    }

    /**
     * {@code audit} deletion tracking reads {@code dm_audittrail}, which only superusers and accounts with the View Audit
     * extended privilege can query; anyone else gets no rows, so deletions would go unnoticed without an error.
     */
    static void requireAuditAccess(DocumentumClient client) throws IOException, InterruptedException {
        JsonNode user = client.currentUser().path("properties");
        int privileges = user.path("user_privileges").asInt(0);
        int xprivileges = user.path("user_xprivileges").asInt(0);
        if ((privileges & SUPERUSER) == 0 && (xprivileges & VIEW_AUDIT) == 0) {
            throw new IllegalStateException("deletion-tracking 'audit' needs an account that can read dm_audittrail: "
                    + user.path("user_name").asText("the crawl account") + " is neither a superuser nor has the View Audit "
                    + "extended privilege. Grant it, or use deletion-tracking 'reconcile'.");
        }
    }

    /** A client for these settings; {@code oauth2} without an access token uses the client-credentials grant. */
    public static DocumentumClient newClient(DocumentumProperties settings) {
        Supplier<String> bearer = null;
        if (settings.authType().equals("bearer") || settings.authType().equals("oauth2")) {
            bearer = settings.oauth2AccessToken() != null ? settings::oauth2AccessToken
                    : new DocumentumOAuth2TokenSource(settings.oauth2TokenUrl(), settings.oauth2ClientId(),
                            settings.oauth2ClientSecret(), settings.oauth2Scope(), settings.timeout());
        }
        // redirects are not followed here: the client follows a content redirect itself, only to the REST server
        return new DocumentumClient(settings, settings.timeout(), settings.maxRetries(), Duration.ofMillis(500),
                java.net.http.HttpClient.newBuilder().connectTimeout(settings.timeout())
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                new ObjectMapper(), bearer);
    }

    @Override
    public synchronized void disconnect() {
        // requests are authenticated one by one: there is no session to close
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
                    for (String type : settings.types()) {
                        list(type, dql.scope(path, serverZone), null, crawl, sink);
                    }
                } else if (settings.incrementalEnabled()) {
                    crawlIncrementally(crawl, sink);
                } else {
                    for (String type : settings.types()) {
                        list(type, dql.scope(null, serverZone), null, crawl, sink);
                    }
                }
                log.info("Documentum scan of repository '{}' finished: {} documents, {} unchanged, {} tombstones, {} "
                                + "excluded, {} failed.", settings.repository(), crawl.documents, crawl.unchanged,
                        crawl.tombstones, crawl.skipped, crawl.failed);
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Documentum scan failed: {}", e.getMessage(), e);
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
        /** The previous run's objects (incremental), the entries recorded by this run, and the keys handled. */
        Map<String, DocumentumCrawlState.Entry> previous = Map.of();
        final Map<String, DocumentumCrawlState.Entry> next = new ConcurrentHashMap<>();
        final Set<String> handled = ConcurrentHashMap.newKeySet();
        /** The permission sets read by this run. */
        final Map<String, DocumentumCrawlState.AclStamp> aclStamps = new ConcurrentHashMap<>();
        /** Chronicles resolved to their latest major version (latest_major mode). */
        final Set<String> resolved = ConcurrentHashMap.newKeySet();
        /** False when a listing was cut short (max-documents). */
        volatile boolean complete = true;
        /** True while folders changed since the watermark are listed again: their documents are rebuilt (paths). */
        volatile boolean refresh;
        private int accepted;

        /**
         * A slot for one more document to fetch and index. Unchanged documents take none, so with max-documents each
         * incremental run moves on to documents not indexed yet instead of spending the budget on the same first ones.
         */
        synchronized boolean admit() {
            if (settings.maxDocuments() > 0 && accepted >= settings.maxDocuments()) {
                complete = false;
                return false;
            }
            accepted++;
            return true;
        }
    }

    // ------------------------------------------------------------------ listings

    private void crawlIncrementally(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Path file = DocumentumCrawlState.file(settings);
        DocumentumCrawlState.State state = DocumentumCrawlState.load(file);
        crawl.previous = state.objects();
        Instant start = Instant.now();
        Optional<Instant> watermark = Optional.ofNullable(state.watermark()).map(Instant::parse);
        ZoneId markZone = serverZone;
        if (settings.serverTimeZone() == null && state.watermarkOffset() != null && watermark.isPresent()) {
            // the offset the server was in at the watermark, which a daylight saving zone reads the literal in
            ZoneOffset then = ZoneOffset.of(state.watermarkOffset());
            markZone = then;
            if (!then.equals(serverZone)) {
                int shift = Math.abs(then.getTotalSeconds() - ((ZoneOffset) serverZone).getTotalSeconds());
                log.info("Documentum server offset changed from {} to {} since the last run (daylight saving?); the "
                        + "watermark is read in {} and widened by {} s.", then, serverZone, then, shift);
                watermark = watermark.map(w -> w.minusSeconds(shift));
            }
        }
        log.info("Documentum incremental crawl: {} known documents, {}.", crawl.previous.size(),
                watermark.map(w -> "changes since " + w).orElse("first run, full crawl"));
        ZoneId sinceZone = markZone;
        Optional<Instant> from = watermark;
        for (String type : settings.types()) {
            // r_modify_date has whole seconds: ">=" the truncated watermark, so nothing saved in that second is missed
            String since = from.map(w -> "r_modify_date >= " + DocumentumDql.date(w, sinceZone)).orElse(null);
            list(type, dql.scope(null, serverZone), since, crawl, sink);
        }
        if (from.isPresent() && settings.crawlMode() == DocumentumProperties.CrawlMode.FOLDER
                && settings.includeSubfolders()) {
            relistChangedFolders(from.get(), sinceZone, crawl, sink);
        }
        if (watermark.isPresent()) {
            recheckEditedAcls(state.acls(), crawl, sink);
        }
        String eventWatermark = state.eventWatermark();
        boolean ok = crawl.failed.get() == 0 && crawl.complete;
        if (ok) {
            if (watermark.isEmpty()) {
                tombstoneUnseen(crawl, sink); // a full run: whatever it did not find is gone
            } else if (settings.deletionTracking() == DeletionTracking.RECONCILE) {
                reconcile(crawl, sink);
            } else if (settings.deletionTracking() == DeletionTracking.AUDIT) {
                eventWatermark = readAuditTrail(Optional.ofNullable(eventWatermark).map(Instant::parse)
                        .orElse(watermark.get()), crawl, sink);
            }
        }
        // deletion tracking re-lists documents too: a failure there must not advance anything either
        ok = ok && crawl.failed.get() == 0 && crawl.complete;
        if (!ok) {
            eventWatermark = state.eventWatermark();
        }
        // entries this run did not touch carry over; a failed run keeps the previous watermark
        Map<String, DocumentumCrawlState.Entry> objects = new LinkedHashMap<>(crawl.previous);
        objects.keySet().removeIf(k -> crawl.handled.contains(k) && !crawl.next.containsKey(k));
        objects.putAll(crawl.next);
        // a permission set's new stamp is only kept when every document using it in scope was handled: after a failed or
        // cut-short run, the next one must still see the permission set as edited and list its documents again
        Map<String, DocumentumCrawlState.AclStamp> stamps = new LinkedHashMap<>(state.acls());
        if (ok) {
            stamps.putAll(crawl.aclStamps);
        } else {
            crawl.aclStamps.forEach(stamps::putIfAbsent); // newly used ones only
        }
        Set<String> used = new HashSet<>();
        objects.values().forEach(e -> used.add(Acl.key(e.aclDomain(), e.aclName())));
        stamps.keySet().retainAll(used);
        String mark = start.minusSeconds(settings.clockSkewSeconds()).truncatedTo(ChronoUnit.SECONDS).toString();
        String nextWatermark = ok ? mark : state.watermark();
        if (eventWatermark == null && watermark.isEmpty() && ok) {
            eventWatermark = mark;
        }
        String offset = !ok ? state.watermarkOffset()
                : serverZone instanceof ZoneOffset o && settings.serverTimeZone() == null ? o.getId() : null;
        DocumentumCrawlState.save(file, new DocumentumCrawlState.State(nextWatermark, eventWatermark, objects, stamps,
                offset));
    }

    /**
     * The folders below the crawl root saved since the watermark, listed again in full. Moving a folder saves only the
     * folder: the documents below it keep their {@code r_modify_date}, so the documents of a folder moved into the scope
     * would never be listed by the watermark. Documents already indexed and unchanged are skipped as usual.
     */
    private void relistChangedFolders(Instant since, ZoneId zone, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String root = settings.rootCabinetPath().replaceAll("/+$", "");
        if (root.isEmpty()) {
            return; // the whole repository: moving a folder does not change what is in scope
        }
        Set<String> paths = new TreeSet<>();
        for (JsonNode folder : client.dqlAll(DocumentumDql.changedFolders(root, since, zone), settings.batchSize())) {
            for (JsonNode p : folder.path("r_folder_path")) {
                String path = p.asText();
                if (path.startsWith(root + "/")) {
                    paths.add(path);
                }
            }
        }
        List<String> tops = new ArrayList<>(); // a folder below another changed one is in that one's listing
        for (String path : paths) {
            if (tops.stream().noneMatch(t -> path.startsWith(t + "/"))) {
                tops.add(path);
            }
        }
        if (!tops.isEmpty()) {
            log.info("Documentum folders changed since the watermark (moved, renamed, re-secured or new): {}; their "
                    + "documents are listed again.", tops);
        }
        crawl.refresh = true; // their folder paths changed although the documents did not
        try {
            for (String path : tops) {
                for (String type : settings.types()) {
                    list(type, dql.scope(path, serverZone), null, crawl, sink);
                }
            }
        } finally {
            crawl.refresh = false;
        }
    }

    /** One keyset-paged listing of a type: the scope, an extra condition, in modification order. */
    private void list(String type, List<String> scope, String extra, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        log.info("Documentum listing: FROM {} WHERE {} (page {}).", dql.from(type),
                scope.isEmpty() && extra == null ? "(all)" : String.join(" AND ", scope) + (extra == null ? "" : " + " + extra),
                settings.batchSize());
        Cursor cursor = null;
        while (crawl.complete) {
            DocumentumClient.Page page = client.dql(dql.listing(type, scope, extra, cursor), 1, settings.batchSize());
            if (page.rows().isEmpty()) {
                return;
            }
            process(type, scope, page.rows(), crawl, sink);
            JsonNode last = page.rows().getLast();
            Cursor next = cursor(last);
            if (next == null || next.equals(cursor)) {
                throw new IllegalStateException("Documentum listing of " + type + " made no progress after "
                        + (cursor == null ? "the first page" : cursor.objectId()) + "; is server-time-zone right?");
            }
            cursor = next; // a short page is not the end: the server may cap items-per-page below the batch size
        }
    }

    /** The keyset position after a row; its time is written back in the offset the server gave it, or the configured zone. */
    private Cursor cursor(JsonNode row) {
        Optional<OffsetDateTime> modified = DocumentumDocumentBuilder.offsetDateTime(
                DocumentumDocumentBuilder.text(row, "r_modify_date"));
        String id = DocumentumDocumentBuilder.text(row, "r_object_id");
        if (modified.isEmpty() || id == null) {
            return null;
        }
        ZoneId zone = settings.serverTimeZone() != null ? settings.serverTimeZone() : modified.get().getOffset();
        return new Cursor(modified.get().toInstant(), zone, id);
    }

    // ------------------------------------------------------------------ documents

    /** Listed rows: their permission sets and principals are fetched in batches, then each document is handled. */
    @SuppressWarnings("preview")
    private void process(String type, List<String> scope, List<JsonNode> rows, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        List<JsonNode> targets = settings.versionMode() == VersionMode.LATEST_MAJOR
                ? latestMajors(type, scope, rows, crawl, sink) : rows;
        if (settings.includeAcls()) {
            prefetch(targets);
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scopeTasks = StructuredTaskScope.open()) {
            for (JsonNode row : targets) {
                if (!crawl.complete) {
                    break; // max-documents reached
                }
                permits.acquire();
                scopeTasks.fork(ObservabilityTask.observed(() -> {
                    try {
                        handle(row, crawl, sink);
                    } catch (InterruptedException e) {
                        crawl.failed.incrementAndGet(); // not crawled: the run must not count as complete
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("Documentum object {} could not be crawled: {}", row.path("r_object_id").asText(),
                                e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scopeTasks.join();
        }
    }

    /**
     * {@code latest_major} mode: each chronicle of the rows (not resolved yet in this run) becomes its highest major
     * version in scope; a known chronicle without one is tombstoned.
     */
    private List<JsonNode> latestMajors(String type, List<String> scope, List<JsonNode> rows, Crawl crawl,
                                        FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        List<String> chronicles = new ArrayList<>();
        for (JsonNode row : rows) {
            String c = DocumentumDocumentBuilder.chronicle(row);
            if (crawl.resolved.add(c)) {
                chronicles.add(c);
            }
        }
        List<JsonNode> targets = new ArrayList<>();
        for (List<String> chunk : chunks(chronicles, DocumentumDql.IN_LIST)) {
            Map<String, JsonNode> best = new HashMap<>();
            Map<String, Integer> bestMajor = new HashMap<>();
            for (JsonNode row : client.dqlAll(dql.majorVersions(type, scope, chunk), settings.batchSize())) {
                int major = major(row.path("r_version_label"));
                String c = DocumentumDocumentBuilder.chronicle(row);
                if (major >= 0 && major > bestMajor.getOrDefault(c, -1)) {
                    bestMajor.put(c, major);
                    best.put(c, row);
                }
            }
            for (String c : chunk) {
                if (best.containsKey(c)) {
                    targets.add(best.get(c));
                } else if (crawl.previous.containsKey(c) && !crawl.handled.contains(c)) {
                    tombstone(c, crawl.previous.get(c), crawl, sink); // no major version in scope any more
                }
            }
        }
        return targets;
    }

    /** The major number of an implicit version label {@code n.0}, or -1. */
    static int major(JsonNode labels) {
        int best = -1;
        for (JsonNode label : labels) {
            Matcher m = MAJOR.matcher(label.asText());
            if (m.matches()) {
                best = Math.max(best, Integer.parseInt(m.group(1)));
            }
        }
        return best;
    }

    private void handle(JsonNode row, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        String objectId = row.path("r_object_id").asText();
        String chronicleId = DocumentumDocumentBuilder.chronicle(row);
        String key = builder.stateKey(chronicleId, objectId);
        if (!crawl.handled.add(key)) {
            return; // already handled in this run (listed again by an ACL re-check or a recheck)
        }
        String aclDomain = row.path("acl_domain").asText("");
        String aclName = row.path("acl_name").asText("");
        DocumentumSecurityMapper.Mapped security = security(row, aclDomain, aclName, crawl);
        DocumentumCrawlState.Entry known = crawl.previous.get(key);
        if (security.excluded()) {
            crawl.skipped.incrementAndGet();
            log.debug("Documentum object {} is excluded: its permission set {} has required groups.", objectId, aclName);
            if (known != null) {
                tombstone(key, known, crawl, sink); // required groups added since the last run: out of the index
            }
            return;
        }
        int vstamp = row.path("i_vstamp").asInt(-1);
        if (known != null && known.objectId().equals(objectId) && known.vstamp() == vstamp
                && known.security().equals(security.fingerprint()) && !crawl.refresh) {
            crawl.unchanged.incrementAndGet();
            crawl.next.put(key, known);
            return;
        }
        if (!crawl.admit()) {
            crawl.handled.remove(key); // not crawled in this run: what the state knows of it stays
            log.info("Documentum scan stopped at max-documents={}.", settings.maxDocuments());
            return;
        }
        try {
            index(row, objectId, key, known, vstamp, aclDomain, aclName, security, crawl, sink);
        } catch (IOException | RuntimeException e) {
            if (known != null) {
                crawl.next.put(key, known); // kept, so deletion tracking still knows the indexed document
            }
            throw e;
        }
    }

    private void index(JsonNode row, String objectId, String key, DocumentumCrawlState.Entry known, int vstamp,
                       String aclDomain, String aclName, DocumentumSecurityMapper.Mapped security, Crawl crawl,
                       FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Optional<JsonNode> object = client.object(objectId);
        if (object.isEmpty()) {
            crawl.handled.remove(key); // destroyed since it was listed: deletion tracking decides
            log.info("Documentum object {} disappeared while crawling.", objectId);
            return;
        }
        JsonNode properties = object.get().path("properties");
        List<String> folders = settings.includeFolderPaths() ? folderPaths(properties.path("i_folder_id")) : List.of();
        DocumentumDocumentBuilder.Content content = settings.includeContentStream() ? content(objectId, properties) : null;
        RepositoryDocument d;
        try {
            d = builder.build(properties, content, folders, security);
        } catch (RuntimeException e) {
            if (content != null) {
                content.stream().close(); // deletes the downloaded temporary file
            }
            throw e;
        }
        sink.next(d);
        crawl.documents.incrementAndGet();
        if (known != null && !known.id().equals(d.id())) {
            sink.next(DocumentumDocumentBuilder.tombstone(known.id(), settings.repository(), key));
            crawl.tombstones.incrementAndGet();
        }
        crawl.next.put(key, new DocumentumCrawlState.Entry(objectId, vstamp, security.fingerprint(), aclDomain, aclName,
                d.id()));
    }

    private DocumentumSecurityMapper.Mapped security(JsonNode row, String domain, String name, Crawl crawl)
            throws IOException, InterruptedException {
        if (!settings.includeAcls()) {
            return DocumentumSecurityMapper.publicAccess();
        }
        Optional<Acl> acl = acl(domain, name);
        acl.ifPresent(a -> crawl.aclStamps.put(a.key(), new DocumentumCrawlState.AclStamp(a.id(), a.vstamp())));
        if (acl.isEmpty()) {
            log.warn("Documentum permission set {}/{} was not found; its documents get no readers.", domain, name);
        }
        return securityMapper.map(acl.orElse(null), DocumentumDocumentBuilder.text(row, "owner_name"),
                DocumentumDocumentBuilder.text(row, "group_name"), n -> principals.getOrDefault(n, Optional.empty()).orElse(null));
    }

    /** Reads the permission sets and principals the rows need, in batches, into the caches. */
    private void prefetch(List<JsonNode> rows) throws IOException, InterruptedException {
        Map<String, String[]> missing = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String domain = row.path("acl_domain").asText("");
            String name = row.path("acl_name").asText("");
            if (!acls.containsKey(Acl.key(domain, name))) {
                missing.put(Acl.key(domain, name), new String[]{domain, name});
            }
        }
        for (List<String[]> chunk : chunks(new ArrayList<>(missing.values()), ACL_BATCH,
                k -> 40 + k[0].length() + k[1].length())) {
            for (JsonNode r : client.dqlAll(DocumentumDql.acls(chunk), settings.batchSize())) {
                Acl acl = Acl.of(r);
                acls.put(acl.key(), Optional.of(acl));
            }
            for (String[] k : chunk) {
                acls.putIfAbsent(Acl.key(k[0], k[1]), Optional.empty());
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String owner = DocumentumDocumentBuilder.text(row, "owner_name");
            if (owner != null) {
                names.add(owner);
            }
            if (settings.principalFormat() == DocumentumProperties.PrincipalFormat.LOGIN) {
                acls.getOrDefault(Acl.key(row.path("acl_domain").asText(""), row.path("acl_name").asText("")),
                        Optional.empty()).ifPresent(a -> a.entries().forEach(e -> {
                            if (!e.group() && !e.accessor().startsWith("dm_") && !e.accessor().startsWith("%")) {
                                names.add(e.accessor());
                            }
                        }));
            }
        }
        names.removeIf(principals::containsKey);
        boolean byLogin = settings.principalFormat() == DocumentumProperties.PrincipalFormat.LOGIN;
        for (List<String> chunk : chunks(new ArrayList<>(names), DocumentumDql.IN_LIST, n -> 4 + n.length())) {
            Map<String, JsonNode> found = new LinkedHashMap<>();
            for (JsonNode r : client.dqlAll(DocumentumDql.users(chunk), settings.batchSize())) {
                found.put(r.path("user_name").asText(), r);
            }
            Set<String> shared = byLogin ? sharedLogins(found.values()) : Set.of();
            found.forEach((name, r) -> {
                String login = DocumentumDocumentBuilder.text(r, "user_login_name");
                if (login != null && shared.contains(login.toLowerCase(Locale.ROOT))) {
                    log.warn("Documentum login name '{}' of '{}' belongs to users of several login domains; it is not "
                            + "used as an identity (its grants are dropped, a restriction on it closes the document).",
                            login, name);
                    login = null;
                }
                principals.put(name, Optional.of(new Principal(r.path("r_is_group").asBoolean(false), login)));
            });
            chunk.forEach(n -> principals.putIfAbsent(n, Optional.empty()));
        }
    }

    /**
     * The login names among these users that more than one user has: {@code user_login_name} is unique only within a
     * {@code user_login_domain}, so such a login does not identify one user.
     */
    private Set<String> sharedLogins(Collection<JsonNode> users) throws IOException, InterruptedException {
        List<String> logins = users.stream().filter(r -> !r.path("r_is_group").asBoolean(false))
                .map(r -> DocumentumDocumentBuilder.text(r, "user_login_name")).filter(Objects::nonNull).distinct().toList();
        Map<String, Integer> holders = new HashMap<>();
        for (List<String> chunk : chunks(logins, DocumentumDql.IN_LIST, n -> 4 + n.length())) {
            for (JsonNode r : client.dqlAll(DocumentumDql.usersByLogin(chunk), settings.batchSize())) {
                if (r.path("r_is_group").asBoolean(false)) {
                    continue;
                }
                holders.merge(r.path("user_login_name").asText().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        Set<String> shared = new HashSet<>();
        holders.forEach((login, n) -> {
            if (n > 1) {
                shared.add(login);
            }
        });
        return shared;
    }

    private Optional<Acl> acl(String domain, String name) throws IOException, InterruptedException {
        Optional<Acl> cached = acls.get(Acl.key(domain, name));
        if (cached != null) {
            return cached;
        }
        List<JsonNode> found = client.dqlAll(DocumentumDql.acls(List.<String[]>of(new String[]{domain, name})),
                settings.batchSize());
        Optional<Acl> acl = found.isEmpty() ? Optional.empty() : Optional.of(Acl.of(found.getFirst()));
        acls.put(Acl.key(domain, name), acl);
        return acl;
    }

    /** The paths of the folders an object is linked into ({@code r_folder_path} of each {@code i_folder_id}). */
    private List<String> folderPaths(JsonNode folderIds) throws IOException, InterruptedException {
        List<String> ids = DocumentumDocumentBuilder.values(folderIds);
        List<String> missing = ids.stream().filter(id -> !folderPaths.containsKey(id)).toList();
        for (List<String> chunk : chunks(missing, DocumentumDql.IN_LIST)) {
            for (JsonNode r : client.dqlAll(DocumentumDql.folders(chunk), settings.batchSize())) {
                folderPaths.put(r.path("r_object_id").asText(), DocumentumDocumentBuilder.values(r.path("r_folder_path")));
            }
            chunk.forEach(id -> folderPaths.putIfAbsent(id, List.of()));
        }
        List<String> paths = new ArrayList<>();
        ids.forEach(id -> paths.addAll(folderPaths.getOrDefault(id, List.of())));
        return paths;
    }

    /**
     * The content to index: the preferred rendition when the object has it, else its primary content (page 0); null when
     * it has none, it is larger than the size limit, the account may not read it, or it is only served by another server.
     * Those last two are a property of the document rather than a failure of the run, so they must not stop every later
     * incremental run from advancing.
     */
    private DocumentumDocumentBuilder.Content content(String objectId, JsonNode properties)
            throws IOException, InterruptedException {
        try {
            return fetchContent(objectId, properties);
        } catch (DocumentumApiException e) {
            if (e.status() == 403) {
                log.warn("Documentum object {}: the crawl account may browse it but not read its content (HTTP 403); "
                        + "indexed by metadata only. Grant it READ, or use a superuser account.", objectId);
                return null;
            }
            if (DocumentumClient.FOREIGN_LINK.equals(e.code())) {
                log.warn("Documentum object {}: {} Indexed by metadata only.", objectId, e.getMessage());
                return null;
            }
            throw e;
        }
    }

    private DocumentumDocumentBuilder.Content fetchContent(String objectId, JsonNode properties)
            throws IOException, InterruptedException {
        String primary = DocumentumDocumentBuilder.text(properties, "a_content_type");
        if (primary == null) {
            return null; // no content
        }
        JsonNode meta = null;
        boolean rendition = false;
        String preferred = settings.preferRendition();
        if (preferred != null && !preferred.isEmpty() && !preferred.equalsIgnoreCase(primary)) {
            meta = client.content(objectId, preferred).orElse(null);
            rendition = meta != null;
        }
        if (meta == null) {
            meta = client.content(objectId, null).orElse(null);
        }
        if (meta == null) {
            log.info("Documentum object {} lists content format {} but has no content; indexed by metadata only.", objectId,
                    primary);
            return null;
        }
        JsonNode p = meta.path("properties");
        long size = p.path("full_content_size").asLong(0);
        long max = settings.maxContentSizeBytes();
        if (max > 0 && size > max) {
            log.info("Documentum object {} content is {} bytes, larger than {}; indexed by metadata only.", objectId, size, max);
            return null;
        }
        Optional<String> href = DocumentumClient.enclosure(meta);
        if (href.isEmpty()) {
            log.warn("Documentum object {} content has no enclosure link; indexed by metadata only.", objectId);
            return null;
        }
        Optional<Path> file = client.download(href.get(), max);
        if (file.isEmpty()) {
            log.info("Documentum object {} content exceeded {} bytes while downloading; indexed by metadata only.", objectId,
                    max);
            return null;
        }
        return new DocumentumDocumentBuilder.Content(new TempFileInputStream(file.get()),
                DocumentumDocumentBuilder.text(p, "full_format"), DocumentumDocumentBuilder.text(p, "mime_type"), size,
                rendition);
    }

    // ------------------------------------------------------------------ permission set edits

    /**
     * Permission sets edited since the last run: their {@code i_vstamp} changed (or they are gone). The documents that use
     * them are listed again, so their mapped permissions are compared and re-indexed when different.
     */
    private void recheckEditedAcls(Map<String, DocumentumCrawlState.AclStamp> known, Crawl crawl,
                                   FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        if (!settings.includeAcls() || known.isEmpty()) {
            return;
        }
        Map<String, String> byId = new HashMap<>();
        known.forEach((k, s) -> byId.put(s.id(), k));
        Set<String> edited = new LinkedHashSet<>(known.keySet());
        for (List<String> chunk : chunks(new ArrayList<>(byId.keySet()), DocumentumDql.IN_LIST)) {
            for (JsonNode r : client.dqlAll(DocumentumDql.aclStamps(chunk), settings.batchSize())) {
                String key = byId.get(r.path("r_object_id").asText());
                if (key != null && known.get(key).vstamp() == r.path("i_vstamp").asInt(-1)) {
                    edited.remove(key);
                }
            }
        }
        log.info("Documentum permission sets: {} in use, {} edited since the last run.", known.size(), edited.size());
        for (String key : edited) {
            acls.remove(key);
            String[] parts = key.split("\u0000", 2);
            String condition = "acl_domain = " + DocumentumDql.quote(parts[0]) + " AND acl_name = "
                    + DocumentumDql.quote(parts[1]);
            for (String type : settings.types()) {
                list(type, dql.scope(null, serverZone), condition, crawl, sink);
            }
        }
    }

    // ------------------------------------------------------------------ deletions

    /** A full run: every known document this run did not find is tombstoned. */
    private void tombstoneUnseen(Crawl crawl, FluxSink<RepositoryDocument> sink) {
        for (Map.Entry<String, DocumentumCrawlState.Entry> e : crawl.previous.entrySet()) {
            if (!crawl.handled.contains(e.getKey())) {
                tombstone(e.getKey(), e.getValue(), crawl, sink);
            }
        }
    }

    private void tombstone(String key, DocumentumCrawlState.Entry entry, Crawl crawl, FluxSink<RepositoryDocument> sink) {
        sink.next(DocumentumDocumentBuilder.tombstone(entry.id(), settings.repository(), key));
        crawl.tombstones.incrementAndGet();
        crawl.handled.add(key);
        crawl.next.remove(key);
    }

    /**
     * Incremental {@code reconcile} tracking: the objects in scope now against the crawl state. A document is looked up
     * again when its chronicle is gone, and also when the object indexed for it is no longer listed: destroying the
     * CURRENT version makes its antecedent CURRENT without changing that version's {@code r_modify_date}.
     */
    private void reconcile(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Set<String> current = new HashSet<>();
        Set<String> objectIds = new HashSet<>();
        for (String type : settings.types()) {
            List<String> scope = dql.scope(null, serverZone);
            String after = null;
            while (true) {
                DocumentumClient.Page page = client.dql(dql.ids(type, scope, after), 1, settings.batchSize());
                if (page.rows().isEmpty()) {
                    break;
                }
                for (JsonNode row : page.rows()) {
                    current.add(builder.stateKey(DocumentumDocumentBuilder.chronicle(row), row.path("r_object_id").asText()));
                    objectIds.add(row.path("r_object_id").asText());
                }
                after = page.rows().getLast().path("r_object_id").asText();
            }
        }
        List<String> vanished = new ArrayList<>();
        for (Map.Entry<String, DocumentumCrawlState.Entry> e : crawl.previous.entrySet()) {
            if (!crawl.handled.contains(e.getKey())
                    && (!current.contains(e.getKey()) || !objectIds.contains(e.getValue().objectId()))) {
                vanished.add(e.getKey());
            }
        }
        log.info("Documentum reconciliation: {} objects in scope, {} known documents no longer listed.", current.size(),
                vanished.size());
        recheck(vanished, crawl, sink);
    }

    /**
     * Incremental {@code audit} tracking: {@code dm_audittrail} events since the event watermark; a known document whose
     * object was destroyed is looked up again. Returns the new event watermark.
     */
    private String readAuditTrail(Instant since, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        Map<String, String> byObject = new HashMap<>();
        crawl.previous.forEach((k, e) -> byObject.put(e.objectId(), k));
        Set<String> affected = new LinkedHashSet<>();
        Instant last = since;
        // read back a day: events read again only re-check documents still known, while an hour's error in how the
        // server reads the literal (time_stamp's zone, a daylight saving change) would lose deletions
        Instant from = since.minus(AUDIT_LOOKBACK);
        Cursor cursor = null;
        int events = 0;
        while (true) {
            DocumentumClient.Page page = client.dql(dql.audit(from, cursor, serverZone), 1,
                    settings.batchSize());
            if (page.rows().isEmpty()) {
                break;
            }
            Cursor before = cursor;
            for (JsonNode row : page.rows()) {
                events++;
                String key = byObject.get(row.path("audited_obj_id").asText());
                if (key == null && crawl.previous.containsKey(row.path("chronicle_id").asText())) {
                    key = row.path("chronicle_id").asText();
                }
                if (key != null && !crawl.handled.contains(key)) {
                    affected.add(key);
                }
                Optional<OffsetDateTime> t = DocumentumDocumentBuilder.offsetDateTime(
                        DocumentumDocumentBuilder.text(row, "time_stamp"));
                if (t.isPresent()) {
                    if (t.get().toInstant().isAfter(last)) {
                        last = t.get().toInstant();
                    }
                    cursor = new Cursor(t.get().toInstant(), settings.serverTimeZone() != null ? settings.serverTimeZone()
                            : t.get().getOffset(), row.path("r_object_id").asText());
                }
            }
            if (cursor == null || cursor.equals(before)) {
                throw new IllegalStateException("Documentum audit trail paging made no progress; is server-time-zone right?");
            }
        }
        log.info("Documentum audit trail: {} events since {} affect {} known documents.", events, since, affected.size());
        recheck(new ArrayList<>(affected), crawl, sink);
        return last.toString();
    }

    /**
     * Documents whose tracked object is gone: in {@code all} mode the version itself was destroyed; otherwise the
     * chronicle is listed again, and either its version in scope is re-indexed or the document is tombstoned.
     */
    private void recheck(List<String> keys, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        if (settings.versionMode() == VersionMode.ALL) {
            keys.forEach(k -> tombstone(k, crawl.previous.get(k), crawl, sink));
            return;
        }
        for (List<String> chunk : chunks(keys, DocumentumDql.IN_LIST)) {
            String condition = "i_chronicle_id IN (" + String.join(", ", chunk.stream().map(DocumentumDql::quote).toList())
                    + ")";
            for (String type : settings.types()) {
                crawl.resolved.removeAll(chunk);
                list(type, dql.scope(null, serverZone), condition, crawl, sink);
            }
            if (!crawl.complete) {
                return; // max-documents cut the listing short: unlisted is not gone
            }
            for (String key : chunk) {
                if (!crawl.next.containsKey(key) && !crawl.handled.contains(key)) {
                    tombstone(key, crawl.previous.get(key), crawl, sink);
                }
            }
        }
    }

    static <T> List<List<T>> chunks(List<T> items, int size) {
        return chunks(items, size, t -> 0);
    }

    /**
     * Consecutive chunks of at most {@code size} items whose values add up to at most {@link DocumentumDql#IN_CHARS}
     * characters: each chunk becomes one GET request line, which the servlet container under REST Services limits
     * (Tomcat's maxHttpHeaderSize is 8 KB by default, request line and headers together).
     */
    static <T> List<List<T>> chunks(List<T> items, int size, ToIntFunction<T> length) {
        List<List<T>> out = new ArrayList<>();
        int start = 0;
        int chars = 0;
        for (int i = 0; i < items.size(); i++) {
            int n = length.applyAsInt(items.get(i));
            if (i > start && (i - start == size || chars + n > DocumentumDql.IN_CHARS)) {
                out.add(items.subList(start, i));
                start = i;
                chars = 0;
            }
            chars += n;
        }
        if (start < items.size()) {
            out.add(items.subList(start, items.size()));
        }
        return out;
    }
}
