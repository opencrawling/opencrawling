/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.discovery.DiscoverableRepositoryConnector;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DocumentFetchingRepositoryConnector;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.opencrawling.runtime.discovery.SourceAclVisibility;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Starts, reports on and stops jobs that a caller describes itself, typically an AI agent.
 *
 * <p>The rules, in the order they are checked:
 * <ul>
 *   <li>The caller is verified by token, names a user, and carries the required scope.</li>
 *   <li>The connector is one an administrator registered ({@code connectors.json}) and listed for dynamic jobs. The
 *       caller never supplies endpoints, hosts, credentials or connector settings.</li>
 *   <li>The start folder lies inside the connector's configured scope and the caller may read it by its source ACL
 *       ({@link SourceAclVisibility}); otherwise it is reported as not found, as discovery does. A connector that
 *       crawls a query or a change log cannot run dynamic jobs.</li>
 *   <li>The crawl publishes every document to the documents topic only, never straight to an output connector, so
 *       each document keeps its source permissions through ingestion and the permission sync. Dynamic jobs always
 *       run in RAG mode.</li>
 * </ul>
 *
 * <p>A job's status is visible to the user who started it (whichever agent acted for them) and to members of the
 * admin groups; to anyone else it does not exist. A job is one run in the run registry's store from the moment it is
 * asked for (#316, {@link RunDynamicJobStore}): in this runtime's memory with the {@code file} control-plane store
 * (lost on restart), in the control-plane database with {@code jdbc}, where every runtime on it sees the same jobs,
 * approves them and stops them, after a restart too. It is kept for the retention period after it ends. Like every
 * run it can be paused and resumed, and one whose runtime died is marked {@code INTERRUPTED}.
 */
public class DynamicJobService {

    private static final Logger log = LoggerFactory.getLogger(DynamicJobService.class);

    static final String STATUS_PATH = "/api/jobs/dynamic/";
    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_START_LENGTH = 1024;
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

    /**
     * Runs a dynamic job's crawl from {@code start} (a folder's source id), publishing to the documents topic only.
     *
     * @return whether the crawl succeeded
     */
    @FunctionalInterface
    public interface CrawlRunner {
        boolean run(RepositoryConnector connector, String start, String jobId, String jobName, String executionId,
                    BooleanSupplier stopRequested, ExecutionProvenance provenance, ChunkingSpec chunking);
    }

    /**
     * A job's state. With approvals on, an agent's job starts as {@code PENDING_APPROVAL}, and ends as
     * {@code REJECTED} or {@code EXPIRED} if it is never approved; a pending job its owner cancels ends
     * {@code STOPPED}. A running job may be {@code PAUSED}; one whose runtime stopped while it ran ends
     * {@code INTERRUPTED}.
     */
    public enum State {
        PENDING_APPROVAL, RUNNING, COMPLETED, FAILED, STOPPED, REJECTED, EXPIRED, PAUSED, INTERRUPTED;

        /** Running or paused: it holds a run. */
        public boolean active() {
            return this == RUNNING || this == PAUSED;
        }
    }

    /** What a job crawls: a folder tree, or one document. */
    public enum Kind { FOLDER, DOCUMENT }

    static final String DELETION_NOTE = "If the repository no longer has this document, it is removed from the index.";

    /**
     * A request about one document of a registered connector.
     *
     * @param document the document's id in the repository (as discovery returns it), never a path
     * @param action   {@code UPSERT} (default) to ingest it again, {@code DELETE} to remove it from the index once
     *                 the repository no longer has it
     * @param chunking for an UPSERT, how to split the document; {@code null} for the default chunking
     */
    public record DocumentRequest(String connector, String document, Action action, ChunkingSpec chunking) {
        public enum Action { UPSERT, DELETE }

        public DocumentRequest(String connector, String document, Action action) {
            this(connector, document, action, null);
        }
    }

    /** The answer to a DELETE: the same whether or not anything was removed. */
    public record DeletionAccepted(String connector, String document, String note) {}

    /**
     * What a caller asks for: a registered connector, optionally a start folder (id or path), a label and how to
     * split the documents ({@code null}: the default chunking).
     */
    public record JobRequest(String connector, String start, String name, ChunkingSpec chunking) {

        public JobRequest(String connector, String start, String name) {
            this(connector, start, name, null);
        }
    }

    /** The folder a job crawls from. */
    public record StartView(String id, String name, String path) {

        static StartView of(DiscoveryNode node) {
            return new StartView(node.sourceId(), node.name(), node.path());
        }
    }

    /** The answer to a request that only validates. */
    public record Validated(boolean valid, String connector, StartView start) {}

    /**
     * The answer to a request that starts a job.
     *
     * @param replayed {@code true} when an earlier request with the same idempotency key had already started it
     */
    public record Accepted(String jobId, String executionId, String statusUrl, State state, boolean replayed) {}

    /**
     * A job's status. {@code documents} is known once the job has ended; {@code chunking} is the chunking the job
     * asked for, {@code null} for the default.
     */
    public record JobStatus(String jobId, String executionId, String name, String connector, Kind kind, StartView start,
                            State state, boolean stopRequested, Instant createdAt, Instant finishedAt, Long documents,
                            String owner, String triggerSource, String approvedBy, String note, ChunkingSpec chunking) {}

    private final Supplier<List<ConnectorDTO>> connectors;
    private final Function<ConnectorDTO, RepositoryConnector> builder;
    private final JobRunRegistry registry;
    private final CrawlRunner runner;
    private final Function<String, Long> documentCount;
    private final DynamicJobProperties properties;
    private final Clock clock;

    private final DynamicJobStore store;

    /**
     * @param connectors    the registered connectors
     * @param builder       builds a connector from its registration (the job API's {@code ConnectorFactory})
     * @param registry      the runtime's job runs, whose caps apply to dynamic jobs as well
     * @param runner        runs a crawl
     * @param documentCount the number of documents an execution handled, or {@code null} when unknown
     */
    public DynamicJobService(Supplier<List<ConnectorDTO>> connectors, Function<ConnectorDTO, RepositoryConnector> builder,
                             JobRunRegistry registry, CrawlRunner runner, Function<String, Long> documentCount,
                             DynamicJobProperties properties, Clock clock) {
        this(connectors, builder, registry, runner, documentCount, properties, clock,
                new RunDynamicJobStore(registry.store(), RunDynamicJobStore.Keys.inMemory(), registry::runtimeId, clock));
    }

    /** @param store where the jobs are kept: as runs in the run registry's store ({@link RunDynamicJobStore}) */
    DynamicJobService(Supplier<List<ConnectorDTO>> connectors, Function<ConnectorDTO, RepositoryConnector> builder,
                      JobRunRegistry registry, CrawlRunner runner, Function<String, Long> documentCount,
                      DynamicJobProperties properties, Clock clock, DynamicJobStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.documentCount = Objects.requireNonNull(documentCount, "documentCount");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        registry.onInterrupted(this::interrupted);
    }

    /** Checks a request as {@link #start} would, without starting anything. */
    public Validated validate(Caller caller, JobRequest request) {
        requireCaller(caller, properties.getRequiredScope());
        JobRequest checked = checked(request);
        checkChunking(checked.chunking());
        DiscoveryNode start = resolveStart(caller, registration(checked.connector(), true), checked.start());
        return new Validated(true, checked.connector(), StartView.of(start));
    }

    /**
     * Starts a job.
     *
     * @param idempotencyKey a caller-chosen key: a repeated request with the same key returns the job the first one
     *                       started, for as long as that job's status is kept; {@code null} for none
     * @param triggerSource  how the request came in, for the ledger
     * @param correlationId  the caller's own id for the request, for the ledger; {@code null} for none
     */
    public Accepted start(Caller caller, JobRequest request, String idempotencyKey, String triggerSource,
                          String correlationId) {
        requireCaller(caller, properties.getRequiredScope());
        JobRequest checked = checked(request);
        checkChunking(checked.chunking());
        String key = checkedKey(idempotencyKey);
        Accepted replay = replay(owner(caller), key, checked);
        if (replay != null) {
            return replay;
        }
        ConnectorDTO registration = registration(checked.connector(), true);
        DiscoveryNode start = resolveStart(caller, registration, checked.start());
        String name = checked.name() != null ? checked.name() : "Dynamic job on " + registration.name();
        Requester requester = requester(caller, triggerSource, correlationId);
        if (properties.isRequireApproval()) {
            return fileForApproval(requester, key, checked, registration, name, Kind.FOLDER, start,
                    new Pending(caller, requester, registration.name(), checked));
        }
        return launch(requester, key, checked, registration, name, Kind.FOLDER, start, () -> builder.apply(registration),
                start.sourceId(), properties.getMaxRunningPerUser(), null);
    }

    /**
     * Ingests one document again (UPSERT), or removes it from the index once the repository no longer has it
     * (DELETE).
     *
     * <p>An UPSERT fetches the document as the crawl would emit it, if it is in the connector's scope and the
     * caller may read it, and runs it as a one-document job: the answer is like {@link #start}'s. A document that
     * is hidden, out of scope or missing is refused alike.
     *
     * <p>A DELETE publishes a tombstone only when the repository reports no such document, so a caller cannot remove
     * a live document from the index. A document that still exists and that the caller may read is refused
     * ({@code 409}). Every other DELETE gets the same answer whether or not a tombstone was published, so a caller
     * learns nothing about documents it cannot read.
     *
     * @return {@link Accepted} for an UPSERT, {@link DeletionAccepted} for a DELETE
     */
    public Object document(Caller caller, DocumentRequest request, String idempotencyKey, String triggerSource,
                           String correlationId) {
        requireCaller(caller, properties.getDocumentScope());
        DocumentRequest checked = checked(request);
        checkChunking(checked.chunking());
        String key = checkedKey(idempotencyKey);
        if (checked.action() == DocumentRequest.Action.UPSERT) {
            Accepted replay = replay(owner(caller), key, checked);
            if (replay != null) {
                return replay;
            }
        }
        Requester requester = requester(caller, triggerSource, correlationId);
        ConnectorDTO registration = registration(checked.connector(), true);
        Pending gate = properties.isRequireApproval() && checked.action() == DocumentRequest.Action.UPSERT
                ? new Pending(caller, requester, registration.name(), checked) : null;
        return document(requester, registration, checked, key, SourceAclVisibility.forCaller(caller),
                properties.getMaxRunningPerUser(), gate, null);
    }

    /**
     * Ingests or removes one document for a requester whose right to ask was already checked: a verified caller
     * (with its source-ACL filter) or a configured BPM event route (with {@link VisibilityFilter#ALL}, since the
     * route, not the requester, decides what may be ingested). The connector still holds the document to its scope.
     *
     * @param request      a checked request
     * @param runningLimit how many of the requester's jobs may run at once (0: no limit of its own)
     * @param gate         for an UPSERT that needs approval: what to file instead of starting it; {@code null} to start
     * @param approved     a job being approved, to start under its id; {@code null} for a new one
     */
    Object document(Requester requester, ConnectorDTO registration, DocumentRequest request, String key,
                    VisibilityFilter filter, int runningLimit, Pending gate, Entry approved) {
        RepositoryConnector built = build(registration);
        FetchedDocument fetched;
        boolean handedOver = false;
        try {
            if (!(built instanceof DocumentFetchingRepositoryConnector connector)) {
                throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Repository connector '" + registration.name()
                        + "' cannot fetch single documents.");
            }
            try {
                fetched = repositoryCall(registration, () -> connector.fetchDocument(request.document(), filter));
            } catch (DynamicJobException e) {
                if (e.status() != HttpStatus.BAD_REQUEST) {
                    throw e;
                }
                // Hidden, out of scope or not a document: the same answers as for a missing one.
                if (request.action() == DocumentRequest.Action.DELETE) {
                    return deletionAccepted(registration, request);
                }
                throw documentNotAvailable(request, registration);
            }
            if (request.action() == DocumentRequest.Action.DELETE) {
                if (fetched instanceof FetchedDocument.Found) {
                    throw new DynamicJobException(HttpStatus.CONFLICT, "Document '" + request.document()
                            + "' still exists in the repository, so it is not removed from the index.");
                }
                String indexedId = ((FetchedDocument.Gone) fetched).indexedId();
                publishTombstone(requester, registration, request, indexedId);
                return deletionAccepted(registration, request);
            }
            if (!(fetched instanceof FetchedDocument.Found found)) {
                throw documentNotAvailable(request, registration);
            }
            String name = approved != null ? approved.name() : "Document " + found.node().name() + " on " + registration.name();
            if (gate != null) {
                // Fetched only to check it; it is fetched again once the job is approved.
                closeQuietly(found.document().contentStream());
                return fileForApproval(requester, key, request, registration, name, Kind.DOCUMENT, found.node(), gate);
            }
            RepositoryConnector single = new SingleDocumentConnector(found.document(), built);
            Accepted accepted = launch(requester, key, request, registration, name, Kind.DOCUMENT, found.node(),
                    () -> single, found.node().sourceId(), runningLimit, approved);
            handedOver = true;
            return accepted;
        } finally {
            if (!handedOver) {
                disconnect(built);
            }
        }
    }

    /**
     * Who a job runs for.
     *
     * @param owner      the key its status is filed under: only requests with the same owner (and admins) see it
     * @param principal  the name shown as the job's owner and in the logs
     * @param provenance what the ledger records about who started it
     */
    record Requester(String owner, String principal, ExecutionProvenance provenance) {}

    private Requester requester(Caller caller, String triggerSource, String correlationId) {
        return new Requester(owner(caller), caller.principal(), ExecutionProvenance.verified(caller.principal(),
                caller.attributes().get(JwtCallerMapper.ACTOR) instanceof String actor ? actor : null,
                caller.groups(), caller.tenant(), triggerSource, correlationId));
    }

    /**
     * Runs a crawl as a job the caller can follow; {@code crawler} is disconnected when the run ends.
     *
     * @param approved a job being approved, started under its own id; {@code null} for a new job
     */
    private Accepted launch(Requester requester, String key, Object fingerprint, ConnectorDTO registration, String name,
                            Kind kind, DiscoveryNode target, Supplier<RepositoryConnector> crawler, String scanFrom,
                            int runningLimit, Entry approved) {
        String owner = requester.owner();
        ExecutionProvenance provenance = requester.provenance();
        String triggerSource = provenance.triggerSource();
        if (approved != null) {
            return launchApproved(approved, requester, registration, crawler, scanFrom, runningLimit);
        }
        String jobId = newJobId();
        String executionId = newExecutionId();
        Entry entry = Entry.of(jobId, executionId, name, registration.name(), kind, StartView.of(target), owner,
                requester.principal(), triggerSource, clock.instant(), chunkingOf(fingerprint), State.RUNNING, null);

        Accepted earlier = store.locked(() -> {
            evict();
            Accepted replay = replayLocked(owner, key, fingerprint);
            if (replay != null) {
                return replay;
            }
            if (runningLimit > 0 && store.count(owner, State.RUNNING, State.PAUSED) >= runningLimit) {
                throw new DynamicJobException(HttpStatus.TOO_MANY_REQUESTS,
                        "You already have " + runningLimit + " dynamic jobs running.");
            }
            store.save(entry);
            if (key != null) {
                store.saveKey(owner, key, new DynamicJobStore.Keyed(jobId, fingerprint));
            }
            return null;
        });
        if (earlier != null) {
            return earlier;
        }

        JobRunRegistry.Reservation reservation = reserve(jobId, executionId, provenance, entry, key);
        RepositoryConnector connector;
        try {
            connector = crawler.get();
        } catch (ConnectorResolutionException e) {
            reservation.release();
            log.warn("Dynamic job: connector '{}' could not be built: {}", registration.name(), e.getMessage());
            throw forget(entry, key, notConfigured(registration.name()));
        }
        log.info("Dynamic job {} [Exec ID: {}] started by '{}' ({}) on '{}' from {} '{}'.", jobId, executionId,
                requester.principal(), triggerSource, registration.name(), kind.name().toLowerCase(java.util.Locale.ROOT), scanFrom);
        reservation.start(stopRequested -> crawl(entry, connector, scanFrom, stopRequested, provenance));
        return new Accepted(jobId, executionId, STATUS_PATH + jobId, State.RUNNING, false);
    }

    /** Files a job for an administrator to approve, instead of starting it. */
    private Accepted fileForApproval(Requester requester, String key, Object fingerprint, ConnectorDTO registration,
                                     String name, Kind kind, DiscoveryNode target, Pending pending) {
        String owner = requester.owner();
        Entry entry = Entry.of(newJobId(), newExecutionId(), name, registration.name(), kind, StartView.of(target), owner,
                requester.principal(), requester.provenance().triggerSource(), clock.instant(), chunkingOf(fingerprint),
                State.PENDING_APPROVAL, pending);
        Accepted earlier = store.locked(() -> {
            evict();
            Accepted replay = replayLocked(owner, key, fingerprint);
            if (replay != null) {
                return replay;
            }
            int limit = properties.getMaxPendingPerUser();
            if (limit > 0 && store.count(owner, State.PENDING_APPROVAL) >= limit) {
                throw new DynamicJobException(HttpStatus.TOO_MANY_REQUESTS,
                        "You already have " + limit + " dynamic jobs waiting for approval.");
            }
            store.save(entry);
            if (key != null) {
                store.saveKey(owner, key, new DynamicJobStore.Keyed(entry.jobId(), fingerprint));
            }
            return null;
        });
        if (earlier != null) {
            return earlier;
        }
        log.info("Dynamic job {} by '{}' on '{}' is waiting for approval.", entry.jobId(), requester.principal(), registration.name());
        return new Accepted(entry.jobId(), entry.executionId(), STATUS_PATH + entry.jobId(), State.PENDING_APPROVAL, false);
    }

    /**
     * Starts an approved job under its own id. A limit that is reached leaves it pending ({@code 429}), so it can be
     * approved again later.
     */
    private Accepted launchApproved(Entry entry, Requester requester, ConnectorDTO registration,
                                    Supplier<RepositoryConnector> crawler, String scanFrom, int runningLimit) {
        store.locked(() -> {
            if (runningLimit > 0 && store.count(entry.owner(), State.RUNNING, State.PAUSED) >= runningLimit) {
                throw new DynamicJobException(HttpStatus.TOO_MANY_REQUESTS,
                        "Its owner already has " + runningLimit + " dynamic jobs running; approve it again later.");
            }
            return null;
        });
        JobRunRegistry.Reservation reservation = switch (registry.reserve(entry.jobId(), entry.executionId(),
                JobController.callerKey(requester.provenance()))) {
            case JobRunRegistry.Reserved reserved -> reserved.reservation();
            default -> throw new DynamicJobException(HttpStatus.TOO_MANY_REQUESTS,
                    "The runtime is running as many jobs as it may; approve it again later.");
        };
        RepositoryConnector connector;
        try {
            connector = crawler.get();
        } catch (ConnectorResolutionException e) {
            reservation.release();
            log.warn("Dynamic job: connector '{}' could not be built: {}", registration.name(), e.getMessage());
            throw notConfigured(registration.name());
        }
        Entry started = update(entry.jobId(), job -> job.withPending(null).withState(State.RUNNING));
        log.info("Dynamic job {} [Exec ID: {}] approved by '{}' and started for '{}' on '{}' from '{}'.", entry.jobId(),
                entry.executionId(), entry.approvedBy(), requester.principal(), registration.name(), scanFrom);
        ExecutionProvenance provenance = requester.provenance();
        reservation.start(stopRequested -> crawl(started != null ? started : entry, connector, scanFrom, stopRequested, provenance));
        return new Accepted(entry.jobId(), entry.executionId(), STATUS_PATH + entry.jobId(), State.RUNNING, false);
    }

    /** What a job waiting for approval needs to be checked and started again. */
    record Pending(Caller caller, Requester requester, String connector, Object request) {}

    /**
     * Approves a job waiting for approval and starts it. Its folder or document is checked again under the
     * requester's identity, since access may have changed while it waited; if that fails, the job ends
     * {@code FAILED} with the reason in its note.
     *
     * @throws DynamicJobException {@code 403} unless the approver is in the admin groups, or when it approves its own
     *                             job; {@code 404} for no such job; {@code 409} when it is not waiting for approval;
     *                             {@code 429} when a limit is reached (the job stays pending)
     */
    public JobStatus approve(Caller approver, String jobId) {
        requireAdmin(approver);
        Entry entry = store.locked(() -> {
            Entry found = existing(jobId);
            if (found.owner().equals(owner(approver))) {
                throw new DynamicJobException(HttpStatus.FORBIDDEN, "A job cannot be approved by the user who asked for it.");
            }
            if (found.state() != State.PENDING_APPROVAL || found.approvedBy() != null) {
                throw new DynamicJobException(HttpStatus.CONFLICT, "Dynamic job '" + jobId + "' is not waiting for approval.");
            }
            Entry approving = found.withApprovedBy(approver.principal());
            store.save(approving);
            return approving;
        });
        Pending pending = entry.pending();
        try {
            ConnectorDTO registration = registration(pending.connector(), true);
            int limit = properties.getMaxRunningPerUser();
            if (pending.request() instanceof JobRequest request) {
                DiscoveryNode start = resolveStart(pending.caller(), registration, request.start());
                launch(pending.requester(), null, null, registration, entry.name(), Kind.FOLDER, start,
                        () -> builder.apply(registration), start.sourceId(), limit, entry);
            } else {
                document(pending.requester(), registration, (DocumentRequest) pending.request(), null,
                        SourceAclVisibility.forCaller(pending.caller()), limit, null, entry);
            }
        } catch (DynamicJobException e) {
            if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
                update(jobId, job -> job.withApprovedBy(null));
                throw e;
            }
            update(jobId, job -> job.withPending(null).withNote("Approved, but not started: " + e.getMessage())
                    .finished(State.FAILED, null, clock.instant()));
            log.info("Dynamic job {} approved by '{}' but not started: {}", jobId, approver.principal(), e.getMessage());
        }
        return store.get(jobId).orElse(entry).view();
    }

    /**
     * Rejects a job waiting for approval.
     *
     * @param reason shown in the job's note; optional, at most {@value #MAX_NOTE_LENGTH} characters
     */
    public JobStatus reject(Caller approver, String jobId, String reason) {
        requireAdmin(approver);
        existingNow(jobId);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && (why.length() > MAX_NOTE_LENGTH || why.chars().anyMatch(Character::isISOControl))) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "A reason may be at most " + MAX_NOTE_LENGTH + " characters, with no control characters.");
        }
        Entry rejected = store.locked(() -> {
            Entry found = existing(jobId);
            if (found.state() != State.PENDING_APPROVAL || found.approvedBy() != null) {
                throw new DynamicJobException(HttpStatus.CONFLICT, "Dynamic job '" + jobId + "' is not waiting for approval.");
            }
            Entry ended = found.withPending(null).withNote("Rejected by " + approver.principal() + (why != null ? ": " + why : "."))
                    .finished(State.REJECTED, null, clock.instant());
            store.save(ended);
            return ended;
        });
        log.info("Dynamic job {} rejected by '{}'.", jobId, approver.principal());
        return rejected.view();
    }

    static final int MAX_NOTE_LENGTH = 500;

    private void requireAdmin(Caller caller) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        if (!isAdmin(caller)) {
            throw new DynamicJobException(HttpStatus.FORBIDDEN, "Only members of the admin groups may approve or reject jobs.");
        }
    }

    /** The job, after expiring and evicting what is due; {@code 404} for none. Holds the store's lock. */
    private Entry existing(String jobId) {
        evict();
        Entry entry = jobId == null ? null : store.get(jobId).orElse(null);
        if (entry == null) {
            throw new DynamicJobException(HttpStatus.NOT_FOUND, "No dynamic job '" + jobId + "'.");
        }
        return entry;
    }

    private Entry existingNow(String jobId) {
        return store.locked(() -> existing(jobId));
    }

    /** Changes a stored job under the store's lock; {@code null} when it is gone. */
    private Entry update(String jobId, java.util.function.UnaryOperator<Entry> change) {
        return store.locked(() -> store.get(jobId).map(job -> {
            Entry changed = change.apply(job);
            store.save(changed);
            return changed;
        }).orElse(null));
    }

    private static void closeQuietly(java.io.InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (java.io.IOException e) {
                log.debug("Dynamic job: closing a content stream failed: {}", e.getMessage());
            }
        }
    }

    private JobRunRegistry.Reservation reserve(String jobId, String executionId, ExecutionProvenance provenance,
                                               Entry entry, String key) {
        return switch (registry.reserve(jobId, executionId, JobController.callerKey(provenance))) {
            case JobRunRegistry.Reserved reserved -> reserved.reservation();
            case JobRunRegistry.LimitReached limit -> throw forget(entry, key, new DynamicJobException(
                    HttpStatus.TOO_MANY_REQUESTS, "The runtime is running as many jobs as it may; try again later."));
            case JobRunRegistry.CallerLimitReached limit -> throw forget(entry, key, new DynamicJobException(
                    HttpStatus.TOO_MANY_REQUESTS, "You already have " + limit.limit() + " jobs running."));
            case JobRunRegistry.AlreadyRunning running -> throw forget(entry, key, new DynamicJobException(
                    HttpStatus.CONFLICT, "Job id collision; try again."));
        };
    }

    /**
     * Publishes a tombstone for a document the repository no longer has, on the request thread. A failure is
     * logged and recorded in the ledger but not reported: the answer must not depend on whether the document ever
     * existed.
     */
    private void publishTombstone(Requester requester, ConnectorDTO registration, DocumentRequest request, String indexedId) {
        RepositoryDocument tombstone = RepositoryDocument.createTombstone(indexedId, indexedId);
        String jobId = newJobId();
        String executionId = newExecutionId();
        try {
            boolean published = runner.run(new SingleDocumentConnector(tombstone, null), indexedId, jobId,
                    "Removal of " + request.document() + " on " + registration.name(), executionId, () -> false,
                    requester.provenance(), null);
            log.info("Dynamic job {} [Exec ID: {}]: '{}' asked to remove '{}' from the index ({}): {}.", jobId,
                    executionId, requester.principal(), indexedId, registration.name(), published ? "published" : "failed");
        } catch (RuntimeException e) {
            log.error("Dynamic job {}: the tombstone for '{}' could not be published: {}", jobId, indexedId, e.getMessage(), e);
        } finally {
            Thread.interrupted();
        }
    }

    private static DeletionAccepted deletionAccepted(ConnectorDTO registration, DocumentRequest request) {
        return new DeletionAccepted(registration.name(), request.document(), DELETION_NOTE);
    }

    private static DynamicJobException documentNotAvailable(DocumentRequest request, ConnectorDTO registration) {
        return new DynamicJobException(HttpStatus.BAD_REQUEST, "No document '" + request.document()
                + "' is available to you from connector '" + registration.name() + "'.");
    }

    static final String JOB_ID_PREFIX = "dyn-";

    /** Whether {@code jobId} names a dynamic job. */
    public static boolean isDynamicJob(String jobId) {
        return jobId != null && jobId.startsWith(JOB_ID_PREFIX);
    }

    private static String newJobId() {
        return JOB_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String newExecutionId() {
        return "exec-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A job's status, if the caller may see it. */
    public JobStatus status(Caller caller, String jobId) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        return visible(caller, jobId).view();
    }

    /**
     * Whether this dynamic job is still remembered and was started for the caller (whichever agent acted for them).
     * No scope check: used to decide who may read the job's traces and reports.
     */
    public boolean isStartedFor(Caller caller, String jobId) {
        if (caller == null || jobId == null) {
            return false;
        }
        Entry entry = store.locked(() -> {
            evict();
            return store.get(jobId).orElse(null);
        });
        return entry != null && entry.owner().equals(owner(caller));
    }

    /** The jobs the caller may see, newest first. */
    public List<JobStatus> list(Caller caller) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        boolean admin = isAdmin(caller);
        String owner = owner(caller);
        List<Entry> visible = store.locked(() -> {
            evict();
            return store.list(admin ? null : owner);
        });
        return visible.stream().map(Entry::view).toList();
    }

    /** The job one of whose runs is {@code executionId}, if the caller may see it (#316). */
    public java.util.Optional<JobStatus> byExecution(Caller caller, String executionId) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        boolean admin = isAdmin(caller);
        String owner = owner(caller);
        List<Entry> visible = store.locked(() -> {
            evict();
            return store.list(admin ? null : owner);
        });
        return visible.stream().filter(job -> job.executionId().equals(executionId)).findFirst().map(Entry::view);
    }

    /**
     * The job's history (#316): the job is one run from its request, so this starts with why it waited
     * ({@code QUEUED} or {@code APPROVAL_REQUESTED}), then its approval, start, pauses, stop and end.
     */
    public List<org.opencrawling.runtime.orchestrator.RunStore.RunStep> history(Caller caller, String jobId) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        return registry.steps(visible(caller, jobId).executionId());
    }

    /** The runtime that runs or ran the job, while its run is kept; {@code null} before it started. */
    public String runtimeOf(String executionId) {
        return registry.execution(executionId).map(JobRunRegistry.RunInfo::runtimeId).orElse(null);
    }

    /** Asks a running job to stop; it ends at the latest before its next document. */
    public JobStatus stop(Caller caller, String jobId) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        visible(caller, jobId);
        Entry cancelled = store.locked(() -> {
            Entry entry = existing(jobId);
            if (entry.state() == State.PENDING_APPROVAL && entry.approvedBy() == null) {
                Entry ended = entry.withPending(null).withNote("Cancelled by " + caller.principal() + " before approval.")
                        .finished(State.STOPPED, null, clock.instant());
                store.save(ended);
                return ended;
            }
            return null;
        });
        if (cancelled != null) {
            log.info("Dynamic job {} cancelled by '{}' before approval.", jobId, caller.principal());
            return cancelled.view();
        }
        Entry stopping = store.locked(() -> {
            Entry entry = existing(jobId);
            if (!entry.state().active()) {
                throw new DynamicJobException(HttpStatus.CONFLICT, "Dynamic job '" + jobId + "' is not running.");
            }
            Entry flagged = entry.withStopRequested(true);
            store.save(flagged);
            return flagged;
        });
        registry.stop(jobId);
        log.info("Dynamic job {} stop requested by '{}'.", jobId, caller.principal());
        return stopping.view();
    }

    /**
     * Pauses a running job: it holds before its next document, in whichever runtime runs it, until it is resumed or
     * stopped. Visible to its owner and the admin groups, like its status.
     *
     * @throws DynamicJobException {@code 404} for no such job (or one the caller may not see); {@code 409} when it is
     *                             not running
     */
    public JobStatus pause(Caller caller, String jobId) {
        return changeRun(caller, jobId, true);
    }

    /** Resumes a paused job; {@code 409} when it is not paused or running. */
    public JobStatus resume(Caller caller, String jobId) {
        return changeRun(caller, jobId, false);
    }

    private JobStatus changeRun(Caller caller, String jobId, boolean pause) {
        requireCaller(caller, properties.getRequiredScope(), properties.getDocumentScope());
        Entry entry = visible(caller, jobId);
        if (!entry.state().active()
                || !(pause ? registry.pause(jobId, entry.executionId()) : registry.resume(jobId, entry.executionId()))) {
            throw new DynamicJobException(HttpStatus.CONFLICT, "Dynamic job '" + jobId + "' is not running.");
        }
        Entry changed = update(jobId, job -> job.state().active() ? job.withState(pause ? State.PAUSED : State.RUNNING) : job);
        log.info("Dynamic job {} {} by '{}'.", jobId, pause ? "paused" : "resumed", caller.principal());
        return (changed != null ? changed : entry).view();
    }

    /** A run of a dynamic job was interrupted (its runtime stopped renewing its heartbeat, or restarted). */
    private void interrupted(JobRunRegistry.RunInfo run) {
        if (run.jobId() == null || !run.jobId().startsWith(JOB_ID_PREFIX)) {
            return;
        }
        // The job is its run, which the registry has already marked INTERRUPTED: the job adds why, once.
        java.util.concurrent.atomic.AtomicBoolean noted = new java.util.concurrent.atomic.AtomicBoolean();
        update(run.jobId(), job -> {
            if (!job.executionId().equals(run.executionId())
                    || !(job.state().active() || job.state() == State.INTERRUPTED && job.note() == null)) {
                return job;
            }
            noted.set(true);
            return job.withNote("Interrupted: the runtime running it stopped.").finished(State.INTERRUPTED, null, clock.instant());
        });
        if (noted.get()) {
            log.warn("Dynamic job {} [Exec ID: {}] interrupted: its runtime stopped.", run.jobId(), run.executionId());
        }
    }

    // ---- Checks ----

    /** A verified user whose token carries one of {@code scopes} (blank scopes are not checked). */
    private void requireCaller(Caller caller, String... scopes) {
        if (caller == null || !caller.authenticated()) {
            throw new DynamicJobException(HttpStatus.UNAUTHORIZED, "Dynamic jobs need a caller verified by token "
                    + "(opencrawling.security.identity.mode=jwt; for the REST API also protect-api=true).");
        }
        if (caller.principal() == null || caller.principal().isBlank()) {
            throw new DynamicJobException(HttpStatus.FORBIDDEN, "The token names no user; dynamic jobs need one.");
        }
        List<String> wanted = java.util.Arrays.stream(scopes).filter(scope -> scope != null && !scope.isBlank())
                .map(String::strip).distinct().toList();
        if (!wanted.isEmpty() && wanted.stream().noneMatch(JwtCallerMapper.scopes(caller)::contains)) {
            throw new DynamicJobException(HttpStatus.FORBIDDEN, "The token does not carry the scope '"
                    + String.join("' or '", wanted) + "'.");
        }
    }

    private static JobRequest checked(JobRequest request) {
        if (request == null || request.connector() == null || request.connector().isBlank()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Name a registered repository connector.");
        }
        String start = request.start() == null || request.start().isBlank() ? null : request.start().strip();
        String name = request.name() == null || request.name().isBlank() ? null : request.name().strip();
        if (start != null && start.length() > MAX_START_LENGTH) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "The start folder may be at most " + MAX_START_LENGTH + " characters.");
        }
        if (name != null && (name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl))) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "The job name may be at most " + MAX_NAME_LENGTH + " characters, with no control characters.");
        }
        return new JobRequest(request.connector().strip(), start, name, normalized(request.chunking()));
    }

    static DocumentRequest checked(DocumentRequest request) {
        if (request == null || request.connector() == null || request.connector().isBlank()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Name a registered repository connector.");
        }
        if (request.document() == null || request.document().isBlank()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Name the document by its repository id.");
        }
        String document = request.document().strip();
        if (document.length() > MAX_START_LENGTH || document.chars().anyMatch(Character::isISOControl)) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "A document id may be at most " + MAX_START_LENGTH + " characters, with no control characters.");
        }
        DocumentRequest.Action action = request.action() != null ? request.action() : DocumentRequest.Action.UPSERT;
        ChunkingSpec chunking = normalized(request.chunking());
        if (chunking != null && action == DocumentRequest.Action.DELETE) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Chunking applies to UPSERT requests only.");
        }
        return new DocumentRequest(request.connector().strip(), document, action, chunking);
    }

    private static ChunkingSpec normalized(ChunkingSpec chunking) {
        return chunking == null || chunking.isEmpty() ? null : chunking;
    }

    private static ChunkingSpec chunkingOf(Object request) {
        return switch (request) {
            case JobRequest job -> job.chunking();
            case DocumentRequest document -> document.chunking();
            case null, default -> null;
        };
    }

    /** Refuses chunking outside what the runtime allows; {@code null} (the default chunking) always passes. */
    private void checkChunking(ChunkingSpec chunking) {
        if (chunking == null) {
            return;
        }
        int minSize = Math.max(1, properties.getMinChunkSize());
        int maxSize = Math.min(ChunkingSpec.LIMIT_CHUNK_SIZE, properties.getMaxChunkSize());
        if (chunking.chunkSize() != null && (chunking.chunkSize() < minSize || chunking.chunkSize() > maxSize)) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "The chunk size must be from " + minSize + " to " + maxSize + " tokens.");
        }
        if (!chunking.isWithinLimits()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "The minimum chunk lengths must be from 0 to "
                    + ChunkingSpec.LIMIT_CHARS + " characters.");
        }
    }

    private static String checkedKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey.strip()).matches()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "An idempotency key is 1 to 200 letters, digits, '.', '_', ':' or '-'.");
        }
        return idempotencyKey.strip();
    }

    /**
     * The registration of a connector dynamic jobs may use. A connector that is not listed and one that does not
     * exist are reported alike.
     */
    ConnectorDTO registration(String name, boolean requireListed) {
        boolean listed = !requireListed || properties.getConnectors().stream()
                .anyMatch(allowed -> allowed != null && allowed.strip().equalsIgnoreCase(name));
        List<ConnectorDTO> all = connectors.get();
        ConnectorDTO registration = !listed || all == null ? null : all.stream()
                .filter(connector -> connector != null && "repository".equalsIgnoreCase(connector.type()))
                .filter(connector -> connector.name() != null && connector.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (registration == null) {
            throw new DynamicJobException(HttpStatus.NOT_FOUND, "No repository connector '" + name + "' is available for dynamic jobs.");
        }
        if (!DiscoveryService.discoverable(registration)) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Repository connector '" + registration.name()
                    + "' cannot check a start folder, so it cannot run dynamic jobs.");
        }
        return registration;
    }

    /** The start folder, checked against the connector's scope and the caller's access to it. */
    private DiscoveryNode resolveStart(Caller caller, ConnectorDTO registration, String start) {
        RepositoryConnector built = build(registration);
        try {
            if (!(built instanceof DiscoverableRepositoryConnector connector)) {
                throw new DynamicJobException(HttpStatus.BAD_REQUEST, "Repository connector '" + registration.name()
                        + "' cannot check a start folder, so it cannot run dynamic jobs.");
            }
            return repositoryCall(registration, () -> connector.crawlStart(start, SourceAclVisibility.forCaller(caller)));
        } finally {
            disconnect(built);
        }
    }

    private RepositoryConnector build(ConnectorDTO registration) {
        try {
            return builder.apply(registration);
        } catch (ConnectorResolutionException e) {
            log.warn("Dynamic job: connector '{}' could not be built: {}", registration.name(), e.getMessage());
            throw notConfigured(registration.name());
        }
    }

    /**
     * Asks the repository something on the caller's behalf: a refusal ({@link IllegalArgumentException}) becomes
     * {@code 400} with the connector's own uniform message; the repository's own errors are logged, not returned.
     */
    private <T> T repositoryCall(ConnectorDTO registration, Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationUnavailableException e) {
            log.warn("Dynamic job: access could not be decided for '{}': {}", registration.name(), e.getMessage());
            throw new DynamicJobException(HttpStatus.SERVICE_UNAVAILABLE, "Access could not be decided; nothing was started.");
        } catch (DiscoveryException e) {
            // The repository's own error may name internal hosts; it is logged, not returned.
            log.warn("Dynamic job: repository '{}' failed: {}", registration.name(), e.getMessage(), e);
            throw new DynamicJobException(HttpStatus.BAD_GATEWAY, "The repository could not be reached; nothing was started.");
        }
    }

    private static DynamicJobException notConfigured(String connector) {
        return new DynamicJobException(HttpStatus.CONFLICT, "Repository connector '" + connector + "' is not configured correctly.");
    }

    private boolean isAdmin(Caller caller) {
        return caller.groups().stream().anyMatch(group -> properties.getAdminGroups().contains(group));
    }

    /** Who owns a job: the user (and tenant) it was started for, whichever agent acted for them. */
    private static String owner(Caller caller) {
        return (caller.tenant() == null ? "" : caller.tenant()) + "/" + caller.principal();
    }

    private Entry visible(Caller caller, String jobId) {
        Entry entry = store.locked(() -> {
            evict();
            return jobId == null ? null : store.get(jobId).orElse(null);
        });
        if (entry == null || !(entry.owner().equals(owner(caller)) || isAdmin(caller))) {
            throw new DynamicJobException(HttpStatus.NOT_FOUND, "No dynamic job '" + jobId + "'.");
        }
        return entry;
    }

    // ---- Idempotency and retention ----

    /** The earlier answer for this owner's key, or {@code null} when the key is new (or absent). */
    private Accepted replay(String owner, String key, Object request) {
        if (key == null) {
            return null;
        }
        return store.locked(() -> {
            evict();
            return replayLocked(owner, key, request);
        });
    }

    /**
     * {@link #replay}, holding the store's lock. {@code request} is the checked request: a {@link JobRequest} or a
     * {@link DocumentRequest}.
     */
    private Accepted replayLocked(String owner, String key, Object request) {
        if (key == null) {
            return null;
        }
        DynamicJobStore.Keyed keyed = store.key(owner, key).orElse(null);
        if (keyed == null) {
            return null;
        }
        if (!keyed.request().equals(request)) {
            throw new DynamicJobException(HttpStatus.CONFLICT, "This idempotency key was already used for a different job.");
        }
        Entry entry = store.get(keyed.jobId()).orElse(null);
        if (entry == null) {
            store.deleteKey(owner, key);
            return null;
        }
        return new Accepted(entry.jobId(), entry.executionId(), STATUS_PATH + entry.jobId(), entry.state(), true);
    }

    /** Drops a job that could not be started, and returns {@code failure} to throw. */
    private DynamicJobException forget(Entry entry, String key, DynamicJobException failure) {
        store.locked(() -> {
            store.delete(entry.jobId());
            if (key != null) {
                store.deleteKey(entry.owner(), key);
            }
            return null;
        });
        return failure;
    }

    /**
     * Expires jobs left unapproved past the approval timeout, then drops finished jobs past their retention and the
     * oldest finished ones beyond the cap. Holds the store's lock.
     */
    private void evict() {
        Instant now = clock.instant();
        for (Entry job : store.unansweredBefore(now.minus(properties.getApprovalTimeout()))) {
            store.save(job.withPending(null).withNote("Not approved within " + properties.getApprovalTimeout() + ".")
                    .finished(State.EXPIRED, null, now));
        }
        store.evict(now.minus(properties.getRetention()), Math.max(0, properties.getMaxRetained()));
    }

    // ---- The run ----

    private void crawl(Entry entry, RepositoryConnector connector, String start, BooleanSupplier stopRequested,
                       ExecutionProvenance provenance) {
        boolean succeeded = false;
        try {
            succeeded = runner.run(connector, start, entry.jobId(), entry.name(), entry.executionId(), stopRequested, provenance,
                    entry.chunking());
        } catch (RuntimeException e) {
            log.error("Dynamic job {} failed: {}", entry.jobId(), e.getMessage(), e);
        } finally {
            // A stop interrupts this thread; clear it so the bookkeeping below is not cut short.
            Thread.interrupted();
            disconnect(connector);
            State end = stopRequested.getAsBoolean() ? State.STOPPED : succeeded ? State.COMPLETED : State.FAILED;
            Long documents = null;
            try {
                documents = documentCount.apply(entry.executionId());
            } catch (RuntimeException e) {
                log.debug("Dynamic job {}: no document count: {}", entry.jobId(), e.getMessage());
            }
            Long counted = documents;
            // A job interrupted meanwhile (another runtime found this one silent) keeps its interruption.
            Entry ended = update(entry.jobId(), job -> job.state().active() ? job.finished(end, counted, clock.instant()) : job);
            log.info("Dynamic job {} [Exec ID: {}] ended: {}.", entry.jobId(), entry.executionId(),
                    ended != null ? ended.state() : end);
        }
    }

    private static void disconnect(RepositoryConnector connector) {
        try {
            connector.disconnect();
        } catch (Exception e) {
            log.debug("Dynamic job: disconnecting failed: {}", e.getMessage());
        }
    }

    /** A dynamic job as stored. {@code pending} holds what is needed to start it once approved; it is never shown. */
    record Entry(String jobId, String executionId, String name, String connector, Kind kind, StartView start, String owner,
                 String principal, String triggerSource, Instant createdAt, ChunkingSpec chunking, State state,
                 boolean stopRequested, Instant finishedAt, Long documents, Pending pending, String approvedBy, String note) {

        static Entry of(String jobId, String executionId, String name, String connector, Kind kind, StartView start,
                        String owner, String principal, String triggerSource, Instant createdAt, ChunkingSpec chunking,
                        State state, Pending pending) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, false, null, null, pending, null, null);
        }

        Entry withState(State state) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, stopRequested, finishedAt, documents, pending, approvedBy, note);
        }

        Entry withPending(Pending pending) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, stopRequested, finishedAt, documents, pending, approvedBy, note);
        }

        Entry withApprovedBy(String approvedBy) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, stopRequested, finishedAt, documents, pending, approvedBy, note);
        }

        Entry withNote(String note) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, stopRequested, finishedAt, documents, pending, approvedBy, note);
        }

        Entry withStopRequested(boolean stopRequested) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, state, stopRequested, finishedAt, documents, pending, approvedBy, note);
        }

        Entry finished(State end, Long documents, Instant at) {
            return new Entry(jobId, executionId, name, connector, kind, start, owner, principal, triggerSource, createdAt,
                    chunking, end, stopRequested, at, documents, pending, approvedBy, note);
        }

        JobStatus view() {
            return new JobStatus(jobId, executionId, name, connector, kind, start, state, stopRequested, createdAt,
                    finishedAt, documents, principal, triggerSource, approvedBy, note, chunking);
        }
    }

    /** For tests: the ids of the jobs held, oldest first. */
    List<String> heldJobIds() {
        return store.ids();
    }
}
