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
package org.opencrawling.zammad;

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
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.opencrawling.zammad.ZammadDocumentBuilder.AttachmentRef;
import org.opencrawling.zammad.ZammadDocumentBuilder.TicketContext;
import org.opencrawling.zammad.ZammadProperties.ArticleGranularity;
import org.opencrawling.zammad.ZammadProperties.CrawlMode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zammad Helpdesk &amp; ITSM repository connector (issue #342): crawls support tickets, public and internal
 * conversation articles, binary ticket attachments, and Knowledge Base localized answers via the Zammad REST API v1.
 *
 * <p>Enforces strict security isolation between public customer-accessible ticket replies ({@code internal: false})
 * and confidential internal agent notes ({@code internal: true}) through {@link ZammadSecurityMapper}.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "zammad")
public class ZammadRepositoryConnector implements DocumentFetchingRepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(ZammadRepositoryConnector.class);

    private static final Pattern TICKET_URI_PATTERN =
            Pattern.compile("^zammad://[^/]+/tickets/(\\d+)/(public|internal|articles/(\\d+)(?:/attachments/(\\d+))?)$");
    private static final Pattern SHORT_TICKET_ID_PATTERN =
            Pattern.compile("^(?:ticket:)?(\\d+)(?::(public|internal))?$");

    private record OrgInfo(String name, boolean shared) {
    }

    private final ZammadProperties settings;
    private ZammadClient client;
    private ZammadSecurityMapper securityMapper;

    private final Map<Long, String> groupsById = new ConcurrentHashMap<>();
    private final Map<Long, String> statesById = new ConcurrentHashMap<>();
    private final Map<Long, String> prioritiesById = new ConcurrentHashMap<>();
    private final Map<Long, String> rolesById = new ConcurrentHashMap<>();
    private final Map<Long, String> userEmailsById = new ConcurrentHashMap<>();
    private final Map<Long, OrgInfo> orgsById = new ConcurrentHashMap<>();
    private volatile boolean connected;

    /** Used by SPI discovery. */
    public ZammadRepositoryConnector() {
        this(ZammadProperties.fromConfiguration(Map.of()));
    }

    @Autowired
    public ZammadRepositoryConnector(Environment environment) {
        this(ZammadProperties.fromEnvironment(environment));
    }

    public ZammadRepositoryConnector(ZammadProperties settings) {
        this.settings = settings;
        this.securityMapper = new ZammadSecurityMapper(settings);
    }

    public ZammadRepositoryConnector(ZammadProperties settings, ZammadClient client) {
        this.settings = settings;
        this.client = client;
        this.securityMapper = new ZammadSecurityMapper(settings);
    }

    @Override
    public String getName() {
        return "ZammadRepositoryConnector";
    }

    @Override
    public String sourceInstance() {
        return settings != null ? settings.baseUrl() : null;
    }

    public ZammadProperties settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Zammad repository connector is not configured: " + String.join(" ", problems));
        }
        if (client == null) {
            client = new ZammadClient(settings);
        }
        this.securityMapper = new ZammadSecurityMapper(settings);
        JsonNode me = client.getCurrentUser();
        String login = ZammadDocumentBuilder.textOrDefault(me, "email",
                ZammadDocumentBuilder.textOrDefault(me, "login", "unknown"));

        groupsById.clear();
        for (JsonNode group : client.listGroups()) {
            long id = group.path("id").asLong(-1L);
            String name = ZammadDocumentBuilder.text(group, "name");
            if (id > 0 && name != null && group.path("active").asBoolean(true)) {
                groupsById.put(id, name);
            }
        }

        statesById.clear();
        for (JsonNode state : client.listTicketStates()) {
            long id = state.path("id").asLong(-1L);
            String name = ZammadDocumentBuilder.text(state, "name");
            if (id > 0 && name != null) {
                statesById.put(id, name);
            }
        }

        prioritiesById.clear();
        for (JsonNode prio : client.listTicketPriorities()) {
            long id = prio.path("id").asLong(-1L);
            String name = ZammadDocumentBuilder.text(prio, "name");
            if (id > 0 && name != null) {
                prioritiesById.put(id, name);
            }
        }

        rolesById.clear();
        for (JsonNode role : client.listRoles()) {
            long id = role.path("id").asLong(-1L);
            String name = ZammadDocumentBuilder.text(role, "name");
            if (id > 0 && name != null && role.path("active").asBoolean(true)) {
                rolesById.put(id, name);
            }
        }

        connected = true;
        log.info("Connected to Zammad at {} as '{}' ({} groups, {} states, {} roles loaded).",
                client.getBaseUrl(), login, groupsById.size(), statesById.size(), rolesById.size());
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        if (client != null) {
            client.close();
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
                throw new DiscoveryException("Cannot connect to Zammad at " + settings.baseUrl() + ": " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ Crawl Scan

    private static final class CrawlSession {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final Set<String> seenTicketIds = ConcurrentHashMap.newKeySet();
        final Set<String> seenKbIds = ConcurrentHashMap.newKeySet();
        Map<String, ZammadCrawlState.Entry> previousTickets = Map.of();
        Map<String, ZammadCrawlState.Entry> previousKb = Map.of();
        final Map<String, ZammadCrawlState.Entry> nextTickets = new ConcurrentHashMap<>();
        final Map<String, ZammadCrawlState.Entry> nextKb = new ConcurrentHashMap<>();
        final Set<String> removedTickets = ConcurrentHashMap.newKeySet();
        final Set<String> removedKb = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                connect();
                CrawlSession crawl = new CrawlSession();
                Path statePath = ZammadCrawlState.file(settings);
                ZammadCrawlState.State previousState = ZammadCrawlState.load(statePath);
                crawl.previousTickets = previousState.tickets();
                crawl.previousKb = previousState.kbAnswers();

                Instant scanStart = Instant.now();
                String trimmedPath = basePath == null ? "" : basePath.strip();
                boolean hasOverride = !trimmedPath.isEmpty()
                        && !"default".equalsIgnoreCase(trimmedPath)
                        && !"/".equals(trimmedPath);

                if (settings.includeTickets() && !trimmedPath.startsWith("kb-category:") && !trimmedPath.startsWith("/KnowledgeBase")) {
                    crawlTickets(trimmedPath, hasOverride, previousState.watermark(), crawl, sink);
                }

                if (settings.includeKnowledgeBase() && !trimmedPath.startsWith("group:") && !trimmedPath.startsWith("/Groups")) {
                    crawlKnowledgeBase(trimmedPath, crawl, sink);
                }

                if (!hasOverride) {
                    Map<String, ZammadCrawlState.Entry> mergedTickets = new LinkedHashMap<>(crawl.previousTickets);
                    crawl.removedTickets.forEach(mergedTickets::remove);
                    mergedTickets.putAll(crawl.nextTickets);

                    Map<String, ZammadCrawlState.Entry> mergedKb = new LinkedHashMap<>(crawl.previousKb);
                    crawl.removedKb.forEach(mergedKb::remove);
                    mergedKb.putAll(crawl.nextKb);

                    String nextWatermark = crawl.failed.get() == 0
                            ? scanStart.minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).toString()
                            : previousState.watermark();
                    ZammadCrawlState.save(statePath, new ZammadCrawlState.State(nextWatermark, mergedTickets, mergedKb));
                }

                log.info("Zammad scan of {} completed: {} documents, {} unchanged, {} tombstones, {} skipped, {} failed.",
                        settings.baseUrl(), crawl.documents.get(), crawl.unchanged.get(),
                        crawl.tombstones.get(), crawl.skipped.get(), crawl.failed.get());
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Zammad scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                disconnect();
            }
        });
    }

    private void crawlTickets(
            String overridePath,
            boolean hasOverride,
            String watermark,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        String searchQuery = resolveTicketSearchQuery(overridePath, hasOverride, watermark);
        boolean fullEnumeration = !hasOverride && (settings.crawlMode() == CrawlMode.FULL || watermark == null);

        int page = 1;
        int pageSize = settings.pageSize();
        while (true) {
            List<JsonNode> tickets = (searchQuery != null)
                    ? client.searchTicketsPage(searchQuery, page, pageSize)
                    : client.listTicketsPage(page, pageSize);
            if (tickets.isEmpty()) {
                break;
            }
            List<JsonNode> batch = new ArrayList<>();
            for (JsonNode ticket : tickets) {
                long ticketId = ticket.path("id").asLong(-1L);
                if (ticketId > 0 && crawl.seenTicketIds.add(Long.toString(ticketId))) {
                    batch.add(ticket);
                }
            }
            processTicketBatch(batch, crawl, sink);
            if (tickets.size() < pageSize) {
                break;
            }
            page++;
        }

        if (fullEnumeration && !crawl.previousTickets.isEmpty()) {
            for (Map.Entry<String, ZammadCrawlState.Entry> prev : crawl.previousTickets.entrySet()) {
                String ticketId = prev.getKey();
                if (!crawl.seenTicketIds.contains(ticketId)) {
                    emitTombstones(prev.getValue().ids(), ticketId, "ticket", crawl, sink);
                    crawl.removedTickets.add(ticketId);
                }
            }
        }
    }

    private String resolveTicketSearchQuery(String overridePath, boolean hasOverride, String watermark) {
        if (hasOverride) {
            if (overridePath.startsWith("group:")) {
                long groupId = parseLong(overridePath.substring("group:".length()), -1L);
                String groupName = groupsById.get(groupId);
                return groupName != null ? "group.name:\"" + groupName + "\"" : "group_id:" + groupId;
            }
            if (overridePath.startsWith("/Groups/")) {
                String groupName = overridePath.substring("/Groups/".length()).replaceAll("^/+|/+$", "");
                return "group.name:\"" + groupName + "\"";
            }
            return overridePath;
        }
        if (settings.crawlMode() == CrawlMode.SEARCH_DELTA && watermark != null && !watermark.isBlank()) {
            return "updated_at:>=" + watermark;
        }
        return null;
    }

    @SuppressWarnings("preview")
    private void processTicketBatch(List<JsonNode> tickets, CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws InterruptedException {
        if (tickets.isEmpty()) {
            return;
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode ticket : tickets) {
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handleTicket(ticket, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("Failed to process Zammad ticket {}: {}", ticket.path("id").asText("?"), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private void handleTicket(JsonNode ticketNode, CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        long ticketId = ticketNode.path("id").asLong(-1L);
        if (ticketId <= 0) {
            return;
        }
        String key = Long.toString(ticketId);
        ZammadCrawlState.Entry known = crawl.previousTickets.get(key);

        String groupName = resolveGroupName(ticketNode);
        String stateName = resolveStateName(ticketNode);

        if (!settings.acceptsGroup(groupName) || !settings.acceptsTicketState(stateName)) {
            if (known != null) {
                emitTombstones(known.ids(), key, "ticket", crawl, sink);
                crawl.removedTickets.add(key);
            } else {
                crawl.skipped.incrementAndGet();
            }
            return;
        }

        TicketContext ctx = buildTicketContext(ticketNode, groupName, stateName);
        OrgInfo orgInfo = resolveOrganizationInfo(ticketNode);

        ZammadSecurityMapper.Mapped publicSecurity = securityMapper.forTicket(
                ctx.groupName(),
                ctx.customerEmail(),
                orgInfo.name(),
                orgInfo.shared(),
                List.of(),
                false
        );
        ZammadSecurityMapper.Mapped internalSecurity = securityMapper.forTicket(
                ctx.groupName(),
                ctx.customerEmail(),
                orgInfo.name(),
                orgInfo.shared(),
                List.of(),
                true
        );

        String aclFingerprint = publicSecurity.fingerprint() + "||" + internalSecurity.fingerprint();
        if (known != null && ctx.updatedAt() != null && ctx.updatedAt().equals(known.updatedAt())
                && aclFingerprint.equals(known.aclFingerprint())) {
            crawl.unchanged.incrementAndGet();
            crawl.nextTickets.put(key, known);
            return;
        }

        List<JsonNode> articles = new ArrayList<>(client.getTicketArticles(ticketId));
        articles.sort(Comparator.comparingLong(a -> a.path("id").asLong(0L)));

        List<String> emittedIds = new ArrayList<>();

        if (settings.articleGranularity() == ArticleGranularity.PER_ARTICLE) {
            for (JsonNode article : articles) {
                boolean isInternal = article.path("internal").asBoolean(false);
                if (isInternal && !settings.includeInternalArticles()) {
                    continue;
                }
                ZammadSecurityMapper.Mapped sec = isInternal ? internalSecurity : publicSecurity;
                RepositoryDocument articleDoc = ZammadDocumentBuilder.buildArticleDocument(ctx, article, sec);
                emit(sink, articleDoc);
                emittedIds.add(articleDoc.id());
                crawl.documents.incrementAndGet();

                if (settings.includeAttachments()) {
                    emitArticleAttachments(ctx, article, articleDoc.id(), sec, emittedIds, crawl, sink);
                }
            }
        } else {
            List<JsonNode> publicArticles = new ArrayList<>();
            List<JsonNode> internalArticles = new ArrayList<>();
            for (JsonNode article : articles) {
                if (article.path("internal").asBoolean(false)) {
                    internalArticles.add(article);
                } else {
                    publicArticles.add(article);
                }
            }

            RepositoryDocument publicDoc = ZammadDocumentBuilder.buildTicketThread(ctx, publicArticles, false, publicSecurity);
            emit(sink, publicDoc);
            emittedIds.add(publicDoc.id());
            crawl.documents.incrementAndGet();

            if (settings.includeAttachments()) {
                for (JsonNode article : publicArticles) {
                    emitArticleAttachments(ctx, article, publicDoc.id(), publicSecurity, emittedIds, crawl, sink);
                }
            }

            if (settings.includeInternalArticles()
                    && settings.articleGranularity() == ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL
                    && !internalArticles.isEmpty()) {
                RepositoryDocument internalDoc = ZammadDocumentBuilder.buildTicketThread(ctx, internalArticles, true, internalSecurity);
                emit(sink, internalDoc);
                emittedIds.add(internalDoc.id());
                crawl.documents.incrementAndGet();

                if (settings.includeAttachments()) {
                    for (JsonNode article : internalArticles) {
                        emitArticleAttachments(ctx, article, internalDoc.id(), internalSecurity, emittedIds, crawl, sink);
                    }
                }
            }
        }

        if (known != null) {
            Set<String> stale = new LinkedHashSet<>(known.ids());
            stale.removeAll(emittedIds);
            emitTombstones(stale, key, "ticket", crawl, sink);
        }

        crawl.nextTickets.put(key, new ZammadCrawlState.Entry(ctx.updatedAt(), aclFingerprint, List.copyOf(emittedIds)));
    }

    private void emitArticleAttachments(
            TicketContext ctx,
            JsonNode article,
            String parentDocId,
            ZammadSecurityMapper.Mapped security,
            List<String> emittedIds,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        for (AttachmentRef att : ZammadDocumentBuilder.attachments(article)) {
            if (settings.maxAttachmentSizeBytes() > 0 && att.sizeBytes() > settings.maxAttachmentSizeBytes()) {
                log.info("Skipping Zammad attachment {} on ticket {} ({} bytes exceeds max-attachment-size-bytes).",
                        att.filename(), ctx.ticketId(), att.sizeBytes());
                continue;
            }
            Path temp = Files.createTempFile("oc-zammad-att-", ".bin");
            try {
                long written = client.downloadAttachment(
                        ctx.ticketId(), att.articleId(), att.attachmentId(), temp, settings.maxAttachmentSizeBytes());
                if (written < 0) {
                    Files.deleteIfExists(temp);
                    log.info("Zammad attachment {} on ticket {} exceeded max-attachment-size-bytes during stream; skipped.",
                            att.filename(), ctx.ticketId());
                    continue;
                }
                InputStream stream = new TempFileInputStream(temp);
                RepositoryDocument attDoc = ZammadDocumentBuilder.buildAttachmentDocument(
                        ctx, att, parentDocId, stream, written, security);
                emit(sink, attDoc);
                emittedIds.add(attDoc.id());
                crawl.documents.incrementAndGet();
            } catch (IOException | InterruptedException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ Knowledge Base Crawl

    private void crawlKnowledgeBase(String overridePath, CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        Optional<JsonNode> initOpt = client.getKnowledgeBaseInit();
        if (initOpt.isEmpty()) {
            return;
        }
        JsonNode init = initOpt.get();
        Long filterCategoryId = null;
        if (overridePath != null && overridePath.startsWith("kb-category:")) {
            filterCategoryId = parseLong(overridePath.substring("kb-category:".length()), -1L);
        }

        List<KbAnswerRecord> answers = parseKnowledgeBaseAnswers(init);
        List<String> activeGroups = new ArrayList<>(groupsById.values());

        for (KbAnswerRecord answer : answers) {
            if (filterCategoryId != null && filterCategoryId > 0 && answer.categoryId() != filterCategoryId) {
                continue;
            }
            String key = Long.toString(answer.answerId());
            crawl.seenKbIds.add(key);
            ZammadCrawlState.Entry known = crawl.previousKb.get(key);

            if (!answer.active()) {
                if (known != null) {
                    emitTombstones(known.ids(), key, "kb_answer", crawl, sink);
                    crawl.removedKb.add(key);
                }
                continue;
            }

            ZammadSecurityMapper.Mapped security = securityMapper.forKnowledgeBase(
                    answer.readerRoles(), activeGroups, answer.internal());
            String fingerprint = security.fingerprint();

            if (known != null && answer.updatedAt() != null && answer.updatedAt().equals(known.updatedAt())
                    && fingerprint.equals(known.aclFingerprint())) {
                crawl.unchanged.incrementAndGet();
                crawl.nextKb.put(key, known);
                continue;
            }

            List<KbTranslation> translations = answer.translations();
            if (translations.isEmpty() || translations.stream().allMatch(t -> t.body() == null || t.body().isBlank())) {
                Optional<JsonNode> fullAnswer = client.getKnowledgeBaseAnswer(answer.kbId(), answer.answerId());
                if (fullAnswer.isPresent()) {
                    translations = extractAnswerTranslations(fullAnswer.get(), answer.localesById());
                }
            }

            List<String> emittedIds = new ArrayList<>();
            for (KbTranslation tr : translations) {
                RepositoryDocument doc = ZammadDocumentBuilder.buildKnowledgeBaseAnswer(
                        settings.instanceKey(),
                        settings.baseUrl(),
                        answer.kbId(),
                        answer.answerId(),
                        answer.categoryId(),
                        answer.categoryTitle(),
                        tr.locale(),
                        tr.title(),
                        tr.body(),
                        answer.internal(),
                        answer.updatedAt(),
                        security
                );
                emit(sink, doc);
                emittedIds.add(doc.id());
                crawl.documents.incrementAndGet();
            }

            if (known != null) {
                Set<String> stale = new LinkedHashSet<>(known.ids());
                stale.removeAll(emittedIds);
                emitTombstones(stale, key, "kb_answer", crawl, sink);
            }
            crawl.nextKb.put(key, new ZammadCrawlState.Entry(answer.updatedAt(), fingerprint, List.copyOf(emittedIds)));
        }

        if (filterCategoryId == null && !crawl.previousKb.isEmpty()) {
            for (Map.Entry<String, ZammadCrawlState.Entry> prev : crawl.previousKb.entrySet()) {
                if (!crawl.seenKbIds.contains(prev.getKey())) {
                    emitTombstones(prev.getValue().ids(), prev.getKey(), "kb_answer", crawl, sink);
                    crawl.removedKb.add(prev.getKey());
                }
            }
        }
    }

    private record KbTranslation(String locale, String title, String body) {
    }

    private record KbAnswerRecord(
            long kbId,
            long answerId,
            long categoryId,
            String categoryTitle,
            boolean active,
            boolean internal,
            String updatedAt,
            List<String> readerRoles,
            List<KbTranslation> translations,
            Map<Long, String> localesById
    ) {
    }

    private List<KbAnswerRecord> parseKnowledgeBaseAnswers(JsonNode init) {
        List<KbAnswerRecord> results = new ArrayList<>();
        JsonNode assets = init.has("assets") ? init.path("assets") : init;

        Map<Long, String> localesById = new LinkedHashMap<>();
        JsonNode localesNode = assets.path("KnowledgeBaseLocale");
        if (localesNode.isObject()) {
            localesNode.forEach(loc -> {
                long id = loc.path("id").asLong(-1L);
                String code = ZammadDocumentBuilder.textOrDefault(loc, "system_locale",
                        ZammadDocumentBuilder.textOrDefault(loc, "locale", "en"));
                if (id > 0) {
                    localesById.put(id, code);
                }
            });
        }

        Map<Long, String> categoryTitles = new LinkedHashMap<>();
        JsonNode catTransNode = assets.path("KnowledgeBaseCategoryTranslation");
        if (catTransNode.isObject()) {
            catTransNode.forEach(tr -> {
                long catId = tr.path("category_id").asLong(-1L);
                String title = ZammadDocumentBuilder.text(tr, "title");
                if (catId > 0 && title != null) {
                    categoryTitles.putIfAbsent(catId, title);
                }
            });
        }

        Map<Long, Long> categoryToKbId = new LinkedHashMap<>();
        Map<Long, List<String>> categoryReaderRoles = new LinkedHashMap<>();
        JsonNode categoriesNode = assets.path("KnowledgeBaseCategory");
        if (categoriesNode.isObject() || categoriesNode.isArray()) {
            categoriesNode.forEach(cat -> {
                long catId = cat.path("id").asLong(-1L);
                long kbId = cat.path("knowledge_base_id").asLong(1L);
                if (catId > 0) {
                    categoryToKbId.put(catId, kbId);
                    String directTitle = ZammadDocumentBuilder.text(cat, "title");
                    if (directTitle != null) {
                        categoryTitles.putIfAbsent(catId, directTitle);
                    }
                    categoryReaderRoles.put(catId, extractCategoryRoles(cat, assets.path("KnowledgeBasePermission")));
                }
            });
        }

        Map<Long, String> contentBodies = new LinkedHashMap<>();
        JsonNode contentsNode = assets.path("KnowledgeBaseAnswerTranslationContent");
        if (contentsNode.isObject()) {
            contentsNode.forEach(c -> {
                long id = c.path("id").asLong(-1L);
                String body = ZammadDocumentBuilder.text(c, "body");
                if (id > 0 && body != null) {
                    contentBodies.put(id, body);
                }
            });
        }

        Map<Long, List<KbTranslation>> translationsByAnswer = new LinkedHashMap<>();
        JsonNode ansTransNode = assets.path("KnowledgeBaseAnswerTranslation");
        if (ansTransNode.isObject()) {
            ansTransNode.forEach(tr -> {
                long answerId = tr.path("answer_id").asLong(-1L);
                if (answerId <= 0) {
                    return;
                }
                long kbLocaleId = tr.path("kb_locale_id").asLong(-1L);
                String locale = ZammadDocumentBuilder.textOrDefault(tr, "locale",
                        localesById.getOrDefault(kbLocaleId, "en"));
                String title = ZammadDocumentBuilder.textOrDefault(tr, "title", "KB Answer #" + answerId);
                long contentId = tr.path("content_id").asLong(-1L);
                String body = firstNonBlank(
                        ZammadDocumentBuilder.text(tr, "body"),
                        ZammadDocumentBuilder.text(tr.path("content"), "body"),
                        contentBodies.get(contentId));
                translationsByAnswer.computeIfAbsent(answerId, k -> new ArrayList<>())
                        .add(new KbTranslation(locale, title, body));
            });
        }

        JsonNode answersNode = assets.has("KnowledgeBaseAnswer")
                ? assets.path("KnowledgeBaseAnswer")
                : init.path("answers");
        if (answersNode.isObject() || answersNode.isArray()) {
            answersNode.forEach(ans -> {
                long answerId = ans.path("id").asLong(-1L);
                if (answerId <= 0) {
                    return;
                }
                long categoryId = ans.path("category_id").asLong(1L);
                long kbId = ans.path("knowledge_base_id").asLong(categoryToKbId.getOrDefault(categoryId, 1L));
                String categoryTitle = firstNonBlank(
                        ZammadDocumentBuilder.text(ans, "category_title"),
                        categoryTitles.get(categoryId),
                        "Category-" + categoryId);
                String archivedAt = ZammadDocumentBuilder.text(ans, "archived_at");
                String publishedAt = ZammadDocumentBuilder.text(ans, "published_at");
                String internalAt = ZammadDocumentBuilder.text(ans, "internal_at");
                boolean active = (archivedAt == null) && (publishedAt != null || internalAt != null
                        || ans.path("published").asBoolean(false) || ans.path("internal").asBoolean(false));
                boolean internal = ans.path("internal").asBoolean(false)
                        || (internalAt != null && publishedAt == null);
                String updatedAt = firstNonBlank(
                        ZammadDocumentBuilder.text(ans, "updated_at"),
                        internalAt,
                        publishedAt);
                List<String> readerRoles = categoryReaderRoles.getOrDefault(categoryId, List.of());
                List<KbTranslation> translations = new ArrayList<>(
                        translationsByAnswer.getOrDefault(answerId, List.of()));
                if (translations.isEmpty()) {
                    translations.addAll(extractAnswerTranslations(ans, localesById));
                }
                results.add(new KbAnswerRecord(
                        kbId, answerId, categoryId, categoryTitle, active, internal,
                        updatedAt, readerRoles, translations, localesById));
            });
        }
        return results;
    }

    private List<String> extractCategoryRoles(JsonNode categoryNode, JsonNode permissionsAssets) {
        List<String> roles = new ArrayList<>();
        JsonNode inlinePerms = categoryNode.path("permissions");
        if (inlinePerms.isArray()) {
            for (JsonNode perm : inlinePerms) {
                addRoleFromPermission(perm, roles);
            }
        }
        long catId = categoryNode.path("id").asLong(-1L);
        if (catId > 0 && permissionsAssets != null && permissionsAssets.isObject()) {
            permissionsAssets.forEach(perm -> {
                if (perm.path("permissionable_id").asLong(-1L) == catId) {
                    addRoleFromPermission(perm, roles);
                }
            });
        }
        return List.copyOf(roles);
    }

    private void addRoleFromPermission(JsonNode perm, List<String> roles) {
        String access = ZammadDocumentBuilder.textOrDefault(perm, "access", "reader");
        if ("none".equalsIgnoreCase(access)) {
            return;
        }
        String roleName = ZammadDocumentBuilder.text(perm, "role");
        if (roleName == null) {
            long roleId = perm.path("role_id").asLong(-1L);
            roleName = rolesById.get(roleId);
        }
        if (roleName != null && !roleName.isBlank()) {
            roles.add(roleName);
        }
    }

    private List<KbTranslation> extractAnswerTranslations(JsonNode answerPayload, Map<Long, String> localesById) {
        JsonNode root = answerPayload.has("assets") ? answerPayload : answerPayload;
        List<KbTranslation> list = new ArrayList<>();
        JsonNode translations = root.path("translations");
        if (translations.isArray()) {
            for (JsonNode tr : translations) {
                long locId = tr.path("kb_locale_id").asLong(-1L);
                String locale = ZammadDocumentBuilder.textOrDefault(tr, "locale",
                        localesById.getOrDefault(locId, "en"));
                String title = ZammadDocumentBuilder.textOrDefault(tr, "title", "Knowledge Base Answer");
                String body = firstNonBlank(
                        ZammadDocumentBuilder.text(tr, "body"),
                        ZammadDocumentBuilder.text(tr.path("content"), "body"));
                if (body != null) {
                    list.add(new KbTranslation(locale, title, body));
                }
            }
        }
        if (list.isEmpty() && answerPayload.has("assets")) {
            Map<Long, String> bodies = new LinkedHashMap<>();
            answerPayload.path("assets").path("KnowledgeBaseAnswerTranslationContent").forEach(c -> {
                long id = c.path("id").asLong(-1L);
                String b = ZammadDocumentBuilder.text(c, "body");
                if (id > 0 && b != null) {
                    bodies.put(id, b);
                }
            });
            answerPayload.path("assets").path("KnowledgeBaseAnswerTranslation").forEach(tr -> {
                long locId = tr.path("kb_locale_id").asLong(-1L);
                String locale = ZammadDocumentBuilder.textOrDefault(tr, "locale",
                        localesById.getOrDefault(locId, "en"));
                String title = ZammadDocumentBuilder.textOrDefault(tr, "title", "Knowledge Base Answer");
                long contentId = tr.path("content_id").asLong(-1L);
                String body = firstNonBlank(
                        ZammadDocumentBuilder.text(tr, "body"),
                        ZammadDocumentBuilder.text(tr.path("content"), "body"),
                        bodies.get(contentId));
                if (body != null) {
                    list.add(new KbTranslation(locale, title, body));
                }
            });
        }
        return list;
    }

    // ------------------------------------------------------------------ Pre-Crawl Discovery & On-Demand Fetch

    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        String start = request.start() == null || request.start().isBlank() ? "/" : request.start().strip();
        try {
            DiscoveryNode startNode = resolveFolderNode(start, filter);
            Deque<DiscoveryNode> queue = new ArrayDeque<>();
            queue.add(startNode);
            List<DiscoveryNode> resultNodes = new ArrayList<>();
            long hidden = 0;
            TruncationReason truncation = null;

            while (!queue.isEmpty()) {
                if (System.nanoTime() - deadline > 0) {
                    return new DiscoveryResult(resultNodes, hidden, TruncationReason.TIMEOUT);
                }
                DiscoveryNode current = queue.poll();
                List<DiscoveryNode> children = listChildrenOfFolder(current, request.includeDocuments());
                boolean[] visible = VisibilityFilter.decide(filter, children);
                for (int i = 0; i < visible.length; i++) {
                    if (!visible[i]) {
                        hidden++;
                        continue;
                    }
                    DiscoveryNode child = children.get(i);
                    if (resultNodes.size() >= request.maxNodes()) {
                        return new DiscoveryResult(resultNodes, hidden, TruncationReason.MAX_NODES);
                    }
                    resultNodes.add(child);
                    if (child.type() == DiscoveryNode.NodeType.FOLDER) {
                        if (child.depth() < request.maxDepth()) {
                            queue.add(child);
                        } else {
                            truncation = TruncationReason.MAX_DEPTH;
                        }
                    }
                }
            }
            return new DiscoveryResult(resultNodes, hidden, truncation);
        } catch (IOException e) {
            throw new DiscoveryException("Zammad discovery exploration failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Zammad discovery exploration was interrupted.", e);
        }
    }

    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        String scope = request.scope() == null || request.scope().isBlank() ? "/" : request.scope().strip();
        try {
            List<DiscoveryNode> samples = new ArrayList<>();
            long scanned = 0;
            long matched = 0;
            long hidden = 0;

            String query = null;
            if (!"/".equals(scope)) {
                if (scope.startsWith("group:") || scope.startsWith("/Groups/")) {
                    DiscoveryNode groupFolder = resolveFolderNode(scope, filter);
                    String groupName = groupFolder.name();
                    query = "group.name:\"" + groupName + "\"";
                } else if (scope.contains(":")) {
                    query = scope;
                } else {
                    throw new IllegalArgumentException("No Zammad folder or search scope '" + scope + "' in this connector's scope.");
                }
            }

            int page = 1;
            int pageSize = settings.pageSize();
            while (true) {
                if (System.nanoTime() - deadline > 0) {
                    return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.TIMEOUT);
                }
                List<JsonNode> tickets = (query != null)
                        ? client.searchTicketsPage(query, page, pageSize)
                        : client.listTicketsPage(page, pageSize);
                if (tickets.isEmpty()) {
                    break;
                }
                List<DiscoveryNode> pageNodes = new ArrayList<>();
                for (JsonNode ticket : tickets) {
                    String groupName = resolveGroupName(ticket);
                    String stateName = resolveStateName(ticket);
                    if (!settings.acceptsGroup(groupName) || !settings.acceptsTicketState(stateName)) {
                        continue;
                    }
                    pageNodes.addAll(ticketDiscoveryNodes(ticket, groupName, stateName, "group:" + ticket.path("group_id").asLong(0L), 1));
                }
                boolean[] visible = VisibilityFilter.decide(filter, pageNodes);
                for (int i = 0; i < visible.length; i++) {
                    if (scanned >= request.maxScan()) {
                        return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.MAX_SCAN);
                    }
                    scanned++;
                    if (!visible[i]) {
                        hidden++;
                        continue;
                    }
                    matched++;
                    if (samples.size() < request.sampleSize()) {
                        samples.add(pageNodes.get(i));
                    }
                }
                if (tickets.size() < pageSize) {
                    break;
                }
                page++;
            }
            return new DryRunResult(matched, CountAccuracy.EXACT, samples, hidden, null);
        } catch (IOException e) {
            throw new DiscoveryException("Zammad dry run failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Zammad dry run was interrupted.", e);
        }
    }

    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        ensureConnected();
        String ref = (reference == null || reference.isBlank()) ? "/" : reference.strip();
        return resolveFolderNode(ref, filter);
    }

    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        ensureConnected();
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
        }
        String trimmed = documentId.strip();
        long ticketId;
        boolean internal = false;

        Matcher uriMatcher = TICKET_URI_PATTERN.matcher(trimmed);
        if (uriMatcher.matches()) {
            ticketId = Long.parseLong(uriMatcher.group(1));
            internal = "internal".equals(uriMatcher.group(2));
        } else {
            Matcher shortMatcher = SHORT_TICKET_ID_PATTERN.matcher(trimmed);
            if (!shortMatcher.matches()) {
                throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
            }
            ticketId = Long.parseLong(shortMatcher.group(1));
            internal = "internal".equalsIgnoreCase(shortMatcher.group(2));
        }

        if (internal && (!settings.includeInternalArticles() || settings.articleGranularity() == ArticleGranularity.PUBLIC_ONLY)) {
            throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
        }

        String canonicalId = internal
                ? ZammadDocumentBuilder.internalTicketId(settings.instanceKey(), ticketId)
                : ZammadDocumentBuilder.publicTicketId(settings.instanceKey(), ticketId);

        try {
            Optional<JsonNode> ticketOpt = client.getTicket(ticketId);
            if (ticketOpt.isEmpty()) {
                return new FetchedDocument.Gone(canonicalId);
            }
            JsonNode ticket = ticketOpt.get();
            String groupName = resolveGroupName(ticket);
            String stateName = resolveStateName(ticket);
            if (!settings.acceptsGroup(groupName) || !settings.acceptsTicketState(stateName)) {
                throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
            }

            TicketContext ctx = buildTicketContext(ticket, groupName, stateName);
            OrgInfo orgInfo = resolveOrganizationInfo(ticket);
            ZammadSecurityMapper.Mapped security = securityMapper.forTicket(
                    ctx.groupName(), ctx.customerEmail(), orgInfo.name(), orgInfo.shared(), List.of(), internal);

            String displayTitle = internal ? ctx.title() + " [Internal Notes]" : ctx.title();
            String path = "/Tickets/" + ZammadDocumentBuilder.sanitizePathSegment(ctx.groupName(), "Unassigned")
                    + "/" + ctx.ticketNumber() + "_" + (internal ? "internal" : "public") + ".html";
            DiscoveryNode node = new DiscoveryNode(
                    canonicalId,
                    canonicalId,
                    "group:" + ticket.path("group_id").asLong(0L),
                    displayTitle,
                    path,
                    DiscoveryNode.NodeType.DOCUMENT,
                    1,
                    "text/html",
                    null,
                    ZammadDocumentBuilder.parseInstant(ctx.updatedAt()).orElse(null),
                    null,
                    security.security()
            );

            if (!VisibilityFilter.decide(filter, List.of(node))[0]) {
                throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
            }

            final boolean targetInternal = internal;
            List<JsonNode> allArticles = client.getTicketArticles(ticketId);
            List<JsonNode> filteredArticles = allArticles.stream()
                    .filter(a -> a.path("internal").asBoolean(false) == targetInternal)
                    .sorted(Comparator.comparingLong(a -> a.path("id").asLong(0L)))
                    .toList();
            if (internal && filteredArticles.isEmpty()) {
                return new FetchedDocument.Gone(canonicalId);
            }
            RepositoryDocument doc = ZammadDocumentBuilder.buildTicketThread(ctx, filteredArticles, internal, security);
            return new FetchedDocument.Found(node, doc);
        } catch (IOException e) {
            throw new DiscoveryException("Zammad document fetch failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Zammad document fetch was interrupted.", e);
        }
    }

    private DiscoveryNode resolveFolderNode(String reference, VisibilityFilter filter) {
        if ("/".equals(reference)) {
            SecurityConfig rootSec = settings.includeAcls()
                    ? securityMapper.forKnowledgeBase(List.of(), new ArrayList<>(groupsById.values()), false).security()
                    : SecurityConfig.createPublic();
            DiscoveryNode root = new DiscoveryNode(
                    "zammad-root", "/", null, "Zammad", "/",
                    DiscoveryNode.NodeType.FOLDER, 0, null, null, null, null, rootSec);
            if (!VisibilityFilter.decide(filter, List.of(root))[0]) {
                throw new IllegalArgumentException("No Zammad folder '" + reference + "' in this connector's scope.");
            }
            return root;
        }
        Long groupId = null;
        String groupName = null;
        if (reference.startsWith("group:")) {
            groupId = parseLong(reference.substring("group:".length()), -1L);
            groupName = groupsById.get(groupId);
        } else if (reference.startsWith("/Groups/")) {
            String targetName = reference.substring("/Groups/".length()).replaceAll("^/+|/+$", "");
            for (Map.Entry<Long, String> entry : groupsById.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(targetName)) {
                    groupId = entry.getKey();
                    groupName = entry.getValue();
                    break;
                }
            }
        }
        if (groupId == null || groupName == null || !settings.acceptsGroup(groupName)) {
            throw new IllegalArgumentException("No Zammad folder '" + reference + "' in this connector's scope.");
        }
        ZammadSecurityMapper.Mapped sec = securityMapper.forGroup(groupName);
        DiscoveryNode folder = new DiscoveryNode(
                "zammad-group:" + groupId,
                "group:" + groupId,
                "/",
                groupName,
                "/Groups/" + groupName,
                DiscoveryNode.NodeType.FOLDER,
                0,
                null,
                null,
                null,
                null,
                sec.security()
        );
        if (!VisibilityFilter.decide(filter, List.of(folder))[0]) {
            throw new IllegalArgumentException("No Zammad folder '" + reference + "' in this connector's scope.");
        }
        return folder;
    }

    private List<DiscoveryNode> listChildrenOfFolder(DiscoveryNode folder, boolean includeDocuments)
            throws IOException, InterruptedException {
        List<DiscoveryNode> children = new ArrayList<>();
        int nextDepth = folder.depth() + 1;
        if ("/".equals(folder.sourceId())) {
            for (Map.Entry<Long, String> entry : groupsById.entrySet()) {
                long gid = entry.getKey();
                String gname = entry.getValue();
                if (!settings.acceptsGroup(gname)) {
                    continue;
                }
                ZammadSecurityMapper.Mapped sec = securityMapper.forGroup(gname);
                children.add(new DiscoveryNode(
                        "zammad-group:" + gid,
                        "group:" + gid,
                        folder.sourceId(),
                        gname,
                        "/Groups/" + gname,
                        DiscoveryNode.NodeType.FOLDER,
                        nextDepth,
                        null,
                        null,
                        null,
                        null,
                        sec.security()
                ));
            }
            return children;
        }
        if (folder.sourceId().startsWith("group:") && includeDocuments) {
            String groupName = folder.name();
            List<JsonNode> tickets = client.searchTicketsPage("group.name:\"" + groupName + "\"", 1, settings.pageSize());
            for (JsonNode ticket : tickets) {
                String tGroup = resolveGroupName(ticket);
                String tState = resolveStateName(ticket);
                if (!settings.acceptsGroup(tGroup) || !settings.acceptsTicketState(tState)) {
                    continue;
                }
                children.addAll(ticketDiscoveryNodes(ticket, tGroup, tState, folder.sourceId(), nextDepth));
            }
        }
        return children;
    }

    private List<DiscoveryNode> ticketDiscoveryNodes(
            JsonNode ticket,
            String groupName,
            String stateName,
            String parentSourceId,
            int depth
    ) throws IOException, InterruptedException {
        TicketContext ctx = buildTicketContext(ticket, groupName, stateName);
        OrgInfo orgInfo = resolveOrganizationInfo(ticket);
        ZammadSecurityMapper.Mapped publicSec = securityMapper.forTicket(
                ctx.groupName(), ctx.customerEmail(), orgInfo.name(), orgInfo.shared(), List.of(), false);
        String publicId = ZammadDocumentBuilder.publicTicketId(settings.instanceKey(), ctx.ticketId());
        String groupSeg = ZammadDocumentBuilder.sanitizePathSegment(ctx.groupName(), "Unassigned");
        Instant modified = ZammadDocumentBuilder.parseInstant(ctx.updatedAt()).orElse(null);

        List<DiscoveryNode> nodes = new ArrayList<>();
        nodes.add(new DiscoveryNode(
                publicId,
                publicId,
                parentSourceId,
                ctx.title(),
                "/Tickets/" + groupSeg + "/" + ctx.ticketNumber() + "_public.html",
                DiscoveryNode.NodeType.DOCUMENT,
                depth,
                "text/html",
                null,
                modified,
                null,
                publicSec.security()
        ));

        if (settings.includeInternalArticles()
                && settings.articleGranularity() == ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL) {
            ZammadSecurityMapper.Mapped internalSec = securityMapper.forTicket(
                    ctx.groupName(), ctx.customerEmail(), orgInfo.name(), orgInfo.shared(), List.of(), true);
            String internalId = ZammadDocumentBuilder.internalTicketId(settings.instanceKey(), ctx.ticketId());
            nodes.add(new DiscoveryNode(
                    internalId,
                    internalId,
                    parentSourceId,
                    ctx.title() + " [Internal Notes]",
                    "/Tickets/" + groupSeg + "/" + ctx.ticketNumber() + "_internal.html",
                    DiscoveryNode.NodeType.DOCUMENT,
                    depth,
                    "text/html",
                    null,
                    modified,
                    null,
                    internalSec.security()
            ));
        }
        return nodes;
    }

    // ------------------------------------------------------------------ Entity Resolution Helpers

    private TicketContext buildTicketContext(JsonNode ticketNode, String groupName, String stateName)
            throws IOException, InterruptedException {
        long ticketId = ticketNode.path("id").asLong();
        String number = ZammadDocumentBuilder.textOrDefault(ticketNode, "number", Long.toString(ticketId));
        String title = ZammadDocumentBuilder.textOrDefault(ticketNode, "title", "Ticket #" + number);
        String groupId = ticketNode.has("group_id") ? ticketNode.path("group_id").asText() : null;
        String priorityName = resolvePriorityName(ticketNode);
        String customerEmail = resolveUserEmail(ticketNode, "customer", "customer_id");
        OrgInfo orgInfo = resolveOrganizationInfo(ticketNode);
        String ownerEmail = resolveUserEmail(ticketNode, "owner", "owner_id");
        List<String> tags = settings.includeTags() ? client.getTicketTags(ticketId) : List.of();
        String createdAt = ZammadDocumentBuilder.text(ticketNode, "created_at");
        String updatedAt = ZammadDocumentBuilder.text(ticketNode, "updated_at");
        String closeAt = ZammadDocumentBuilder.text(ticketNode, "close_at");

        return new TicketContext(
                settings.instanceKey(),
                settings.baseUrl(),
                ticketId,
                number,
                title,
                groupId,
                groupName,
                stateName,
                priorityName,
                customerEmail,
                orgInfo.name(),
                ownerEmail,
                tags,
                createdAt,
                updatedAt,
                closeAt
        );
    }

    private String resolveGroupName(JsonNode ticketNode) {
        JsonNode expanded = ticketNode.path("group");
        if (expanded.isTextual() && !expanded.asText().isBlank()) {
            return expanded.asText().strip();
        }
        if (expanded.isObject()) {
            String name = ZammadDocumentBuilder.text(expanded, "name");
            if (name != null) {
                return name;
            }
        }
        long groupId = ticketNode.path("group_id").asLong(-1L);
        return groupId > 0 ? groupsById.get(groupId) : null;
    }

    private String resolveStateName(JsonNode ticketNode) {
        JsonNode expanded = ticketNode.path("state");
        if (expanded.isTextual() && !expanded.asText().isBlank()) {
            return expanded.asText().strip();
        }
        if (expanded.isObject()) {
            String name = ZammadDocumentBuilder.text(expanded, "name");
            if (name != null) {
                return name;
            }
        }
        long stateId = ticketNode.path("state_id").asLong(-1L);
        return stateId > 0 ? statesById.get(stateId) : null;
    }

    private String resolvePriorityName(JsonNode ticketNode) {
        JsonNode expanded = ticketNode.path("priority");
        if (expanded.isTextual() && !expanded.asText().isBlank()) {
            return expanded.asText().strip();
        }
        if (expanded.isObject()) {
            String name = ZammadDocumentBuilder.text(expanded, "name");
            if (name != null) {
                return name;
            }
        }
        long prioId = ticketNode.path("priority_id").asLong(-1L);
        return prioId > 0 ? prioritiesById.get(prioId) : null;
    }

    private String resolveUserEmail(JsonNode ticketNode, String expandedField, String idField)
            throws IOException, InterruptedException {
        JsonNode expanded = ticketNode.path(expandedField);
        if (expanded.isTextual() && !expanded.asText().isBlank()) {
            return expanded.asText().strip();
        }
        if (expanded.isObject()) {
            String email = firstNonBlank(
                    ZammadDocumentBuilder.text(expanded, "email"),
                    ZammadDocumentBuilder.text(expanded, "login"));
            if (email != null) {
                return email;
            }
        }
        long userId = ticketNode.path(idField).asLong(-1L);
        if (userId <= 1) {
            // In Zammad, user_id == 1 is the '-' unassigned system placeholder
            return null;
        }
        String cached = userEmailsById.get(userId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        Optional<JsonNode> userOpt = client.getUser(userId);
        String resolved = userOpt
                .map(u -> firstNonBlank(ZammadDocumentBuilder.text(u, "email"), ZammadDocumentBuilder.text(u, "login")))
                .orElse("");
        userEmailsById.put(userId, resolved);
        return resolved.isEmpty() ? null : resolved;
    }

    private OrgInfo resolveOrganizationInfo(JsonNode ticketNode) throws IOException, InterruptedException {
        JsonNode expanded = ticketNode.path("organization");
        if (expanded.isObject()) {
            String name = ZammadDocumentBuilder.text(expanded, "name");
            boolean shared = expanded.path("shared").asBoolean(false);
            if (name != null) {
                return new OrgInfo(name, shared);
            }
        }
        long orgId = ticketNode.path("organization_id").asLong(-1L);
        if (orgId <= 0) {
            if (expanded.isTextual() && !expanded.asText().isBlank()) {
                return new OrgInfo(expanded.asText().strip(), ticketNode.path("organization_shared").asBoolean(false));
            }
            return new OrgInfo(null, false);
        }
        OrgInfo cached = orgsById.get(orgId);
        if (cached != null) {
            return cached;
        }
        Optional<JsonNode> orgOpt = client.getOrganization(orgId);
        OrgInfo resolved = orgOpt
                .map(o -> new OrgInfo(ZammadDocumentBuilder.text(o, "name"), o.path("shared").asBoolean(false)))
                .orElse(new OrgInfo(null, false));
        orgsById.put(orgId, resolved);
        return resolved;
    }

    private void emitTombstones(
            Iterable<String> docIds,
            String entityId,
            String entityType,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) {
        for (String docId : docIds) {
            emit(sink, ZammadDocumentBuilder.tombstone(docId, entityId, entityType));
            crawl.tombstones.incrementAndGet();
        }
    }

    private static void emit(FluxSink<RepositoryDocument> sink, RepositoryDocument document) {
        synchronized (sink) {
            sink.next(document);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("zammad.entityType", "STRING", "Entity type: ticket, ticket_internal, ticket_article, ticket_attachment, or kb_answer"),
                new ConnectorSchema.SchemaField("zammad.ticketId", "LONG", "Zammad numeric ticket ID"),
                new ConnectorSchema.SchemaField("zammad.ticketNumber", "STRING", "Zammad human-readable ticket number"),
                new ConnectorSchema.SchemaField("zammad.title", "STRING", "Ticket or Knowledge Base answer title"),
                new ConnectorSchema.SchemaField("zammad.group", "STRING", "Assigned Zammad support group name"),
                new ConnectorSchema.SchemaField("zammad.groupId", "LONG", "Assigned Zammad support group ID"),
                new ConnectorSchema.SchemaField("zammad.state", "STRING", "Ticket state (new, open, pending reminder, closed, etc.)"),
                new ConnectorSchema.SchemaField("zammad.priority", "STRING", "Ticket priority (1 low, 2 normal, 3 high)"),
                new ConnectorSchema.SchemaField("zammad.customer", "STRING", "Customer email or login (omitted on internal notes)"),
                new ConnectorSchema.SchemaField("zammad.organization", "STRING", "Customer organization name (omitted on internal notes)"),
                new ConnectorSchema.SchemaField("zammad.owner", "STRING", "Assigned agent email or login"),
                new ConnectorSchema.SchemaField("zammad.tags", "STRING", "Comma-separated ticket tags"),
                new ConnectorSchema.SchemaField("zammad.internal", "BOOLEAN", "Whether the document contains internal agent-only notes or internal KB content"),
                new ConnectorSchema.SchemaField("zammad.articleCount", "INTEGER", "Number of articles in the ticket thread view"),
                new ConnectorSchema.SchemaField("zammad.articleId", "LONG", "Ticket article ID (for per-article and attachment documents)"),
                new ConnectorSchema.SchemaField("zammad.attachmentId", "LONG", "Ticket attachment ID"),
                new ConnectorSchema.SchemaField("zammad.fileName", "STRING", "Attachment filename"),
                new ConnectorSchema.SchemaField("zammad.fileSize", "LONG", "Attachment size in bytes"),
                new ConnectorSchema.SchemaField("zammad.kbId", "LONG", "Knowledge Base ID"),
                new ConnectorSchema.SchemaField("zammad.answerId", "LONG", "Knowledge Base answer ID"),
                new ConnectorSchema.SchemaField("zammad.categoryId", "LONG", "Knowledge Base category ID"),
                new ConnectorSchema.SchemaField("zammad.categoryTitle", "STRING", "Knowledge Base category title"),
                new ConnectorSchema.SchemaField("zammad.locale", "STRING", "Knowledge Base answer locale code"),
                new ConnectorSchema.SchemaField("zammad.visibility", "STRING", "Knowledge Base visibility: published or internal"),
                new ConnectorSchema.SchemaField("zammad.createdAt", "TIMESTAMP", "Creation ISO-8601 timestamp"),
                new ConnectorSchema.SchemaField("zammad.updatedAt", "TIMESTAMP", "Last update ISO-8601 timestamp"),
                new ConnectorSchema.SchemaField("zammad.closeAt", "TIMESTAMP", "Ticket close ISO-8601 timestamp"),
                new ConnectorSchema.SchemaField("zammad.webUrl", "STRING", "Direct Zammad Web UI deep link")
        ));
    }
}
