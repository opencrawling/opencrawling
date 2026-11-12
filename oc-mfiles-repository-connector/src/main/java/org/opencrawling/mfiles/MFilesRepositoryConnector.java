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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.mfiles.MFilesDocumentBuilder.FileRef;
import org.opencrawling.mfiles.MFilesDocumentBuilder.PropertyDef;
import org.opencrawling.mfiles.MFilesProperties.CrawlMode;
import org.opencrawling.mfiles.MFilesProperties.VersionMode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * M-Files repository connector (issue #123): crawls one M-Files vault through the M-Files Web Service (MFWS) REST API.
 *
 * <p>Crawl modes:
 * <ul>
 *   <li>{@code SEARCH} / {@code ALL_OBJECTS}: MFWS searches ({@code GET /objects}) per class, filtered by object type and
 *       the configured conditions. MFWS cuts a search at {@code limit} results and offers no offset, so a result with
 *       {@code MoreResults} is re-queried in last-modified time windows ({@code p21>=} / {@code p21<=}) split in half until
 *       each fits;</li>
 *   <li>{@code VIEW}: an M-Files view and its sub-views and property folders;</li>
 *   <li>{@code VAF}: the change feed and normalized objects of a Vault Application Framework app (the protocol of the
 *       ManifoldCF M-Files connector), which also delivers server-resolved permissions.</li>
 * </ul>
 * For every object the latest version (or every version) is read with its property values; its files are downloaded to
 * temporary files (deleted when the stream is closed) — one OIS document for a single-file document or an object without
 * files, one per file for a multi-file document — and its permissions become the OIS security. Deleted objects
 * ({@code Deleted = true}, searched with {@code d=include}) become DELETE tombstones.
 *
 * <p>A scan uses one MFWS session and always ends it. Concurrency is bounded by {@code parallelism}.
 *
 * <p>As a Spring bean it is active with {@code spring.opencrawling.connector.type=mfiles} and reads
 * {@code spring.opencrawling.connector.mfiles.*}; jobs build it from their connector configuration instead.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "mfiles")
public class MFilesRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(MFilesRepositoryConnector.class);

    /** Shown when connecting and by the connection check: OpenCrawling ships no M-Files licence. */
    public static final String LICENCE_NOTICE = "This connector requires a licensed M-Files Server (M-Files Corporation) with "
            + "the M-Files Web Service (REST API) enabled, and an M-Files account with read access to the vault; OpenCrawling "
            + "does not include M-Files or any M-Files licence. See the Prerequisites in the connector's README.";

    /** Lower bound of the time windows when no {@code modified-after} or watermark is set. */
    private static final Instant EPOCH = Instant.parse("1970-01-01T00:00:00Z");
    private static final int VIEW_DEPTH_LIMIT = 32;

    private final MFilesProperties settings;
    private MFilesClient client;
    private String vaultName;
    private Map<Integer, PropertyDef> definitions = Map.of();
    private MFilesSecurityMapper securityMapper;
    private volatile Map<String, String> principalNames;

    /** Used by SPI discovery only. */
    public MFilesRepositoryConnector() {
        this(MFilesProperties.fromConfiguration(Map.of()));
    }

    @Autowired
    public MFilesRepositoryConnector(Environment environment) {
        this(MFilesProperties.fromEnvironment(environment));
    }

    public MFilesRepositoryConnector(MFilesProperties settings) {
        this.settings = settings;
    }

    MFilesRepositoryConnector(MFilesProperties settings, MFilesClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public String getName() {
        return "MFilesRepositoryConnector";
    }

    @Override
    public String sourceInstance() {
        return settings != null ? settings.url() : null;
    }

    public MFilesProperties settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        log.info(LICENCE_NOTICE);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("M-Files repository connector is not configured: " + String.join(" ", problems)
                    + " " + LICENCE_NOTICE);
        }
        if (client == null) {
            client = newClient(settings, settings.timeout(), settings.maxRetries());
        }
        client.login();
        JsonNode vault = client.getVault();
        vaultName = vault.path("Name").asText(null);
        String guid = MFilesProperties.stripBraces(vault.path("GUID").asText(""));
        if (!guid.isEmpty() && !guid.equalsIgnoreCase(settings.vaultKey())) {
            throw new IllegalStateException("M-Files session is scoped to vault " + guid + ", not the configured "
                    + settings.vaultKey() + ".");
        }
        definitions = propertyDefinitions();
        principalNames = null;
        securityMapper = new MFilesSecurityMapper(settings, this::principalName);
        log.info("Connected to M-Files vault '{}' at {} ({}).", vaultName, client.getBaseUrl(), settings);
    }

    /** A client for these settings; {@code auth-type: oauth2} without an access token uses the client-credentials grant. */
    public static MFilesClient newClient(MFilesProperties settings, Duration timeout, int maxRetries) {
        Supplier<String> bearer = null;
        if (settings.loginMode() == MFilesProperties.LoginMode.BEARER) {
            bearer = settings.oauth2AccessToken() != null
                    ? settings::oauth2AccessToken
                    : new MFilesOAuth2TokenSource(settings.oauth2TokenUrl(), settings.oauth2ClientId(),
                            settings.oauth2ClientSecret(), settings.oauth2Scope(), timeout);
        }
        return new MFilesClient(settings, timeout, maxRetries, Duration.ofMillis(500),
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build(),
                new ObjectMapper(), bearer);
    }

    @Override
    public synchronized void disconnect() {
        if (client != null) {
            client.logout();
        }
    }

    private Map<Integer, PropertyDef> propertyDefinitions() throws IOException, InterruptedException {
        Map<Integer, PropertyDef> defs = new HashMap<>();
        try {
            for (JsonNode def : MFilesClient.items(client.getPropertyDefinitions())) {
                defs.put(def.path("ID").asInt(), new PropertyDef(def.path("ID").asInt(), def.path("Name").asText(null),
                        def.path("DataType").asInt(0)));
            }
        } catch (MFilesApiException e) {
            log.warn("M-Files property definitions are unreadable ({}); properties are emitted as {}p<id>.", e.getMessage(),
                    settings.propertyPrefix());
        }
        return defs;
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                connect();
                Crawl crawl = new Crawl();
                String path = basePath == null ? "" : basePath.strip();
                boolean override = !path.isEmpty() && !"default".equalsIgnoreCase(path) && !"/".equals(path);
                if (override && (settings.crawlMode() == CrawlMode.VIEW || path.matches("(?i)^(views/)?v\\d+.*"))) {
                    crawlView(viewPath(path.replaceFirst("(?i)^views/", "")), crawl, sink);
                } else if (override) {
                    if (settings.incrementalEnabled()) {
                        log.info("A job path replaces the configured search, so this run is not incremental.");
                    }
                    crawlSearch(conditions(path), crawl, sink);
                } else if (settings.crawlMode() == CrawlMode.VIEW) {
                    crawlView(viewPath(settings.viewId()), crawl, sink);
                } else if (settings.crawlMode() == CrawlMode.VAF) {
                    crawlVaf(crawl, sink);
                } else if (settings.incrementalEnabled()) {
                    crawlIncrementally(crawl, sink);
                } else {
                    crawlSearch(conditions(settings.crawlMode() == CrawlMode.ALL_OBJECTS ? "" : settings.searchQuery()), crawl, sink);
                }
                log.info("M-Files scan of vault '{}' finished: {} documents, {} unchanged, {} tombstones, {} skipped, {} failed.",
                        vaultName, crawl.documents, crawl.unchanged, crawl.tombstones, crawl.skipped, crawl.failed);
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("M-Files scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                // one session per scan
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
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        /** Incremental state: the previous run's objects, the entries recorded by this run, and the objects deleted. */
        Map<String, MFilesCrawlState.Entry> previous;
        final Map<String, MFilesCrawlState.Entry> next = new ConcurrentHashMap<>();
        final Set<String> removed = ConcurrentHashMap.newKeySet();
        /** False when a listing was cut short (a view or time window over the limit, max-documents). */
        volatile boolean complete = true;
        private int accepted;

        /** The objects not seen before, cut to {@code maxDocuments}. */
        synchronized List<JsonNode> take(List<JsonNode> objects) {
            List<JsonNode> taken = new ArrayList<>();
            for (JsonNode object : objects) {
                if (limitReached()) {
                    complete = false;
                    break;
                }
                if (accept(key(object))) {
                    taken.add(object);
                }
            }
            return taken;
        }

        /** Whether an object ({@code <type>/<id>}) is new to this scan and within {@code maxDocuments}. */
        synchronized boolean accept(String key) {
            if (!admit()) {
                return false;
            }
            if (!seen.add(key)) {
                accepted--;
                return false;
            }
            return true;
        }

        /** Counts one more object against {@code maxDocuments}; false once the limit is reached. */
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

    private static String key(JsonNode objectVersion) {
        JsonNode objVer = objectVersion.path("ObjVer");
        return objVer.path("Type").asInt() + "/" + objVer.path("ID").asInt();
    }

    // ------------------------------------------------------------------ SEARCH / ALL_OBJECTS mode

    /** The search conditions: MFWS expressions as given, or a quick search for plain text. */
    static String conditions(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String q = query.strip().replaceFirst("^\\?", "");
        return q.contains("=") ? q : "q=" + MFilesClient.enc(q);
    }

    private void crawlSearch(String conditions, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        crawlSearch(conditions, settings.modifiedAfterInstant().orElse(null), crawl, sink);
    }

    private void crawlSearch(String conditions, Instant from, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        List<Integer> classes = settings.classIds().isEmpty() ? java.util.Collections.singletonList(null) : settings.classIds();
        for (Integer classId : classes) {
            List<String> parts = new ArrayList<>();
            if (!conditions.isBlank()) {
                parts.add(conditions);
            }
            if (settings.objectType() >= 0) {
                parts.add("o=" + settings.objectType());
            }
            if (classId != null) {
                parts.add("p" + MFilesDocumentBuilder.PD_CLASS + "=" + classId);
            }
            if (settings.emitDeletes()) {
                parts.add("d=include");
            }
            String query = String.join("&", parts);
            log.info("M-Files search: /objects?{} (limit {}{}).", query, settings.batchSize(), from == null ? "" : ", modified >= " + from);
            searchWindow(query, from, null, settings.batchSize(), crawl, sink);
            if (crawl.limitReached()) {
                log.info("M-Files scan stopped at max-documents={}.", settings.maxDocuments());
                return;
            }
        }
    }

    /**
     * One search over the last-modified window [{@code from}, {@code to}] (open bounds when null). A result MFWS cut at the
     * limit is split into two halves (the shared second is searched twice; objects are de-duplicated), down to one second.
     */
    private void searchWindow(String query, Instant from, Instant to, int limit, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        if (crawl.limitReached()) {
            crawl.complete = false;
            return;
        }
        StringBuilder q = new StringBuilder(query);
        if (from != null) {
            q.append(q.isEmpty() ? "" : "&").append("p").append(MFilesDocumentBuilder.PD_LAST_MODIFIED).append("%3E=").append(MFilesClient.enc(from.toString()));
        }
        if (to != null) {
            q.append(q.isEmpty() ? "" : "&").append("p").append(MFilesDocumentBuilder.PD_LAST_MODIFIED).append("%3C=").append(MFilesClient.enc(to.toString()));
        }
        q.append(q.isEmpty() ? "" : "&").append("limit=").append(limit);
        MFilesClient.Page page = client.search(q.toString());
        if (page.moreResults() || page.items().size() > limit) {
            Instant lower = from != null ? from : EPOCH;
            Instant upper = to != null ? to : Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            long seconds = Duration.between(lower, upper).toSeconds();
            if (seconds > 1) {
                Instant middle = lower.plusSeconds(seconds / 2);
                searchWindow(query, lower, middle, limit, crawl, sink);
                searchWindow(query, middle, upper, limit, crawl, sink);
                return;
            }
            if (limit == settings.batchSize()) {
                log.warn("More than {} M-Files objects were modified within {} - {}; searching that second with a 20x limit.",
                        limit, lower, upper);
                searchWindow(query, lower, upper, limit * 20, crawl, sink);
                return;
            }
            log.warn("M-Files still cuts the search for {} - {} at {} results; objects of that second may be missing.",
                    lower, upper, page.items().size());
            crawl.complete = false;
        }
        process(crawl.take(changedOnly(page.items(), crawl)), crawl, sink);
    }

    // ------------------------------------------------------------------ incremental search

    /**
     * Searches from the saved watermark, skips objects whose last-modified time is unchanged, and records what each object
     * produced. The watermark moves to the scan start (minus the clock-skew margin) only when every listed object was
     * processed, so a failed object is listed again next time.
     */
    private void crawlIncrementally(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Path file = MFilesCrawlState.file(settings);
        MFilesCrawlState.State state = MFilesCrawlState.load(file);
        crawl.previous = state.objects();
        Instant scanStart = Instant.now();
        Instant from = settings.modifiedAfterInstant().orElse(null);
        if (state.watermark() != null) {
            Instant watermark = Instant.parse(state.watermark());
            from = from == null || watermark.isAfter(from) ? watermark : from;
        }
        log.info("Incremental M-Files crawl: {} objects known from {}; searching objects modified since {}.",
                crawl.previous.size(), file, from == null ? "the beginning" : from);
        crawlSearch(conditions(settings.crawlMode() == CrawlMode.ALL_OBJECTS ? "" : settings.searchQuery()), from, crawl, sink);

        String watermark = state.watermark();
        if (crawl.complete && crawl.failed.get() == 0) {
            watermark = scanStart.minusSeconds(settings.clockSkewSeconds()).truncatedTo(ChronoUnit.SECONDS).toString();
        } else {
            log.info("M-Files listing incomplete or with failures: the watermark stays at {}.", watermark);
        }
        MFilesCrawlState.save(file, new MFilesCrawlState.State(watermark, state.vafCursor(), merged(crawl)));
    }

    private Map<String, MFilesCrawlState.Entry> merged(Crawl crawl) {
        Map<String, MFilesCrawlState.Entry> objects = new LinkedHashMap<>(crawl.previous);
        crawl.removed.forEach(objects::remove);
        objects.putAll(crawl.next);
        return objects;
    }

    /** Without incremental state, every object; with it, the ones whose last-modified time changed or that were deleted. */
    private List<JsonNode> changedOnly(List<JsonNode> objects, Crawl crawl) {
        if (crawl.previous == null) {
            return objects;
        }
        List<JsonNode> changed = new ArrayList<>();
        for (JsonNode object : objects) {
            String key = key(object);
            MFilesCrawlState.Entry known = crawl.previous.get(key);
            String modified = object.path("LastModifiedUtc").asText(null);
            if (known != null && modified != null && modified.equals(known.modified()) && !object.path("Deleted").asBoolean(false)) {
                if (crawl.seen.add(key)) {
                    crawl.unchanged.incrementAndGet();
                }
            } else {
                changed.add(object);
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ VIEW mode

    /** {@code 101} → {@code v101}; a path such as {@code v101/L5} is used as given. */
    static String viewPath(String viewId) {
        String v = viewId == null ? "" : viewId.strip().replaceAll("^/+|/+$", "");
        return v.matches("\\d+") ? "v" + v : v;
    }

    private void crawlView(String path, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        crawlView(path, 0, new HashSet<>(), crawl, sink);
    }

    private void crawlView(String path, int depth, Set<String> visited, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        if (crawl.limitReached() || !visited.add(path)) {
            return;
        }
        if (depth > VIEW_DEPTH_LIMIT) {
            log.warn("M-Files view {} is nested deeper than {} levels; not descending further.", path, VIEW_DEPTH_LIMIT);
            crawl.complete = false;
            return;
        }
        log.info("M-Files view: /views/{}/items.", path);
        MFilesClient.Page page = client.getViewItems(path);
        if (page.moreResults()) {
            log.warn("M-Files truncated view {} at {} items; the rest of this view folder is not crawled.", path, page.items().size());
            crawl.complete = false;
        }
        List<JsonNode> objects = new ArrayList<>();
        List<String> children = new ArrayList<>();
        for (JsonNode item : page.items()) {
            switch (itemType(item)) {
                case "objectversion" -> objects.add(item.path("ObjectVersion"));
                case "viewfolder" -> children.add(path + "/v" + item.path("View").path("ID").asInt());
                case "propertyfolder" -> propertyFolderSegment(item.path("PropertyFolder")).ifPresent(s -> children.add(path + "/" + s));
                case "traditionalfolder" -> {
                    JsonNode folder = item.path("TraditionalFolder");
                    int id = folder.path("Item").asInt(folder.path("ID").asInt(-1));
                    if (id >= 0) {
                        children.add(path + "/y" + id);
                    }
                }
                default -> log.debug("M-Files view {}: item type {} not crawled.", path, item.path("FolderContentItemType").asText());
            }
        }
        process(crawl.take(objects), crawl, sink);
        for (String child : children) {
            crawlView(child, depth + 1, visited, crawl, sink);
        }
    }

    /** {@code MFFolderContentItemType} by number (1 view, 2 property folder, 3 traditional folder, 4 object) or by name. */
    private static String itemType(JsonNode item) {
        JsonNode type = item.path("FolderContentItemType");
        if (type.isNumber()) {
            return switch (type.asInt()) {
                case 1 -> "viewfolder";
                case 2 -> "propertyfolder";
                case 3 -> "traditionalfolder";
                case 4 -> "objectversion";
                default -> "other";
            };
        }
        return type.asText("").toLowerCase(Locale.ROOT);
    }

    /** The view path level of a property folder: {@code L<id>} for a lookup, {@code T<text>} for text. */
    private static Optional<String> propertyFolderSegment(JsonNode typedValue) {
        int dataType = typedValue.path("DataType").asInt(0);
        if (dataType == MFilesValues.LOOKUP || dataType == MFilesValues.MULTI_SELECT_LOOKUP) {
            JsonNode lookup = typedValue.has("Lookup") ? typedValue.path("Lookup") : typedValue.path("Lookups").path(0);
            return lookup.has("Item") ? Optional.of("L" + lookup.path("Item").asInt()) : Optional.empty();
        }
        if (dataType == MFilesValues.TEXT || dataType == MFilesValues.MULTI_LINE_TEXT) {
            String text = typedValue.path("Value").asText(typedValue.path("DisplayValue").asText(""));
            return text.isEmpty() ? Optional.empty() : Optional.of("T" + MFilesClient.enc(text));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ objects

    @SuppressWarnings("preview")
    private void process(List<JsonNode> objects, Crawl crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        if (objects.isEmpty()) {
            return;
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode object : objects) {
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handle(object, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("M-Files object {} could not be crawled: {}", key(object), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private void handle(JsonNode hit, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        JsonNode objVer = hit.path("ObjVer");
        int type = objVer.path("Type").asInt();
        int id = objVer.path("ID").asInt();
        int version = objVer.path("Version").asInt();
        String key = type + "/" + id;
        if (!settings.classIds().isEmpty() && hit.has("Class") && !settings.classIds().contains(hit.path("Class").asInt())) {
            crawl.skipped.incrementAndGet();
            return;
        }
        MFilesCrawlState.Entry known = crawl.previous == null ? null : crawl.previous.get(key);
        if (hit.path("Deleted").asBoolean(false)) {
            Set<String> ids = new LinkedHashSet<>(MFilesDocumentBuilder.ids(settings.vaultKey(), hit, true));
            if (known != null) {
                ids.addAll(known.ids());
            } else if (settings.versionMode() == VersionMode.ALL_VERSIONS) {
                try {
                    for (JsonNode older : client.getHistory(type, id)) {
                        if (older.path("ObjVer").path("Version").asInt() != version) {
                            ids.addAll(MFilesDocumentBuilder.ids(settings.vaultKey(), older, false));
                        }
                    }
                } catch (MFilesApiException e) {
                    log.warn("History of deleted M-Files object {} is unreadable; only its latest ids are deleted: {}", key,
                            e.getMessage());
                }
            }
            tombstones(ids, type, id, sink, crawl);
            crawl.removed.add(key);
            return;
        }

        List<JsonNode> properties = client.getProperties(type, id, version);
        MFilesSecurityMapper.Mapped security = settings.includeAcls()
                ? securityMapper.fromPermissions(client.getPermissions(type, id, version))
                : MFilesSecurityMapper.publicAccess();

        List<String> emitted = new ArrayList<>();
        emitted.addAll(emitVersion(hit, true, properties, security, crawl, sink));
        if (settings.versionMode() == VersionMode.ALL_VERSIONS) {
            for (JsonNode older : client.getHistory(type, id)) {
                int number = older.path("ObjVer").path("Version").asInt();
                if (number != version) {
                    emitted.addAll(emitVersion(older, false, client.getProperties(type, id, number), security, crawl, sink));
                }
            }
        }
        if (known != null) {
            Set<String> stale = new LinkedHashSet<>(known.ids());
            stale.removeAll(emitted);
            tombstones(stale, type, id, sink, crawl);
        }
        if (crawl.previous != null) {
            crawl.next.put(key, new MFilesCrawlState.Entry(hit.path("LastModifiedUtc").asText(null), List.copyOf(emitted)));
        }
    }

    /** Emits one object version: one document, or one per file of a multi-file document. Returns the ids emitted. */
    private List<String> emitVersion(JsonNode objectVersion, boolean latest, List<JsonNode> properties,
                                     MFilesSecurityMapper.Mapped security, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        JsonNode objVer = objectVersion.path("ObjVer");
        int type = objVer.path("Type").asInt();
        int id = objVer.path("ID").asInt();
        int version = objVer.path("Version").asInt();
        String versionId = MFilesDocumentBuilder.versionId(settings.vaultKey(), type, id, version, latest);
        MFilesDocumentBuilder.Context context = new MFilesDocumentBuilder.Context(settings.vaultKey(), vaultName, latest,
                settings.includeProperties(), settings.propertyPrefix());
        List<FileRef> files = MFilesDocumentBuilder.files(objectVersion);
        List<String> ids = new ArrayList<>();
        if (files.isEmpty()) {
            emit(sink, MFilesDocumentBuilder.build(versionId, context, objectVersion, properties, definitions, null, null, null,
                    -1, security));
            ids.add(versionId);
        } else if (MFilesDocumentBuilder.isSingleFile(objectVersion, files)) {
            emitFile(versionId, null, context, objectVersion, properties, files.getFirst(), security, sink);
            ids.add(versionId);
        } else {
            for (FileRef file : files) {
                String fileId = MFilesDocumentBuilder.fileDocumentId(versionId, file.fileId());
                emitFile(fileId, versionId, context, objectVersion, properties, file, security, sink);
                ids.add(fileId);
            }
        }
        crawl.documents.addAndGet(ids.size());
        return ids;
    }

    private void emitFile(String docId, String parentId, MFilesDocumentBuilder.Context context, JsonNode objectVersion,
                          List<JsonNode> properties, FileRef file, MFilesSecurityMapper.Mapped security,
                          FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        JsonNode objVer = objectVersion.path("ObjVer");
        Download download = download(objVer.path("Type").asInt(), objVer.path("ID").asInt(), objVer.path("Version").asInt(), file);
        RepositoryDocument built;
        try {
            built = MFilesDocumentBuilder.build(docId, context, objectVersion, properties, definitions, file, parentId,
                    download.stream(), download.length(), security);
        } catch (RuntimeException e) {
            download.close();
            throw e;
        }
        emit(sink, built);
    }

    private record Download(InputStream stream, long length) {
        static final Download NONE = new Download(null, -1);

        void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private Download download(int type, int id, int version, FileRef file) throws IOException, InterruptedException {
        if (!settings.includeFiles()) {
            return Download.NONE;
        }
        if (file.size() > settings.maxFileSizeBytes()) {
            log.info("M-Files file {} of object {}/{} ({} bytes) exceeds max-file-size-bytes; metadata only.", file.fileId(), type,
                    id, file.size());
            return Download.NONE;
        }
        Path temp = Files.createTempFile("oc-mfiles-", ".bin");
        try {
            long length = client.downloadFile(type, id, version, file.fileId(), temp, settings.maxFileSizeBytes());
            if (length < 0) {
                Files.deleteIfExists(temp);
                log.info("M-Files file {} of object {}/{} exceeds max-file-size-bytes; metadata only.", file.fileId(), type, id);
                return Download.NONE;
            }
            return new Download(new TempFileInputStream(temp), length);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private void tombstones(Set<String> ids, int type, int id, FluxSink<RepositoryDocument> sink, Crawl crawl) {
        for (String docId : ids) {
            emit(sink, MFilesDocumentBuilder.tombstone(docId, settings.vaultKey(), type, id));
            crawl.tombstones.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ VAF mode

    /**
     * Drains the VAF change feed from the saved cursor (incremental) or from the beginning. Deleted objects become
     * tombstones; changed ones are read through the normalized endpoint, whose ACL tokens are already effective permissions.
     */
    private void crawlVaf(Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        Path file = MFilesCrawlState.file(settings);
        MFilesCrawlState.State state = settings.incrementalEnabled() ? MFilesCrawlState.load(file) : MFilesCrawlState.State.empty();
        if (settings.incrementalEnabled()) {
            crawl.previous = state.objects();
        }
        String start = state.vafCursor() == null ? "" : state.vafCursor();
        String cursor = start;
        log.info("M-Files VAF change feed from cursor '{}'.", cursor);
        while (!crawl.limitReached()) {
            JsonNode page = client.getChanges(cursor, settings.batchSize());
            List<JsonNode> changes = new ArrayList<>();
            page.path("changes").forEach(changes::add);
            processVaf(changes, crawl, sink);
            String nextCursor = page.path("nextCursor").asText(cursor);
            boolean hasMore = page.path("hasMore").asBoolean(false);
            if (hasMore && nextCursor.equals(cursor)) {
                log.warn("The VAF change feed did not advance its cursor '{}'; stopping.", cursor);
                crawl.complete = false;
                break;
            }
            cursor = nextCursor;
            if (!hasMore) {
                break;
            }
        }
        if (crawl.limitReached()) {
            crawl.complete = false;
        }
        if (settings.incrementalEnabled()) {
            String saved = crawl.complete && crawl.failed.get() == 0 ? cursor : start;
            MFilesCrawlState.save(file, new MFilesCrawlState.State(state.watermark(), saved, merged(crawl)));
        }
    }

    @SuppressWarnings("preview")
    private void processVaf(List<JsonNode> changes, Crawl crawl, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            // the feed is ordered: only the last change of an object in a page counts (UPDATED then DELETED is a delete),
            // and objects run in parallel only across different objects
            Map<String, JsonNode> latest = new LinkedHashMap<>();
            for (JsonNode change : changes) {
                String key = change.path("objectType").asInt() + "/" + change.path("objectId").asInt();
                latest.remove(key);
                latest.put(key, change);
            }
            for (JsonNode change : latest.values()) {
                if (!crawl.admit()) {
                    break;
                }
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handleVaf(change, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("M-Files change {}/{} could not be crawled: {}", change.path("objectType").asText("?"),
                                change.path("objectId").asText("?"), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private void handleVaf(JsonNode change, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        int type = change.path("objectType").asInt();
        int id = change.path("objectId").asInt();
        int version = change.path("version").asInt();
        String key = type + "/" + id;
        if (settings.objectType() >= 0 && type != settings.objectType()) {
            crawl.skipped.incrementAndGet();
            return;
        }
        MFilesCrawlState.Entry known = crawl.previous == null ? null : crawl.previous.get(key);
        String objectId = MFilesDocumentBuilder.objectId(settings.vaultKey(), type, id);
        if ("DELETED".equalsIgnoreCase(change.path("changeType").asText(""))) {
            Set<String> ids = new LinkedHashSet<>(List.of(objectId));
            if (known != null) {
                ids.addAll(known.ids());
            }
            tombstones(ids, type, id, sink, crawl);
            crawl.removed.add(key);
            return;
        }
        JsonNode normalized = client.getNormalized(type, id, version);
        String versionString = normalized.path("versionString").asText("v" + version);
        if (known != null && versionString.equals(known.modified())) {
            crawl.unchanged.incrementAndGet();
            return;
        }
        if (!settings.classIds().isEmpty() && normalized.has("classId")
                && !settings.classIds().contains(normalized.path("classId").asInt())) {
            crawl.skipped.incrementAndGet();
            return;
        }

        Map<String, List<String>> metadata = new LinkedHashMap<>();
        JsonNode fileNode = normalized.path("file");
        String fileName = MFilesDocumentBuilder.text(fileNode, "name");
        String title = Optional.ofNullable(MFilesDocumentBuilder.text(normalized, "title")).orElse(fileName);
        metadata.put("name", List.of(fileName != null ? fileName : title != null ? title : key));
        if (title != null) {
            metadata.put("title", List.of(title));
        }
        String mimeType = Optional.ofNullable(MFilesDocumentBuilder.text(normalized, "mimeType"))
                .orElse(MFilesDocumentBuilder.mimeType(fileName));
        if (mimeType != null) {
            metadata.put("mimeType", List.of(mimeType));
        }
        metadata.put("mfiles.vaultGuid", List.of(settings.vaultKey()));
        if (vaultName != null) {
            metadata.put("mfiles.vaultName", List.of(vaultName));
        }
        metadata.put("mfiles.objType", List.of(Integer.toString(type)));
        metadata.put("mfiles.objId", List.of(Integer.toString(id)));
        metadata.put("mfiles.version", List.of(Integer.toString(version)));
        metadata.put("mfiles.isLatestVersion", List.of("true"));
        if (settings.includeProperties()) {
            normalized.path("metadata").properties().forEach(field -> {
                List<String> values = new ArrayList<>();
                if (field.getValue().isArray()) {
                    field.getValue().forEach(v -> values.add(v.asText()));
                } else if (!field.getValue().isNull()) {
                    values.add(field.getValue().asText());
                }
                if (!values.isEmpty()) {
                    metadata.put(field.getKey(), values);
                }
            });
        }
        MFilesSecurityMapper.Mapped security = settings.includeAcls()
                ? securityMapper.fromTokens(normalized.path("aclTokens"), normalized.path("denyTokens"))
                : MFilesSecurityMapper.publicAccess();
        if (!security.users().isEmpty()) {
            metadata.put("mfiles_identity_users", List.of(String.join(",", security.users())));
        }
        if (!security.groups().isEmpty()) {
            metadata.put("mfiles_identity_groups", List.of(String.join(",", security.groups())));
        }

        InputStream content = null;
        String downloadUrl = MFilesDocumentBuilder.text(fileNode, "downloadUrl");
        if (settings.includeFiles() && downloadUrl != null && !downloadUrl.isBlank()) {
            Path temp = Files.createTempFile("oc-mfiles-", ".bin");
            try {
                long length = client.downloadUrl(downloadUrl, temp, settings.maxFileSizeBytes());
                if (length < 0) {
                    Files.deleteIfExists(temp);
                    log.info("M-Files object {} exceeds max-file-size-bytes; metadata only.", key);
                } else {
                    content = new TempFileInputStream(temp);
                    metadata.put("mfiles.fileSize", List.of(Long.toString(length)));
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        }
        Instant lastModified = MFilesValues.parseInstant(MFilesDocumentBuilder.text(normalized, "lastModifiedUtc")).orElseGet(Instant::now);
        emit(sink, new RepositoryDocument(objectId, objectId, content, metadata, security.acl(), security.security(), lastModified,
                DocumentAction.UPSERT));
        crawl.documents.incrementAndGet();
        if (crawl.previous != null) {
            crawl.next.put(key, new MFilesCrawlState.Entry(versionString, List.of(objectId)));
        }
    }

    // ------------------------------------------------------------------ principals

    /** For {@code principal-format: name}: the users and user groups value lists, read once per connection. */
    private Optional<String> principalName(int id, boolean group) {
        Map<String, String> names = principalNames;
        if (names == null) {
            synchronized (this) {
                names = principalNames;
                if (names == null) {
                    names = new HashMap<>();
                    readNames(settings.usersValueList(), "user", names);
                    readNames(settings.userGroupsValueList(), "group", names);
                    principalNames = names;
                }
            }
        }
        return Optional.ofNullable(names.get((group ? "group:" : "user:") + id));
    }

    private void readNames(int valueList, String kind, Map<String, String> names) {
        try {
            for (JsonNode item : client.getValueListItems(valueList)) {
                String name = item.path("Name").asText(item.path("DisplayValue").asText(""));
                if (!name.isBlank()) {
                    names.put(kind + ":" + item.path("ID").asInt(), name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("M-Files value list {} ({}s) is unreadable ({}); those identities keep their mfiles:{}:<id> token.",
                    valueList, kind, e.getMessage(), kind);
        }
    }

    private static void emit(FluxSink<RepositoryDocument> sink, RepositoryDocument document) {
        synchronized (sink) {
            sink.next(document);
        }
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        String prefix = settings.propertyPrefix();
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("mfiles.vaultGuid", "STRING", "Vault GUID (without braces)"),
                new ConnectorSchema.SchemaField("mfiles.vaultName", "STRING", "Vault name"),
                new ConnectorSchema.SchemaField("mfiles.objType", "INTEGER", "Object type id (0 = Document)"),
                new ConnectorSchema.SchemaField("mfiles.objId", "INTEGER", "Object id"),
                new ConnectorSchema.SchemaField("mfiles.version", "INTEGER", "Object version"),
                new ConnectorSchema.SchemaField("mfiles.isLatestVersion", "BOOLEAN", "Whether this is the latest version"),
                new ConnectorSchema.SchemaField("mfiles.displayId", "STRING", "Display id"),
                new ConnectorSchema.SchemaField("mfiles.title", "STRING", "Name or title"),
                new ConnectorSchema.SchemaField("mfiles.classId", "INTEGER", "Class id"),
                new ConnectorSchema.SchemaField("mfiles.className", "STRING", "Class name"),
                new ConnectorSchema.SchemaField("mfiles.singleFile", "BOOLEAN", "Single-file document"),
                new ConnectorSchema.SchemaField("mfiles.fileCount", "INTEGER", "Number of files of the object version"),
                new ConnectorSchema.SchemaField("mfiles.fileId", "INTEGER", "File id"),
                new ConnectorSchema.SchemaField("mfiles.fileName", "STRING", "File name with extension"),
                new ConnectorSchema.SchemaField("mfiles.fileSize", "LONG", "File size in bytes"),
                new ConnectorSchema.SchemaField("mfiles.parentId", "STRING", "Object id of a multi-file document's file"),
                new ConnectorSchema.SchemaField("mfiles.created", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("mfiles.createdBy", "STRING", "Created by"),
                new ConnectorSchema.SchemaField("mfiles.lastModified", "TIMESTAMP", "Last modification timestamp"),
                new ConnectorSchema.SchemaField("mfiles.lastModifiedBy", "STRING", "Last modified by"),
                new ConnectorSchema.SchemaField("mfiles.workflow", "STRING", "Workflow"),
                new ConnectorSchema.SchemaField("mfiles.state", "STRING", "Workflow state"),
                new ConnectorSchema.SchemaField(prefix + "<propertyName>", "STRING", "Every property value, keyed by the property "
                        + "definition name in lower camel case; lookup ids under " + prefix + "<propertyName>_id")
        ));
    }
}
