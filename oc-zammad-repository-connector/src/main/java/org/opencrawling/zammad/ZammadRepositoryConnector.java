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
import org.opencrawling.zammad.ZammadDocumentBuilder.ArticleView;
import org.opencrawling.zammad.ZammadDocumentBuilder.AttachmentRef;
import org.opencrawling.zammad.ZammadDocumentBuilder.Participant;
import org.opencrawling.zammad.ZammadDocumentBuilder.TicketContext;
import org.opencrawling.zammad.ZammadProperties.ArticleGranularity;
import org.opencrawling.zammad.ZammadProperties.CrawlMode;
import org.opencrawling.zammad.ZammadProperties.CustomerIdentity;
import org.opencrawling.zammad.ZammadProperties.IdentityRendering;
import org.opencrawling.zammad.ZammadProperties.ParticipantMetadata;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
            Pattern.compile("^zammad://[^/]+/tickets/(\\d+)(?:/(public|internal|articles/(\\d+)(?:/attachments/(\\d+))?))?$");
    private static final Pattern SHORT_TICKET_ID_PATTERN =
            Pattern.compile("^(?:ticket:)?(\\d+)(?::(public|internal))?$");

    private record OrgInfo(String name, boolean shared) {
    }

    /** A Zammad user as far as the connector needs it: id ({@code <= 0} when unknown), e-mail or login, display name. */
    private record UserInfo(long id, String email, String name) {
    }

    /** A ticket's context plus the identity used for its customer in permission rules. */
    private record ResolvedTicket(TicketContext ctx, String customerPrincipal) {
    }

    private final ZammadProperties settings;
    private final List<Pattern> titlePatterns;
    private final boolean ownsClient;
    private ZammadClient client;
    private ZammadSecurityMapper securityMapper;

    private final Map<Long, String> groupsById = new ConcurrentHashMap<>();
    private final Map<Long, String> statesById = new ConcurrentHashMap<>();
    private final Map<Long, String> prioritiesById = new ConcurrentHashMap<>();
    private final Map<Long, String> rolesById = new ConcurrentHashMap<>();
    private final Map<Long, UserInfo> usersById = new ConcurrentHashMap<>();
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
        this.titlePatterns = settings.compiledTitlePatterns();
        this.ownsClient = true;
        this.securityMapper = new ZammadSecurityMapper(settings);
    }

    public ZammadRepositoryConnector(ZammadProperties settings, ZammadClient client) {
        this.settings = settings;
        this.titlePatterns = settings.compiledTitlePatterns();
        this.ownsClient = (client == null);
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

        // Clear per-scan user/organization lookup caches so reused connector instances do not leak memory
        // or serve stale organization.shared ACL flags across runs.
        usersById.clear();
        orgsById.clear();

        connected = true;
        log.info("Connected to Zammad at {} as '{}' ({} groups, {} states, {} roles loaded).",
                client.getBaseUrl(), login, groupsById.size(), statesById.size(), rolesById.size());
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
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
                throw new DiscoveryException("Cannot connect to Zammad at " + settings.baseUrl() + ": " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ Crawl Scan

    private static final class CrawlSession {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicLong unchangedDocuments = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Set<String> seenTicketIds = ConcurrentHashMap.newKeySet();
        final Set<String> seenKbIds = ConcurrentHashMap.newKeySet();
        Map<String, ZammadCrawlState.Entry> previousTickets = Map.of();
        Map<String, ZammadCrawlState.Entry> previousKb = Map.of();
        final Map<String, ZammadCrawlState.Entry> nextTickets = new ConcurrentHashMap<>();
        final Map<String, ZammadCrawlState.Entry> nextKb = new ConcurrentHashMap<>();
        final Set<String> removedTickets = ConcurrentHashMap.newKeySet();
        final Set<String> removedKb = ConcurrentHashMap.newKeySet();

        boolean isStopRequested(FluxSink<?> sink) {
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
                ZammadCrawlState.State previousState = ZammadCrawlState.load(settings);
                crawl.previousTickets = previousState.tickets();
                crawl.previousKb = previousState.kbAnswers();

                Instant scanStart = Instant.now();
                Instant watermarkAnchor = client.lastServerTime().orElse(scanStart);
                String trimmedPath = basePath == null ? "" : basePath.strip();
                boolean hasOverride = !trimmedPath.isEmpty()
                        && !"default".equalsIgnoreCase(trimmedPath)
                        && !"/".equals(trimmedPath);

                // #415: an incremental job enumerates every ticket once per full-scan interval, inside the window.
                boolean scheduledFullScan = !hasOverride
                        && settings.crawlMode() == CrawlMode.SEARCH_DELTA
                        && previousState.watermark() != null
                        && settings.fullScanDue(
                                ZammadDocumentBuilder.parseInstant(previousState.lastFullScanAt()).orElse(null),
                                watermarkAnchor);
                boolean fullEnumeration = !hasOverride && (settings.crawlMode() == CrawlMode.FULL
                        || previousState.watermark() == null
                        || scheduledFullScan);
                boolean ticketsCrawled = false;

                if (!crawl.isStopRequested(sink)
                        && settings.includeTickets()
                        && !trimmedPath.startsWith("kb-category:")
                        && !trimmedPath.startsWith("/KnowledgeBase")) {
                    crawlTickets(trimmedPath, hasOverride, previousState.watermark(), fullEnumeration, crawl, sink);
                    ticketsCrawled = true;
                }

                if (!crawl.isStopRequested(sink)
                        && settings.includeKnowledgeBase()
                        && !trimmedPath.startsWith("group:")
                        && !trimmedPath.startsWith("/Groups")) {
                    crawlKnowledgeBase(trimmedPath, crawl, sink);
                }

                if (crawl.isStopRequested(sink)) {
                    log.info("Zammad scan of {} cancelled/interrupted before completion ({} documents emitted); incremental state not advanced.",
                            settings.baseUrl(), crawl.documents.get());
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                    return;
                }

                if (!hasOverride) {
                    Map<String, ZammadCrawlState.Entry> mergedTickets = new LinkedHashMap<>(crawl.previousTickets);
                    crawl.removedTickets.forEach(mergedTickets::remove);
                    mergedTickets.putAll(crawl.nextTickets);

                    Map<String, ZammadCrawlState.Entry> mergedKb = new LinkedHashMap<>(crawl.previousKb);
                    crawl.removedKb.forEach(mergedKb::remove);
                    mergedKb.putAll(crawl.nextKb);

                    String nextWatermark = crawl.failed.get() == 0
                            ? watermarkAnchor.minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).toString()
                            : previousState.watermark();
                    String nextFullScanAt = ticketsCrawled && fullEnumeration && crawl.failed.get() == 0
                            ? watermarkAnchor.truncatedTo(ChronoUnit.SECONDS).toString()
                            : previousState.lastFullScanAt();
                    ZammadCrawlState.save(settings,
                            new ZammadCrawlState.State(nextWatermark, mergedTickets, mergedKb, nextFullScanAt));
                }

                log.info("Zammad scan of {} completed ({} ticket enumeration{}): {} documents, {} unchanged tickets, "
                                + "{} unchanged documents, {} tombstones, {} skipped, {} failed.",
                        settings.baseUrl(), fullEnumeration ? "full" : "incremental",
                        scheduledFullScan ? ", periodic full scan" : "",
                        crawl.documents.get(), crawl.unchanged.get(), crawl.unchangedDocuments.get(),
                        crawl.tombstones.get(), crawl.skipped.get(), crawl.failed.get());
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (crawl.cancelled.get() || sink.isCancelled()) {
                    log.info("Zammad scan of {} stopped after downstream cancellation.", settings.baseUrl());
                    return;
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
            boolean fullEnumeration,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        String searchQuery = resolveTicketSearchQuery(overridePath, hasOverride, watermark, fullEnumeration);

        int page = 1;
        int effectivePageSize = (searchQuery != null)
                ? Math.min(settings.pageSize(), ZammadClient.MAX_TICKETS_SEARCH_PAGE_SIZE)
                : Math.min(settings.pageSize(), ZammadClient.MAX_TICKETS_INDEX_PAGE_SIZE);
        while (!crawl.isStopRequested(sink)) {
            List<JsonNode> tickets = (searchQuery != null)
                    ? client.searchTicketsPage(searchQuery, page, effectivePageSize)
                    : client.listTicketsPage(page, effectivePageSize);
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
            if (batch.isEmpty()) {
                break;
            }
            processTicketBatch(batch, crawl, sink);
            if (crawl.isStopRequested(sink)) {
                return;
            }
            page++;
        }

        if (fullEnumeration && !crawl.isStopRequested(sink) && !crawl.previousTickets.isEmpty()) {
            if (crawl.seenTicketIds.isEmpty()) {
                // An empty listing more likely means revoked permissions or a search-index problem than an empty
                // helpdesk: deleting every indexed ticket would be worse than keeping them until the next full scan.
                log.warn("Zammad full ticket enumeration of {} returned no tickets while {} are indexed; "
                        + "no tombstones emitted for vanished tickets.", settings.baseUrl(), crawl.previousTickets.size());
                return;
            }
            for (Map.Entry<String, ZammadCrawlState.Entry> prev : crawl.previousTickets.entrySet()) {
                if (crawl.isStopRequested(sink)) {
                    return;
                }
                String ticketId = prev.getKey();
                if (!crawl.seenTicketIds.contains(ticketId)) {
                    emitTombstones(prev.getValue().ids(), ticketId, "ticket", crawl, sink);
                    crawl.removedTickets.add(ticketId);
                }
            }
        }
    }

    private String resolveTicketSearchQuery(String overridePath, boolean hasOverride, String watermark,
                                            boolean fullEnumeration) {
        if (hasOverride) {
            if (overridePath.startsWith("group:")) {
                long groupId = parseLong(overridePath.substring("group:".length()), -1L);
                String groupName = groupsById.get(groupId);
                return groupName != null ? "group.name:\"" + groupName + "\"" : "group_id:" + groupId;
            }
            if (overridePath.startsWith("/Groups/")) {
                String groupName = stripSlashes(overridePath.substring("/Groups/".length()));
                return "group.name:\"" + groupName + "\"";
            }
            return overridePath;
        }
        String baseQuery = settings.baseQuery();
        if (!fullEnumeration && watermark != null && !watermark.isBlank()) {
            String delta = "updated_at:[" + watermark + " TO *]";
            return baseQuery == null ? delta : "(" + baseQuery + ") AND " + delta;
        }
        // A full enumeration lists every ticket through the index endpoint; with a base query it has to use the
        // search endpoint, and tickets that stop matching the query are tombstoned as vanished (#413).
        return baseQuery;
    }

    @SuppressWarnings("preview")
    private void processTicketBatch(List<JsonNode> tickets, CrawlSession crawl, FluxSink<RepositoryDocument> sink)
            throws InterruptedException {
        if (tickets.isEmpty() || crawl.isStopRequested(sink)) {
            return;
        }
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode ticket : tickets) {
                if (crawl.isStopRequested(sink)) {
                    break;
                }
                permits.acquire();
                if (crawl.isStopRequested(sink)) {
                    permits.release();
                    break;
                }
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        if (!crawl.isStopRequested(sink)) {
                            handleTicket(ticket, crawl, sink);
                        }
                    } catch (InterruptedException e) {
                        crawl.interrupted.set(true);
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        if (!crawl.isStopRequested(sink)) {
                            crawl.failed.incrementAndGet();
                            log.error("Failed to process Zammad ticket {}: {}", ticket.path("id").asText("?"), e.getMessage());
                        }
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
        if (crawl.isStopRequested(sink)) {
            return;
        }
        long ticketId = ticketNode.path("id").asLong(-1L);
        if (ticketId <= 0) {
            return;
        }
        String key = Long.toString(ticketId);
        ZammadCrawlState.Entry known = crawl.previousTickets.get(key);

        String groupName = resolveGroupName(ticketNode);
        String stateName = resolveStateName(ticketNode);

        String exclusion = exclusionReason(ticketNode, groupName, stateName);
        List<String> tags = null;
        if (exclusion == null && (settings.includeTags() || settings.filtersByTags())) {
            tags = client.getTicketTags(ticketId);
            if (!settings.acceptsTags(tags)) {
                exclusion = "tags";
            }
        }
        if (exclusion != null) {
            if (known != null) {
                log.debug("Zammad ticket {} left the crawl scope ({}); tombstoning its documents.", ticketId, exclusion);
                emitTombstones(known.ids(), key, "ticket", crawl, sink);
                crawl.removedTickets.add(key);
            } else {
                crawl.skipped.incrementAndGet();
            }
            return;
        }

        ResolvedTicket resolved = buildTicketContext(ticketNode, groupName, stateName, tags);
        TicketContext ctx = resolved.ctx();
        OrgInfo orgInfo = resolveOrganizationInfo(ticketNode);

        ZammadSecurityMapper.Mapped publicSecurity = securityMapper.forTicket(
                ctx.groupName(),
                resolved.customerPrincipal(),
                orgInfo.name(),
                orgInfo.shared(),
                List.of(),
                false
        );
        ZammadSecurityMapper.Mapped internalSecurity = securityMapper.forTicket(
                ctx.groupName(),
                resolved.customerPrincipal(),
                orgInfo.name(),
                orgInfo.shared(),
                List.of(),
                true
        );

        String aclFingerprint = publicSecurity.fingerprint() + "||" + internalSecurity.fingerprint();
        String renderSignature = settings.renderSignature();
        if (known != null && ctx.updatedAt() != null && ctx.updatedAt().equals(known.updatedAt())
                && aclFingerprint.equals(known.aclFingerprint())
                && renderSignature.equals(known.renderSignature())) {
            crawl.unchanged.incrementAndGet();
            crawl.nextTickets.put(key, known);
            return;
        }

        List<JsonNode> articles = new ArrayList<>(client.getTicketArticles(ticketId));
        articles.sort(Comparator.comparingLong(a -> a.path("id").asLong(0L)));
        List<ArticleView> views = articleViews(articles);
        ctx = ctx.withParticipants(participants(ctx, resolved, views));

        // Attachments are immutable in Zammad: ones already indexed under the same permissions and rendering are kept
        // without downloading them again.
        Set<String> reusable = known != null && aclFingerprint.equals(known.aclFingerprint())
                && renderSignature.equals(known.renderSignature())
                ? Set.copyOf(known.ids())
                : Set.of();
        List<String> emittedIds = new ArrayList<>();
        Map<String, String> hashes = new HashMap<>();

        if (settings.articleGranularity() == ArticleGranularity.TICKET) {
            List<ArticleView> included = views.stream()
                    .filter(v -> !v.internal() || settings.includeInternalArticles())
                    .toList();
            if (!included.isEmpty() && !crawl.isStopRequested(sink)) {
                RepositoryDocument ticketDoc = ZammadDocumentBuilder.buildTicketConversation(ctx, included, internalSecurity);
                emitIfChanged(ticketDoc, known, hashes, emittedIds, crawl, sink);
                if (settings.includeAttachments()) {
                    for (ArticleView view : included) {
                        if (crawl.isStopRequested(sink)) {
                            return;
                        }
                        emitArticleAttachments(ctx, view.article(), ticketDoc.id(), internalSecurity, reusable,
                                emittedIds, crawl, sink);
                    }
                }
            }
        } else if (settings.articleGranularity() == ArticleGranularity.PER_ARTICLE) {
            for (ArticleView view : views) {
                if (crawl.isStopRequested(sink)) {
                    return;
                }
                boolean isInternal = view.internal();
                if (isInternal && !settings.includeInternalArticles()) {
                    continue;
                }
                ZammadSecurityMapper.Mapped sec = isInternal ? internalSecurity : publicSecurity;
                RepositoryDocument articleDoc = ZammadDocumentBuilder.buildArticleDocument(ctx, view, sec);
                emitIfChanged(articleDoc, known, hashes, emittedIds, crawl, sink);

                if (settings.includeAttachments()) {
                    emitArticleAttachments(ctx, view.article(), articleDoc.id(), sec, reusable, emittedIds, crawl, sink);
                }
            }
        } else {
            List<ArticleView> publicArticles = new ArrayList<>();
            List<ArticleView> internalArticles = new ArrayList<>();
            for (ArticleView view : views) {
                if (view.internal()) {
                    internalArticles.add(view);
                } else {
                    publicArticles.add(view);
                }
            }

            if (!publicArticles.isEmpty() && !crawl.isStopRequested(sink)) {
                RepositoryDocument publicDoc = ZammadDocumentBuilder.buildTicketThread(ctx, publicArticles, false, publicSecurity);
                emitIfChanged(publicDoc, known, hashes, emittedIds, crawl, sink);

                if (settings.includeAttachments()) {
                    for (ArticleView view : publicArticles) {
                        if (crawl.isStopRequested(sink)) {
                            return;
                        }
                        emitArticleAttachments(ctx, view.article(), publicDoc.id(), publicSecurity, reusable,
                                emittedIds, crawl, sink);
                    }
                }
            }

            if (settings.includeInternalArticles()
                    && settings.articleGranularity() == ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL
                    && !internalArticles.isEmpty()
                    && !crawl.isStopRequested(sink)) {
                RepositoryDocument internalDoc = ZammadDocumentBuilder.buildTicketThread(ctx, internalArticles, true, internalSecurity);
                emitIfChanged(internalDoc, known, hashes, emittedIds, crawl, sink);

                if (settings.includeAttachments()) {
                    for (ArticleView view : internalArticles) {
                        if (crawl.isStopRequested(sink)) {
                            return;
                        }
                        emitArticleAttachments(ctx, view.article(), internalDoc.id(), internalSecurity, reusable,
                                emittedIds, crawl, sink);
                    }
                }
            }
        }

        if (crawl.isStopRequested(sink)) {
            return;
        }

        if (known != null) {
            Set<String> stale = new LinkedHashSet<>(known.ids());
            stale.removeAll(emittedIds);
            emitTombstones(stale, key, "ticket", crawl, sink);
        }

        crawl.nextTickets.put(key, new ZammadCrawlState.Entry(
                ctx.updatedAt(), aclFingerprint, List.copyOf(emittedIds), renderSignature, hashes));
    }

    /**
     * Emits a rendered (non-attachment) document unless the previous run indexed exactly the same content, metadata and
     * permissions under the same id (#412): a ticket touched only by a filtered system article is not re-indexed.
     */
    private void emitIfChanged(
            RepositoryDocument document,
            ZammadCrawlState.Entry known,
            Map<String, String> hashes,
            List<String> emittedIds,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) {
        String hash = ZammadDocumentBuilder.contentHash(document);
        emittedIds.add(document.id());
        if (hash != null) {
            hashes.put(document.id(), hash);
        }
        if (known != null && hash != null && hash.equals(known.hashes().get(document.id()))) {
            crawl.unchangedDocuments.incrementAndGet();
            return;
        }
        emit(sink, document);
        crawl.documents.incrementAndGet();
    }

    /** The articles to render, in order, without excluded senders, types and automatic replies (#412). */
    private List<ArticleView> articleViews(List<JsonNode> articles) throws IOException, InterruptedException {
        List<ArticleView> views = new ArrayList<>();
        for (JsonNode article : articles) {
            if (!includesArticle(article)) {
                continue;
            }
            String role = ZammadDocumentBuilder.articleRole(article);
            views.add(new ArticleView(article, role, settings.roleLabel(role), renderArticleAuthor(article)));
        }
        return List.copyOf(views);
    }

    private boolean includesArticle(JsonNode article) {
        if (settings.excludesSender(ZammadDocumentBuilder.text(article, "sender"))) {
            return false;
        }
        if (settings.excludesArticleType(ZammadDocumentBuilder.text(article, "type"))) {
            return false;
        }
        return !(settings.skipAutoReplies() && ZammadDocumentBuilder.isAutoReply(article));
    }

    /** The Zammad user who wrote an article: {@code origin_by_id} when an agent wrote on a customer's behalf. */
    private static long articleAuthorId(JsonNode article) {
        long origin = article.path("origin_by_id").asLong(-1L);
        return origin > 1 ? origin : article.path("created_by_id").asLong(-1L);
    }

    private String renderArticleAuthor(JsonNode article) throws IOException, InterruptedException {
        IdentityRendering rendering = settings.identityRendering();
        if (rendering == IdentityRendering.NONE) {
            return null;
        }
        if (rendering == IdentityRendering.EMAIL) {
            return ZammadDocumentBuilder.textOrDefault(article, "from",
                    ZammadDocumentBuilder.textOrDefault(article, "created_by", "unknown"));
        }
        long authorId = articleAuthorId(article);
        if (rendering == IdentityRendering.ID) {
            return authorId > 1 ? "zammad-user-" + authorId : null;
        }
        UserInfo author = authorId > 1 ? lookupUser(authorId) : null;
        if (author != null && author.name() != null) {
            return author.name();
        }
        String fromName = ZammadDocumentBuilder.displayNameFromAddress(ZammadDocumentBuilder.text(article, "from"));
        if (fromName != null) {
            return fromName;
        }
        return authorId > 1 ? "zammad-user-" + authorId : null;
    }

    /** Customer, owner and article authors, for {@code zammad.participants} (#414). */
    private List<Participant> participants(TicketContext ctx, ResolvedTicket resolved, List<ArticleView> views)
            throws IOException, InterruptedException {
        if (settings.participantMetadata() == ParticipantMetadata.NONE) {
            return List.of();
        }
        Map<String, Participant> unique = new LinkedHashMap<>();
        for (Participant p : ctx.participants()) {
            addParticipant(unique, p);
        }
        for (ArticleView view : views) {
            long authorId = articleAuthorId(view.article());
            UserInfo author = authorId > 1 ? lookupUser(authorId) : null;
            String name = author != null && author.name() != null
                    ? author.name()
                    : ZammadDocumentBuilder.displayNameFromAddress(ZammadDocumentBuilder.text(view.article(), "from"));
            addParticipant(unique, new Participant(authorId > 1 ? authorId : -1L, name));
        }
        return List.copyOf(unique.values());
    }

    private static void addParticipant(Map<String, Participant> unique, Participant participant) {
        if (participant == null || ((participant.displayName() == null || participant.displayName().isBlank())
                && participant.userId() <= 0)) {
            return;
        }
        String key = participant.userId() > 0 ? "id:" + participant.userId() : "name:" + participant.displayName();
        unique.merge(key, participant, (a, b) -> a.displayName() != null ? a : b);
    }

    /**
     * Why a ticket is outside the crawl scope (group, state, creation date, priority, organization, title), or
     * {@code null} when it is in scope. Tag filters are checked separately because they need an extra call (#413).
     */
    private String exclusionReason(JsonNode ticket, String groupName, String stateName)
            throws IOException, InterruptedException {
        if (!settings.acceptsGroup(groupName)) {
            return "group";
        }
        if (!settings.acceptsTicketState(stateName)) {
            return "state";
        }
        Optional<Instant> createdAfter = settings.createdAfterInstant(Instant.now());
        if (createdAfter.isPresent()) {
            Optional<Instant> created = ZammadDocumentBuilder.parseInstant(ZammadDocumentBuilder.text(ticket, "created_at"));
            if (created.isPresent() && created.get().isBefore(createdAfter.get())) {
                return "created-before";
            }
        }
        if (!settings.priorities().isEmpty() && !settings.acceptsPriority(resolvePriorityName(ticket))) {
            return "priority";
        }
        if (!settings.organizations().isEmpty() && !settings.acceptsOrganization(resolveOrganizationInfo(ticket).name())) {
            return "organization";
        }
        String title = ZammadDocumentBuilder.text(ticket, "title");
        if (title != null && titlePatterns.stream().anyMatch(p -> p.matcher(title).find())) {
            return "title";
        }
        return null;
    }

    /** {@link #exclusionReason} plus tag filters, for discovery and fetching. */
    private boolean inScope(JsonNode ticket, String groupName, String stateName) throws IOException, InterruptedException {
        if (exclusionReason(ticket, groupName, stateName) != null) {
            return false;
        }
        return !settings.filtersByTags() || settings.acceptsTags(client.getTicketTags(ticket.path("id").asLong()));
    }

    private void emitArticleAttachments(
            TicketContext ctx,
            JsonNode article,
            String parentDocId,
            ZammadSecurityMapper.Mapped security,
            Set<String> reusable,
            List<String> emittedIds,
            CrawlSession crawl,
            FluxSink<RepositoryDocument> sink
    ) throws IOException, InterruptedException {
        for (AttachmentRef att : ZammadDocumentBuilder.attachments(article)) {
            if (crawl.isStopRequested(sink)) {
                return;
            }
            String attachmentId = ZammadDocumentBuilder.attachmentDocumentId(
                    ctx.instanceKey(), ctx.ticketId(), att.articleId(), att.attachmentId());
            if (reusable.contains(attachmentId)) {
                emittedIds.add(attachmentId);
                crawl.unchangedDocuments.incrementAndGet();
                continue;
            }
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
                if (crawl.isStopRequested(sink)) {
                    Files.deleteIfExists(temp);
                    return;
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

        Instant now = client.lastServerTime().orElseGet(Instant::now);
        List<KbAnswerRecord> answers = parseKnowledgeBaseAnswers(init, now);
        List<String> activeGroups = new ArrayList<>(groupsById.values());

        for (KbAnswerRecord answer : answers) {
            if (crawl.isStopRequested(sink)) {
                return;
            }
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
                    answer.readerRoles(), answer.explicitPermissions(), activeGroups, answer.internal());
            String fingerprint = security.fingerprint() + "|vis:" + (answer.internal() ? "internal" : "published");

            if (known != null && answer.updatedAt() != null && answer.updatedAt().equals(known.updatedAt())
                    && fingerprint.equals(known.aclFingerprint())) {
                crawl.unchanged.incrementAndGet();
                crawl.nextKb.put(key, known);
                continue;
            }

            List<KbTranslation> translations = answer.translations();
            if (translations.isEmpty() || translations.stream().allMatch(t -> t.body() == null || t.body().isBlank())) {
                List<Long> contentIds = translations.stream()
                        .map(KbTranslation::contentId)
                        .filter(id -> id > 0)
                        .distinct()
                        .toList();
                Optional<JsonNode> fullAnswer = client.getKnowledgeBaseAnswer(answer.kbId(), answer.answerId(), contentIds);
                if (fullAnswer.isPresent()) {
                    List<KbTranslation> fetched = extractAnswerTranslations(fullAnswer.get(), answer.answerId(), answer.localesById());
                    if (!fetched.isEmpty()) {
                        translations = fetched;
                    }
                }
            }

            List<String> emittedIds = new ArrayList<>();
            for (KbTranslation tr : translations) {
                if (crawl.isStopRequested(sink)) {
                    return;
                }
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

        if (filterCategoryId == null && !crawl.isStopRequested(sink) && !crawl.previousKb.isEmpty()) {
            for (Map.Entry<String, ZammadCrawlState.Entry> prev : crawl.previousKb.entrySet()) {
                if (crawl.isStopRequested(sink)) {
                    return;
                }
                if (!crawl.seenKbIds.contains(prev.getKey())) {
                    emitTombstones(prev.getValue().ids(), prev.getKey(), "kb_answer", crawl, sink);
                    crawl.removedKb.add(prev.getKey());
                }
            }
        }
    }

    private record KbTranslation(String locale, String title, String body, long contentId) {
    }

    private record CategoryPermissions(List<String> readerRoles, boolean explicit) {
        static final CategoryPermissions NONE = new CategoryPermissions(List.of(), false);
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
            boolean explicitPermissions,
            List<KbTranslation> translations,
            Map<Long, String> localesById
    ) {
    }

    private List<KbAnswerRecord> parseKnowledgeBaseAnswers(JsonNode init, Instant now) {
        List<KbAnswerRecord> results = new ArrayList<>();
        JsonNode assets = init.has("assets") ? init.path("assets") : init;

        Map<Long, String> systemLocalesById = new LinkedHashMap<>();
        JsonNode sysLocalesNode = assets.path("Locale");
        if (sysLocalesNode.isObject()) {
            sysLocalesNode.forEach(loc -> {
                long id = loc.path("id").asLong(-1L);
                String code = ZammadDocumentBuilder.text(loc, "locale");
                if (id > 0 && code != null) {
                    systemLocalesById.put(id, code);
                }
            });
        }

        Map<Long, String> localesById = new LinkedHashMap<>();
        JsonNode localesNode = assets.path("KnowledgeBaseLocale");
        if (localesNode.isObject()) {
            localesNode.forEach(loc -> {
                long id = loc.path("id").asLong(-1L);
                long sysId = loc.path("system_locale_id").asLong(-1L);
                String fallback = loc.path("primary").asBoolean(false) || sysId <= 0 ? "en" : "locale-" + sysId;
                String code = firstNonBlank(
                        ZammadDocumentBuilder.text(loc, "system_locale"),
                        ZammadDocumentBuilder.text(loc, "locale"),
                        systemLocalesById.get(sysId),
                        fallback);
                if (id > 0) {
                    localesById.put(id, code);
                }
            });
        }

        Map<Long, CategoryPermissions> kbLevelPermissions = new LinkedHashMap<>();
        JsonNode kbsNode = assets.path("KnowledgeBase");
        if (kbsNode.isObject() || kbsNode.isArray()) {
            kbsNode.forEach(kb -> {
                long kbId = kb.path("id").asLong(-1L);
                if (kbId > 0) {
                    CategoryPermissions perms = extractPermissionsFromNode(kb, null);
                    if (perms.explicit()) {
                        kbLevelPermissions.put(kbId, perms);
                    }
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
        Map<Long, CategoryPermissions> categoryPermissions = new LinkedHashMap<>();
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
                    CategoryPermissions perms = extractPermissionsFromNode(cat, assets.path("KnowledgeBasePermission"));
                    if (!perms.explicit()) {
                        perms = kbLevelPermissions.getOrDefault(kbId, CategoryPermissions.NONE);
                    }
                    categoryPermissions.put(catId, perms);
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
                        .add(new KbTranslation(locale, title, body, contentId));
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

                // Match Zammad CanBePublished::StateMachine: future timestamps are scheduled transitions, not active yet.
                Optional<Instant> archivedAtTs = ZammadDocumentBuilder.parseInstant(archivedAt);
                Optional<Instant> publishedAtTs = ZammadDocumentBuilder.parseInstant(publishedAt);
                Optional<Instant> internalAtTs = ZammadDocumentBuilder.parseInstant(internalAt);

                boolean isArchived = isTimestampReached(archivedAtTs, now);
                boolean isPublished = !isArchived && (isTimestampReached(publishedAtTs, now)
                        || (publishedAt == null && ans.path("published").asBoolean(false)));
                boolean isInternal = !isArchived && !isPublished && (isTimestampReached(internalAtTs, now)
                        || (internalAt == null && ans.path("internal").asBoolean(false)));
                boolean active = isPublished || isInternal;
                boolean internal = isInternal;

                String updatedAt = firstNonBlank(
                        ZammadDocumentBuilder.text(ans, "updated_at"),
                        internalAt,
                        publishedAt);
                CategoryPermissions perms = categoryPermissions.getOrDefault(
                        categoryId, kbLevelPermissions.getOrDefault(kbId, CategoryPermissions.NONE));
                List<KbTranslation> translations = new ArrayList<>(
                        translationsByAnswer.getOrDefault(answerId, List.of()));
                if (translations.isEmpty()) {
                    translations.addAll(extractAnswerTranslations(ans, answerId, localesById));
                }
                results.add(new KbAnswerRecord(
                        kbId, answerId, categoryId, categoryTitle, active, internal,
                        updatedAt, perms.readerRoles(), perms.explicit(), translations, localesById));
            });
        }
        return results;
    }

    private static boolean isTimestampReached(Optional<Instant> timestamp, Instant now) {
        return timestamp.isPresent() && !timestamp.get().isAfter(now);
    }

    private CategoryPermissions extractPermissionsFromNode(JsonNode ownerNode, JsonNode permissionsAssets) {
        List<String> roles = new ArrayList<>();
        // Zammad computes inherited effective permissions into permissions_effective; when non-empty it is authoritative.
        JsonNode effectivePerms = ownerNode.path("permissions_effective");
        if (effectivePerms.isArray() && !effectivePerms.isEmpty()) {
            for (JsonNode perm : effectivePerms) {
                addRoleFromPermission(perm, roles);
            }
            return new CategoryPermissions(List.copyOf(roles), true);
        }

        boolean explicit = false;
        JsonNode inlinePerms = ownerNode.path("permissions");
        if (inlinePerms.isArray() && !inlinePerms.isEmpty()) {
            explicit = true;
            for (JsonNode perm : inlinePerms) {
                addRoleFromPermission(perm, roles);
            }
        }
        long ownerId = ownerNode.path("id").asLong(-1L);
        if (ownerId > 0 && permissionsAssets != null && permissionsAssets.isObject()) {
            for (JsonNode perm : permissionsAssets) {
                if (perm.path("permissionable_id").asLong(-1L) == ownerId) {
                    explicit = true;
                    addRoleFromPermission(perm, roles);
                }
            }
        }
        return new CategoryPermissions(List.copyOf(roles), explicit);
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

    private List<KbTranslation> extractAnswerTranslations(JsonNode answerPayload, long targetAnswerId, Map<Long, String> localesById) {
        JsonNode root = answerPayload.has("assets") ? answerPayload : answerPayload;
        List<KbTranslation> list = new ArrayList<>();
        JsonNode translations = root.path("translations");
        if (translations.isArray()) {
            for (JsonNode tr : translations) {
                long locId = tr.path("kb_locale_id").asLong(-1L);
                String locale = ZammadDocumentBuilder.textOrDefault(tr, "locale",
                        localesById.getOrDefault(locId, "en"));
                String title = ZammadDocumentBuilder.textOrDefault(tr, "title", "Knowledge Base Answer");
                long contentId = tr.path("content_id").asLong(-1L);
                String body = firstNonBlank(
                        ZammadDocumentBuilder.text(tr, "body"),
                        ZammadDocumentBuilder.text(tr.path("content"), "body"));
                if (body != null) {
                    list.add(new KbTranslation(locale, title, body, contentId));
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
                long ansId = tr.path("answer_id").asLong(-1L);
                if (targetAnswerId > 0 && ansId > 0 && ansId != targetAnswerId) {
                    return;
                }
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
                    list.add(new KbTranslation(locale, title, body, contentId));
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
            int effectivePageSize = (query != null)
                    ? Math.min(settings.pageSize(), ZammadClient.MAX_TICKETS_SEARCH_PAGE_SIZE)
                    : Math.min(settings.pageSize(), ZammadClient.MAX_TICKETS_INDEX_PAGE_SIZE);
            Set<Long> seenIds = new HashSet<>();
            while (true) {
                if (System.nanoTime() - deadline > 0) {
                    return new DryRunResult(matched, CountAccuracy.LOWER_BOUND, samples, hidden, TruncationReason.TIMEOUT);
                }
                List<JsonNode> tickets = (query != null)
                        ? client.searchTicketsPage(query, page, effectivePageSize)
                        : client.listTicketsPage(page, effectivePageSize);
                if (tickets.isEmpty()) {
                    break;
                }
                int newTickets = 0;
                List<DiscoveryNode> pageNodes = new ArrayList<>();
                for (JsonNode ticket : tickets) {
                    long tid = ticket.path("id").asLong(-1L);
                    if (tid > 0 && !seenIds.add(tid)) {
                        continue;
                    }
                    newTickets++;
                    String groupName = resolveGroupName(ticket);
                    String stateName = resolveStateName(ticket);
                    if (!inScope(ticket, groupName, stateName)) {
                        continue;
                    }
                    pageNodes.addAll(ticketDiscoveryNodes(ticket, groupName, stateName, "group:" + ticket.path("group_id").asLong(0L), 1));
                }
                if (newTickets == 0) {
                    break;
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
        String part;

        Matcher uriMatcher = TICKET_URI_PATTERN.matcher(trimmed);
        if (uriMatcher.matches()) {
            ticketId = Long.parseLong(uriMatcher.group(1));
            part = uriMatcher.group(2);
        } else {
            Matcher shortMatcher = SHORT_TICKET_ID_PATTERN.matcher(trimmed);
            if (!shortMatcher.matches()) {
                throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
            }
            ticketId = Long.parseLong(shortMatcher.group(1));
            part = shortMatcher.group(2) != null ? shortMatcher.group(2).toLowerCase(Locale.ROOT) : null;
        }

        boolean ticketLevel = settings.articleGranularity() == ArticleGranularity.TICKET;
        if (ticketLevel && part != null || !ticketLevel && part == null && uriMatcher.matches()) {
            // ids of another granularity than the configured one are not produced by this connector
            throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
        }
        boolean internal = "internal".equals(part);
        if (internal && (!settings.includeInternalArticles() || settings.articleGranularity() == ArticleGranularity.PUBLIC_ONLY)) {
            throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
        }

        String canonicalId = ticketLevel
                ? ZammadDocumentBuilder.ticketDocumentId(settings.instanceKey(), ticketId)
                : internal
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
            if (!inScope(ticket, groupName, stateName)) {
                throw new IllegalArgumentException("No Zammad document '" + documentId + "' in this connector's scope.");
            }

            ResolvedTicket resolved = buildTicketContext(ticket, groupName, stateName, null);
            TicketContext ctx = resolved.ctx();
            OrgInfo orgInfo = resolveOrganizationInfo(ticket);
            // The ticket-level conversation includes internal notes: group-only, like internal notes.
            ZammadSecurityMapper.Mapped security = securityMapper.forTicket(
                    ctx.groupName(), resolved.customerPrincipal(), orgInfo.name(), orgInfo.shared(), List.of(),
                    internal || ticketLevel);

            String displayTitle = internal ? ctx.title() + " [Internal Notes]" : ctx.title();
            String suffix = ticketLevel ? "conversation" : internal ? "internal" : "public";
            String path = "/Tickets/" + ZammadDocumentBuilder.sanitizePathSegment(ctx.groupName(), "Unassigned")
                    + "/" + ctx.ticketNumber() + "_" + suffix + ".html";
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

            List<JsonNode> allArticles = new ArrayList<>(client.getTicketArticles(ticketId));
            allArticles.sort(Comparator.comparingLong(a -> a.path("id").asLong(0L)));
            List<ArticleView> views = articleViews(allArticles);
            ctx = ctx.withParticipants(participants(ctx, resolved, views));
            final boolean targetInternal = internal;
            List<ArticleView> selected = views.stream()
                    .filter(v -> ticketLevel ? (!v.internal() || settings.includeInternalArticles()) : v.internal() == targetInternal)
                    .toList();
            if (selected.isEmpty()) {
                return new FetchedDocument.Gone(canonicalId);
            }
            RepositoryDocument doc = ticketLevel
                    ? ZammadDocumentBuilder.buildTicketConversation(ctx, selected, security)
                    : ZammadDocumentBuilder.buildTicketThread(ctx, selected, internal, security);
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
            String targetName = stripSlashes(reference.substring("/Groups/".length()));
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
                if (!inScope(ticket, tGroup, tState)) {
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
        ResolvedTicket resolved = buildTicketContext(ticket, groupName, stateName, null);
        TicketContext ctx = resolved.ctx();
        OrgInfo orgInfo = resolveOrganizationInfo(ticket);
        String groupSeg = ZammadDocumentBuilder.sanitizePathSegment(ctx.groupName(), "Unassigned");
        Instant modified = ZammadDocumentBuilder.parseInstant(ctx.updatedAt()).orElse(null);

        List<DiscoveryNode> nodes = new ArrayList<>();
        if (settings.articleGranularity() == ArticleGranularity.TICKET) {
            ZammadSecurityMapper.Mapped groupOnly = securityMapper.forTicket(
                    ctx.groupName(), resolved.customerPrincipal(), orgInfo.name(), orgInfo.shared(), List.of(), true);
            String ticketDocId = ZammadDocumentBuilder.ticketDocumentId(settings.instanceKey(), ctx.ticketId());
            nodes.add(new DiscoveryNode(
                    ticketDocId,
                    ticketDocId,
                    parentSourceId,
                    ctx.title(),
                    "/Tickets/" + groupSeg + "/" + ctx.ticketNumber() + "_conversation.html",
                    DiscoveryNode.NodeType.DOCUMENT,
                    depth,
                    "text/html",
                    null,
                    modified,
                    null,
                    groupOnly.security()
            ));
            return nodes;
        }
        ZammadSecurityMapper.Mapped publicSec = securityMapper.forTicket(
                ctx.groupName(), resolved.customerPrincipal(), orgInfo.name(), orgInfo.shared(), List.of(), false);
        String publicId = ZammadDocumentBuilder.publicTicketId(settings.instanceKey(), ctx.ticketId());
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
                    ctx.groupName(), resolved.customerPrincipal(), orgInfo.name(), orgInfo.shared(), List.of(), true);
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

    /**
     * Resolves the ticket's lookups and renders customer and owner per identity settings (#414). {@code tags} are the
     * tags already read for the tag filters, or {@code null} to read them here when they are indexed.
     */
    private ResolvedTicket buildTicketContext(JsonNode ticketNode, String groupName, String stateName, List<String> tags)
            throws IOException, InterruptedException {
        long ticketId = ticketNode.path("id").asLong();
        String number = ZammadDocumentBuilder.textOrDefault(ticketNode, "number", Long.toString(ticketId));
        String title = ZammadDocumentBuilder.textOrDefault(ticketNode, "title", "Ticket #" + number);
        String groupId = ticketNode.has("group_id") ? ticketNode.path("group_id").asText() : null;
        String priorityName = resolvePriorityName(ticketNode);
        UserInfo customer = resolveUser(ticketNode, "customer", "customer_id");
        OrgInfo orgInfo = resolveOrganizationInfo(ticketNode);
        UserInfo owner = resolveUser(ticketNode, "owner", "owner_id");
        List<String> ticketTags = !settings.includeTags() ? List.of()
                : tags != null ? tags : client.getTicketTags(ticketId);
        String createdAt = ZammadDocumentBuilder.text(ticketNode, "created_at");
        String updatedAt = ZammadDocumentBuilder.text(ticketNode, "updated_at");
        String closeAt = ZammadDocumentBuilder.text(ticketNode, "close_at");

        List<Participant> participants = new ArrayList<>();
        for (UserInfo user : new UserInfo[] {customer, owner}) {
            if (user != null && (user.name() != null || user.id() > 1)) {
                participants.add(new Participant(user.id() > 1 ? user.id() : -1L, user.name()));
            }
        }

        TicketContext ctx = new TicketContext(
                settings.instanceKey(),
                settings.baseUrl(),
                ticketId,
                number,
                title,
                groupId,
                groupName,
                stateName,
                priorityName,
                renderIdentity(customer),
                orgInfo.name(),
                renderIdentity(owner),
                ticketTags,
                createdAt,
                updatedAt,
                closeAt,
                participants,
                settings.participantMetadata()
        );
        return new ResolvedTicket(ctx, customerPrincipal(customer));
    }

    /** How a person appears in headers and metadata (#414); never falls back to an e-mail unless asked for e-mails. */
    private String renderIdentity(UserInfo user) {
        if (user == null) {
            return null;
        }
        String id = user.id() > 1 ? "zammad-user-" + user.id() : null;
        return switch (settings.identityRendering()) {
            case EMAIL -> user.email() != null ? user.email() : user.name();
            case NAME -> user.name() != null ? user.name() : id;
            case ID -> id;
            case NONE -> null;
        };
    }

    /** The customer's identity in permission rules: e-mail or login, or {@code zammad-user-<id>} (#414). */
    private String customerPrincipal(UserInfo customer) {
        if (customer == null) {
            return null;
        }
        if (settings.customerIdentity() == CustomerIdentity.ID) {
            return customer.id() > 1 ? "zammad-user-" + customer.id() : null;
        }
        return customer.email();
    }

    /** Whether display names are needed, so that users are looked up even when the ticket carries their e-mail. */
    private boolean needsNames() {
        return settings.identityRendering() == IdentityRendering.NAME
                || settings.participantMetadata() != ParticipantMetadata.NONE;
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

    private UserInfo resolveUser(JsonNode ticketNode, String expandedField, String idField)
            throws IOException, InterruptedException {
        long userId = ticketNode.path(idField).asLong(-1L);
        JsonNode expanded = ticketNode.path(expandedField);
        String expandedEmail = null;
        String expandedName = null;
        if (expanded.isTextual() && !expanded.asText().isBlank()) {
            expandedEmail = expanded.asText().strip();
        } else if (expanded.isObject()) {
            expandedEmail = firstNonBlank(
                    ZammadDocumentBuilder.text(expanded, "email"),
                    ZammadDocumentBuilder.text(expanded, "login"));
            expandedName = fullName(expanded);
        }
        boolean complete = expandedEmail != null && (expandedName != null || !needsNames());
        if (complete || userId <= 1) {
            // In Zammad, user_id == 1 is the '-' unassigned system placeholder
            return expandedEmail == null && expandedName == null
                    ? null
                    : new UserInfo(userId > 1 ? userId : -1L, expandedEmail, expandedName);
        }
        UserInfo looked = lookupUser(userId);
        String email = expandedEmail != null ? expandedEmail : looked.email();
        String name = expandedName != null ? expandedName : looked.name();
        return email == null && name == null ? null : new UserInfo(userId, email, name);
    }

    /** {@code GET /api/v1/users/{id}}, cached for the run; a missing user resolves to an empty entry. */
    private UserInfo lookupUser(long userId) throws IOException, InterruptedException {
        UserInfo cached = usersById.get(userId);
        if (cached != null) {
            return cached;
        }
        Optional<JsonNode> userOpt = client.getUser(userId);
        UserInfo resolved = userOpt
                .map(u -> new UserInfo(userId,
                        firstNonBlank(ZammadDocumentBuilder.text(u, "email"), ZammadDocumentBuilder.text(u, "login")),
                        fullName(u)))
                .orElse(new UserInfo(userId, null, null));
        usersById.put(userId, resolved);
        return resolved;
    }

    private static String fullName(JsonNode user) {
        String first = ZammadDocumentBuilder.text(user, "firstname");
        String last = ZammadDocumentBuilder.text(user, "lastname");
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return joined.isEmpty() ? null : joined;
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

    private static String stripSlashes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
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
                new ConnectorSchema.SchemaField("zammad.entityType", "STRING", "Entity type: ticket, ticket_internal, ticket_conversation, ticket_article, ticket_attachment, or kb_answer"),
                new ConnectorSchema.SchemaField("zammad.ticketId", "LONG", "Zammad numeric ticket ID"),
                new ConnectorSchema.SchemaField("zammad.ticketNumber", "STRING", "Zammad human-readable ticket number"),
                new ConnectorSchema.SchemaField("zammad.title", "STRING", "Ticket or Knowledge Base answer title"),
                new ConnectorSchema.SchemaField("zammad.group", "STRING", "Assigned Zammad support group name"),
                new ConnectorSchema.SchemaField("zammad.groupId", "LONG", "Assigned Zammad support group ID"),
                new ConnectorSchema.SchemaField("zammad.state", "STRING", "Ticket state (new, open, pending reminder, closed, etc.)"),
                new ConnectorSchema.SchemaField("zammad.priority", "STRING", "Ticket priority (1 low, 2 normal, 3 high)"),
                new ConnectorSchema.SchemaField("zammad.customer", "STRING", "Customer, rendered per identity-rendering (omitted on internal notes)"),
                new ConnectorSchema.SchemaField("zammad.organization", "STRING", "Customer organization name (omitted on internal notes)"),
                new ConnectorSchema.SchemaField("zammad.owner", "STRING", "Assigned agent, rendered per identity-rendering"),
                new ConnectorSchema.SchemaField("zammad.participants", "STRING", "Display names of the customer, the owner and the article authors (multi-valued)"),
                new ConnectorSchema.SchemaField("zammad.participantIds", "STRING", "Zammad user ids of the participants, zammad-user-<id> (multi-valued)"),
                new ConnectorSchema.SchemaField("zammad.hasInternalNotes", "BOOLEAN", "Whether a ticket conversation document includes internal notes"),
                new ConnectorSchema.SchemaField("zammad.articleRole", "STRING", "Article role: customer, agent, internal or system"),
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
