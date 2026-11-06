/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DynamicJobServiceTest {

    static final ConnectorDTO FAKE = new ConnectorDTO("Fake_Repo", "A fake repository", "repository",
            FakeDiscoverableConnector.class.getName(), 1, Map.of("password", "s3cret", "endpoint", "http://internal:8080"));
    static final ConnectorDTO UNLISTED = new ConnectorDTO("Other_Repo", "Registered, not listed", "repository",
            FakeDiscoverableConnector.class.getName(), 1, Map.of());
    static final ConnectorDTO FILES = new ConnectorDTO("Files", "File system", "repository",
            FileSystemRepositoryConnector.class.getName(), 1, Map.of());
    static final String SCOPE = "oc:jobs:write";

    /** A crawl that records its arguments and, while {@link #hold} is closed, stays in progress. */
    final class Runner implements DynamicJobService.CrawlRunner {
        final List<String> starts = Collections.synchronizedList(new ArrayList<>());
        final List<ExecutionProvenance> provenances = Collections.synchronizedList(new ArrayList<>());
        final List<RepositoryConnector> connectors = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch hold = new CountDownLatch(0);
        volatile boolean succeed = true;
        volatile RuntimeException failure;

        @Override
        public boolean run(RepositoryConnector connector, String start, String jobId, String jobName, String executionId,
                           BooleanSupplier stopRequested, ExecutionProvenance provenance) {
            starts.add(start);
            provenances.add(provenance);
            connectors.add(connector);
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return false;
            }
            if (failure != null) {
                throw failure;
            }
            return succeed;
        }
    }

    final List<FakeDiscoverableConnector> built = Collections.synchronizedList(new ArrayList<>());
    Function<ConnectorDTO, RepositoryConnector> builder = dto -> {
        FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
        built.add(connector);
        return connector;
    };
    final Runner runner = new Runner();
    final DynamicJobProperties properties = new DynamicJobProperties();
    final Map<String, Long> documentCounts = new java.util.concurrent.ConcurrentHashMap<>();
    final MutableClock clock = new MutableClock();
    JobRunRegistry registry = new JobRunRegistry(0);
    DynamicJobService service;

    static Caller caller(String principal, String... groups) {
        return new Caller(principal, List.of(groups), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("openid", SCOPE)));
    }

    /** A caller whose token carries only the single-document scope. */
    static Caller documentCaller(String principal, String... groups) {
        return new Caller(principal, List.of(groups), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("oc:jobs:run")));
    }

    static DynamicJobService.DocumentRequest upsert(String document) {
        return new DynamicJobService.DocumentRequest("Fake_Repo", document, null);
    }

    static DynamicJobService.DocumentRequest delete(String document) {
        return new DynamicJobService.DocumentRequest("Fake_Repo", document, DynamicJobService.DocumentRequest.Action.DELETE);
    }

    /** What the crawl of a job's connector emits. */
    static List<RepositoryDocument> emitted(RepositoryConnector connector) {
        return connector.scan("ignored").collectList().block();
    }

    static DynamicJobService.JobRequest request(String start) {
        return new DynamicJobService.JobRequest("Fake_Repo", start, null);
    }

    @BeforeEach
    void setUp() {
        properties.setConnectors(List.of("fake_repo", "Files"));
        properties.setAdminGroups(List.of("oc-admins"));
        service();
    }

    void service() {
        service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES), dto -> builder.apply(dto), registry,
                runner, documentCounts::get, properties, clock);
    }

    @AfterEach
    void release() {
        runner.hold.countDown();
    }

    DynamicJobService.JobStatus awaitEnd(Caller caller, String jobId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            DynamicJobService.JobStatus status = service.status(caller, jobId);
            if (status.state() != DynamicJobService.State.RUNNING) {
                return status;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Job " + jobId + " did not end");
    }

    /** The registry frees a run's slot just after the job has recorded its end. */
    void awaitNoRuns() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!registry.runs().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(registry.runs()).isEmpty();
    }

    static HttpStatus statusOf(Runnable call) {
        DynamicJobException e = catchThrowableOfType(DynamicJobException.class, call::run);
        assertThat(e).as("expected a DynamicJobException").isNotNull();
        return e.status();
    }

    // ---- Who may ask ----

    @Test
    void onlyAVerifiedUserWithTheScopeMayStartJobs() {
        assertThat(statusOf(() -> service.start(null, request(null), null, "api", null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> service.start(Caller.asserted("alice", List.of("finance")), request(null), null, "api", null)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> service.start(caller(null, "finance"), request(null), null, "api", null)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        Caller noScope = new Caller("alice", List.of("finance"), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("openid")));
        assertThat(statusOf(() -> service.start(noScope, request(null), null, "api", null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.validate(noScope, request(null)))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.list(noScope))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(runner.starts).isEmpty();
        assertThat(built).isEmpty();
    }

    @Test
    void aBlankRequiredScopeIsNotChecked() {
        properties.setRequiredScope(" ");
        Caller noScope = new Caller("alice", List.of(), null, true, Map.of());

        assertThat(service.validate(noScope, request(null)).valid()).isTrue();
    }

    // ---- Which connector ----

    @Test
    void onlyListedRegisteredConnectorsCanBeUsedAndAbsentOnesReadAlike() {
        DynamicJobException unlisted = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("alice"), new DynamicJobService.JobRequest("Other_Repo", null, null), null, "api", null));
        DynamicJobException missing = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("alice"), new DynamicJobService.JobRequest("Nowhere", null, null), null, "api", null));

        assertThat(unlisted.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unlisted.getMessage()).isEqualTo("No repository connector 'Other_Repo' is available for dynamic jobs.");
        assertThat(missing.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getMessage()).isEqualTo("No repository connector 'Nowhere' is available for dynamic jobs.");
        assertThat(built).isEmpty();
    }

    @Test
    void aConnectorThatCannotCheckAStartFolderCannotRunDynamicJobs() {
        assertThat(statusOf(() -> service.start(caller("alice"), new DynamicJobService.JobRequest("Files", null, null), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(built).isEmpty();
        assertThat(statusOf(() -> service.start(caller("alice"), new DynamicJobService.JobRequest(" ", null, null), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- Which folder ----

    @Test
    void theStartFolderMustBeOneTheCallerMayReadAndOthersReadLikeMissingOnes() {
        DynamicJobException hidden = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("carol"), request("finance-folder"), null, "api", null));
        DynamicJobException absent = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("carol"), request("nowhere"), null, "api", null));

        assertThat(hidden.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(hidden.getMessage()).isEqualTo("No fake folder 'finance-folder' in this connector's scope.");
        assertThat(absent.getMessage()).isEqualTo("No fake folder 'nowhere' in this connector's scope.");
        assertThat(runner.starts).isEmpty();
        // As for search (#224): the group finance does not let a user named finance in.
        assertThat(statusOf(() -> service.start(caller("finance"), request("finance-folder"), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void repositoryAndAuthorizationFailuresStartNothingAndLeakNothing() {
        builder = dto -> {
            FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
            connector.failure = () -> new DiscoveryException("connect to http://internal:8080 refused", null);
            built.add(connector);
            return connector;
        };
        DynamicJobException down = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("alice", "finance"), request(null), null, "api", null));
        assertThat(down.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(down.getMessage()).doesNotContain("internal");
        assertThat(built).allMatch(connector -> connector.disconnected);

        builder = dto -> {
            FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
            connector.failure = () -> new AuthorizationUnavailableException("store down", null);
            return connector;
        };
        assertThat(statusOf(() -> service.start(caller("alice", "finance"), request(null), null, "api", null)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        builder = dto -> {
            throw new ConnectorResolutionException("bad password for http://internal:8080", null);
        };
        DynamicJobException misconfigured = catchThrowableOfType(DynamicJobException.class,
                () -> service.start(caller("alice", "finance"), request(null), null, "api", null));
        assertThat(misconfigured.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(misconfigured.getMessage()).doesNotContain("internal", "password");
        assertThat(runner.starts).isEmpty();
        assertThat(service.list(caller("alice"))).isEmpty();
    }

    // ---- Running ----

    @Test
    void aJobCrawlsTheResolvedFolderAndRecordsWhoStartedIt() {
        Caller agentForAlice = new Caller("alice", List.of("finance"), "acme", true,
                Map.of(JwtCallerMapper.SCOPES, List.of(SCOPE), JwtCallerMapper.ACTOR, "ingest-agent"));

        // Held until its document count is known, or the run could end and read no count first.
        runner.hold = new CountDownLatch(1);
        DynamicJobService.Accepted accepted = service.start(agentForAlice,
                new DynamicJobService.JobRequest("Fake_Repo", "finance-folder", "Q3 reports"), null, "mcp", "req-1");
        documentCounts.put(accepted.executionId(), 7L);
        runner.hold.countDown();
        DynamicJobService.JobStatus status = awaitEnd(agentForAlice, accepted.jobId());

        assertThat(accepted.jobId()).startsWith("dyn-");
        assertThat(accepted.statusUrl()).isEqualTo("/api/jobs/dynamic/" + accepted.jobId());
        assertThat(accepted.replayed()).isFalse();
        assertThat(runner.starts).containsExactly("finance-folder");
        ExecutionProvenance provenance = runner.provenances.get(0);
        assertThat(provenance.callerVerified()).isTrue();
        assertThat(provenance.triggeredBy()).isEqualTo("ingest-agent");
        assertThat(provenance.onBehalfOf()).isEqualTo("alice");
        assertThat(provenance.callerTenant()).isEqualTo("acme");
        assertThat(provenance.triggerSource()).isEqualTo("mcp");
        assertThat(provenance.correlationId()).isEqualTo("req-1");
        assertThat(status.state()).isEqualTo(DynamicJobService.State.COMPLETED);
        assertThat(status.documents()).isEqualTo(7L);
        assertThat(status.name()).isEqualTo("Q3 reports");
        assertThat(status.start().id()).isEqualTo("finance-folder");
        assertThat(status.owner()).isEqualTo("alice");
        // The crawl got its own connector, disconnected once it ended; so was the one that checked the folder.
        assertThat(built).hasSize(2).allMatch(connector -> connector.disconnected);
        assertThat(runner.connectors.get(0)).isSameAs(built.get(1));
    }

    @Test
    void withoutAStartTheConnectorsConfiguredRootIsCrawled() {
        DynamicJobService.Accepted accepted = service.start(caller("carol"), request(null), null, "api", null);
        awaitEnd(caller("carol"), accepted.jobId());

        assertThat(runner.starts).containsExactly("root");
    }

    @Test
    void validateOnlyChecksEverythingAndStartsNothing() {
        DynamicJobService.Validated validated = service.validate(caller("alice", "finance"), request("finance-folder"));

        assertThat(validated.valid()).isTrue();
        assertThat(validated.start().id()).isEqualTo("finance-folder");
        assertThat(runner.starts).isEmpty();
        assertThat(service.list(caller("alice"))).isEmpty();
        assertThat(statusOf(() -> service.validate(caller("carol"), request("finance-folder")))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aFailedOrThrowingCrawlEndsFailed() {
        runner.succeed = false;
        String failed = service.start(caller("alice"), request(null), null, "api", null).jobId();
        assertThat(awaitEnd(caller("alice"), failed).state()).isEqualTo(DynamicJobService.State.FAILED);

        runner.succeed = true;
        runner.failure = new IllegalStateException("boom");
        String threw = service.start(caller("alice"), request(null), null, "api", null).jobId();
        assertThat(awaitEnd(caller("alice"), threw).state()).isEqualTo(DynamicJobService.State.FAILED);
        awaitNoRuns();
    }

    @Test
    void theOwnerCanStopARunningJob() {
        runner.hold = new CountDownLatch(1);
        String jobId = service.start(caller("alice"), request(null), null, "api", null).jobId();

        DynamicJobService.JobStatus stopping = service.stop(caller("alice"), jobId);

        assertThat(stopping.stopRequested()).isTrue();
        assertThat(awaitEnd(caller("alice"), jobId).state()).isEqualTo(DynamicJobService.State.STOPPED);
        assertThat(statusOf(() -> service.stop(caller("alice"), jobId))).isEqualTo(HttpStatus.CONFLICT);
        awaitNoRuns();
    }

    // ---- Who sees what ----

    @Test
    void aJobIsVisibleToItsUserAndToAdminsOnly() {
        runner.hold = new CountDownLatch(1);
        String alices = service.start(caller("alice"), request(null), null, "api", null).jobId();
        Caller agentForAlice = new Caller("alice", List.of(), null, true,
                Map.of(JwtCallerMapper.SCOPES, List.of(SCOPE), JwtCallerMapper.ACTOR, "another-agent"));
        Caller aliceInOtherTenant = new Caller("alice", List.of(), "other", true, Map.of(JwtCallerMapper.SCOPES, List.of(SCOPE)));

        assertThat(service.status(agentForAlice, alices).jobId()).isEqualTo(alices);
        assertThat(service.status(caller("root-user", "oc-admins"), alices).jobId()).isEqualTo(alices);
        DynamicJobException other = catchThrowableOfType(DynamicJobException.class, () -> service.status(caller("bob"), alices));
        DynamicJobException none = catchThrowableOfType(DynamicJobException.class, () -> service.status(caller("bob"), "dyn-nothing"));
        assertThat(other.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getMessage()).isEqualTo("No dynamic job '" + alices + "'.");
        assertThat(none.getMessage()).isEqualTo("No dynamic job 'dyn-nothing'.");
        assertThat(statusOf(() -> service.stop(caller("bob"), alices))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> service.status(aliceInOtherTenant, alices))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(service.list(caller("bob"))).isEmpty();
        assertThat(service.list(caller("root-user", "oc-admins"))).extracting(DynamicJobService.JobStatus::jobId).containsExactly(alices);
        assertThat(service.status(caller("alice"), alices).state()).isEqualTo(DynamicJobService.State.RUNNING);
    }

    // ---- One document ----

    @Test
    void aReadableDocumentInScopeIsIngestedAsAOneDocumentJob() {
        Object result = service.document(documentCaller("alice", "finance"), upsert("finance-report"), null, "mcp", "wf-9");

        assertThat(result).isInstanceOf(DynamicJobService.Accepted.class);
        DynamicJobService.Accepted accepted = (DynamicJobService.Accepted) result;
        DynamicJobService.JobStatus status = awaitEnd(documentCaller("alice"), accepted.jobId());
        assertThat(status.state()).isEqualTo(DynamicJobService.State.COMPLETED);
        assertThat(status.kind()).isEqualTo(DynamicJobService.Kind.DOCUMENT);
        assertThat(status.start().id()).isEqualTo("finance-report");
        assertThat(runner.starts).containsExactly("finance-report");
        List<RepositoryDocument> documents = emitted(runner.connectors.get(0));
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("fake://finance-report");
            assertThat(document.action()).isEqualTo(DocumentAction.UPSERT);
            assertThat(document.security()).isNotNull();
        });
        assertThat(runner.provenances.get(0).correlationId()).isEqualTo("wf-9");
        // The connector that fetched the document stays open for the run, and is closed after it.
        assertThat(built).singleElement().satisfies(connector -> assertThat(connector.disconnected).isTrue());
    }

    @Test
    void hiddenAndMissingDocumentsAreRefusedAlikeAndNothingRuns() {
        String hidden = catchThrowableOfType(DynamicJobException.class,
                () -> service.document(documentCaller("carol"), upsert("finance-report"), null, "api", null)).getMessage();
        String unknown = catchThrowableOfType(DynamicJobException.class,
                () -> service.document(documentCaller("carol"), upsert("nope"), null, "api", null)).getMessage();
        String gone = catchThrowableOfType(DynamicJobException.class,
                () -> service.document(documentCaller("carol"), upsert("deleted-old"), null, "api", null)).getMessage();

        assertThat(hidden).isEqualTo("No document 'finance-report' is available to you from connector 'Fake_Repo'.");
        assertThat(unknown).isEqualTo("No document 'nope' is available to you from connector 'Fake_Repo'.");
        assertThat(gone).isEqualTo("No document 'deleted-old' is available to you from connector 'Fake_Repo'.");
        assertThat(runner.starts).isEmpty();
        assertThat(built).hasSize(3).allMatch(connector -> connector.disconnected);
        // As for search (#224): the group finance does not let a user named finance in.
        assertThat(statusOf(() -> service.document(documentCaller("finance"), upsert("finance-report"), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDocumentTheRepositoryNoLongerHasIsRemovedWithATombstone() {
        Object result = service.document(documentCaller("alice"), delete("deleted-old"), null, "mcp", null);

        assertThat(result).isEqualTo(new DynamicJobService.DeletionAccepted("Fake_Repo", "deleted-old",
                DynamicJobService.DELETION_NOTE));
        assertThat(runner.starts).containsExactly("fake://deleted-old");
        assertThat(emitted(runner.connectors.get(0))).singleElement().satisfies(tombstone -> {
            assertThat(tombstone.id()).isEqualTo("fake://deleted-old");
            assertThat(tombstone.action()).isEqualTo(DocumentAction.DELETE);
        });
        assertThat(runner.provenances.get(0).triggeredBy()).isEqualTo("alice");
        assertThat(service.list(documentCaller("alice"))).isEmpty();
    }

    @Test
    void aDeleteOfADocumentTheCallerCannotSeeAnswersLikeOneThatWasRemovedAndRemovesNothing() {
        Object hidden = service.document(documentCaller("carol"), delete("finance-report"), null, "api", null);
        Object unknown = service.document(documentCaller("carol"), delete("nope"), null, "api", null);

        assertThat(hidden).isEqualTo(new DynamicJobService.DeletionAccepted("Fake_Repo", "finance-report",
                DynamicJobService.DELETION_NOTE));
        assertThat(unknown).isEqualTo(new DynamicJobService.DeletionAccepted("Fake_Repo", "nope",
                DynamicJobService.DELETION_NOTE));
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void aLiveDocumentIsNeverRemoved() {
        DynamicJobException e = catchThrowableOfType(DynamicJobException.class,
                () -> service.document(documentCaller("alice", "finance"), delete("finance-report"), null, "api", null));

        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void aTombstoneThatCannotBePublishedStillGetsTheSameAnswer() {
        runner.failure = new IllegalStateException("kafka down at internal-host");

        assertThat(service.document(documentCaller("alice"), delete("deleted-old"), null, "api", null))
                .isEqualTo(new DynamicJobService.DeletionAccepted("Fake_Repo", "deleted-old", DynamicJobService.DELETION_NOTE));
    }

    @Test
    void eachOperationNeedsItsOwnScopeAndEitherScopeFollowsJobs() {
        assertThat(statusOf(() -> service.start(documentCaller("alice"), request(null), null, "api", null)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.document(caller("alice", "finance"), upsert("finance-report"), null, "api", null)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.document(Caller.asserted("alice", List.of("finance")), upsert("finance-report"), null, "api", null)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String jobId = ((DynamicJobService.Accepted) service.document(documentCaller("alice", "finance"),
                upsert("finance-report"), null, "api", null)).jobId();

        assertThat(service.status(caller("alice"), jobId).jobId()).isEqualTo(jobId);
        assertThat(service.status(documentCaller("alice"), jobId).jobId()).isEqualTo(jobId);
        assertThat(statusOf(() -> service.status(documentCaller("bob"), jobId))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentRequestsAreCheckedAndReplayedByKey() {
        assertThat(statusOf(() -> service.document(documentCaller("alice"), upsert(" "), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.document(documentCaller("alice"),
                new DynamicJobService.DocumentRequest("Other_Repo", "finance-report", null), null, "api", null)))
                .isEqualTo(HttpStatus.NOT_FOUND);
        DynamicJobService.Accepted first = (DynamicJobService.Accepted) service.document(
                documentCaller("alice", "finance"), upsert("finance-report"), "doc-1", "api", null);
        DynamicJobService.Accepted again = (DynamicJobService.Accepted) service.document(
                documentCaller("alice", "finance"), upsert("finance-report"), "doc-1", "api", null);

        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(again.replayed()).isTrue();
        assertThat(statusOf(() -> service.document(documentCaller("alice", "finance"), upsert("alice-notes"), "doc-1", "api", null)))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- Approval ----

    static Caller admin(String principal) {
        return caller(principal, "oc-admins");
    }

    DynamicJobService.Accepted pendingFolderJob(Caller caller) {
        properties.setRequireApproval(true);
        DynamicJobService.Accepted accepted = service.start(caller, request(null), null, "mcp", null);
        assertThat(accepted.state()).isEqualTo(DynamicJobService.State.PENDING_APPROVAL);
        return accepted;
    }

    @Test
    void withApprovalsOnAnAgentsJobWaitsAndStartsOnceAnAdminApprovesIt() {
        DynamicJobService.Accepted accepted = pendingFolderJob(caller("alice"));

        assertThat(runner.starts).isEmpty();
        assertThat(registry.runs()).isEmpty();
        assertThat(service.status(caller("alice"), accepted.jobId()).state()).isEqualTo(DynamicJobService.State.PENDING_APPROVAL);

        DynamicJobService.JobStatus approved = service.approve(admin("olga"), accepted.jobId());

        assertThat(approved.jobId()).isEqualTo(accepted.jobId());
        assertThat(approved.approvedBy()).isEqualTo("olga");
        DynamicJobService.JobStatus done = awaitEnd(caller("alice"), accepted.jobId());
        assertThat(done.state()).isEqualTo(DynamicJobService.State.COMPLETED);
        assertThat(done.executionId()).isEqualTo(accepted.executionId());
        assertThat(runner.starts).containsExactly("root");
        assertThat(runner.provenances.get(0).triggeredBy()).as("the requester, not the approver").isEqualTo("alice");
    }

    @Test
    void whatTheCallerCannotSeeIsStillRefusedAtOnceNotFiledForApproval() {
        properties.setRequireApproval(true);

        assertThat(statusOf(() -> service.start(caller("carol"), request("finance-folder"), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.document(documentCaller("carol"), upsert("finance-report"), null, "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(service.list(admin("olga"))).isEmpty();
    }

    @Test
    void onlyAnotherAdminMayApproveOrReject() {
        DynamicJobService.Accepted mine = pendingFolderJob(admin("olga"));

        assertThat(statusOf(() -> service.approve(admin("olga"), mine.jobId()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.approve(caller("bob"), mine.jobId()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.reject(caller("bob"), mine.jobId(), null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.approve(caller("bob"), "dyn-nothing"))).as("no hint for non-admins").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> service.approve(admin("pete"), "dyn-nothing"))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> service.approve(Caller.asserted("pete", List.of("oc-admins")), mine.jobId())))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void aJobIsApprovedOrRejectedOnlyOnce() {
        DynamicJobService.Accepted first = pendingFolderJob(caller("alice"));
        DynamicJobService.Accepted second = pendingFolderJob(caller("alice"));

        service.approve(admin("olga"), first.jobId());
        DynamicJobService.JobStatus rejected = service.reject(admin("olga"), second.jobId(), "Out of the audit scope");

        assertThat(rejected.state()).isEqualTo(DynamicJobService.State.REJECTED);
        assertThat(rejected.note()).isEqualTo("Rejected by olga: Out of the audit scope");
        assertThat(rejected.finishedAt()).isNotNull();
        assertThat(statusOf(() -> service.approve(admin("pete"), first.jobId()))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> service.reject(admin("pete"), first.jobId(), null))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> service.approve(admin("pete"), second.jobId()))).isEqualTo(HttpStatus.CONFLICT);
        awaitEnd(caller("alice"), first.jobId());
        assertThat(runner.starts).hasSize(1);
    }

    @Test
    void aReasonIsChecked() {
        DynamicJobService.Accepted pending = pendingFolderJob(caller("alice"));

        assertThat(statusOf(() -> service.reject(admin("olga"), pending.jobId(), "x".repeat(501)))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.reject(admin("olga"), pending.jobId(), "line\nbreak"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(service.reject(admin("olga"), pending.jobId(), null).note()).isEqualTo("Rejected by olga.");
    }

    @Test
    void accessIsCheckedAgainOnApprovalAndAFailureEndsTheJob() {
        DynamicJobService.Accepted pending = pendingFolderJob(caller("alice"));
        builder = dto -> {
            FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
            connector.failure = () -> new IllegalArgumentException("No fake folder 'root' in this connector's scope.");
            built.add(connector);
            return connector;
        };

        DynamicJobService.JobStatus status = service.approve(admin("olga"), pending.jobId());

        assertThat(status.state()).isEqualTo(DynamicJobService.State.FAILED);
        assertThat(status.note()).isEqualTo("Approved, but not started: No fake folder 'root' in this connector's scope.");
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void anApprovalOverALimitLeavesTheJobPendingToApproveLater() {
        properties.setMaxRunningPerUser(1);
        runner.hold = new CountDownLatch(1);
        DynamicJobService.Accepted pending = pendingFolderJob(caller("alice"));
        properties.setRequireApproval(false);
        service.start(caller("alice"), request(null), null, "api", null);

        assertThat(statusOf(() -> service.approve(admin("olga"), pending.jobId()))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(service.status(caller("alice"), pending.jobId()).state()).isEqualTo(DynamicJobService.State.PENDING_APPROVAL);
        assertThat(service.status(caller("alice"), pending.jobId()).approvedBy()).isNull();
        runner.hold.countDown();
        awaitNoRuns();
        assertThat(service.approve(admin("olga"), pending.jobId()).state()).isIn(DynamicJobService.State.RUNNING,
                DynamicJobService.State.COMPLETED);
    }

    @Test
    void anUnansweredJobExpiresAndItsOwnerMayCancelOne() {
        properties.setApprovalTimeout(Duration.ofHours(1));
        DynamicJobService.Accepted expiring = pendingFolderJob(caller("alice"));
        DynamicJobService.Accepted cancelled = pendingFolderJob(caller("alice"));

        DynamicJobService.JobStatus stopped = service.stop(caller("alice"), cancelled.jobId());
        clock.advance(Duration.ofMinutes(61));

        assertThat(stopped.state()).isEqualTo(DynamicJobService.State.STOPPED);
        assertThat(stopped.note()).contains("Cancelled");
        DynamicJobService.JobStatus expired = service.status(caller("alice"), expiring.jobId());
        assertThat(expired.state()).isEqualTo(DynamicJobService.State.EXPIRED);
        assertThat(expired.note()).contains("Not approved within");
        assertThat(statusOf(() -> service.approve(admin("olga"), expiring.jobId()))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> service.stop(caller("bob"), expiring.jobId()))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void aUserMayHaveOnlySoManyJobsWaitingAndARepeatedKeyReturnsThePendingOne() {
        properties.setRequireApproval(true);
        properties.setMaxPendingPerUser(2);
        DynamicJobService.Accepted keyed = service.start(caller("alice"), request(null), "audit-1", "api", null);
        service.start(caller("alice"), request(null), null, "api", null);

        assertThat(statusOf(() -> service.start(caller("alice"), request(null), null, "api", null))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        DynamicJobService.Accepted again = service.start(caller("alice"), request(null), "audit-1", "api", null);
        assertThat(again.jobId()).isEqualTo(keyed.jobId());
        assertThat(again.state()).isEqualTo(DynamicJobService.State.PENDING_APPROVAL);
        assertThat(again.replayed()).isTrue();
    }

    @Test
    void aDocumentIngestWaitsAndIsFetchedAgainOnApprovalWhileRemovalsAreNotHeld() {
        properties.setRequireApproval(true);
        Object result = service.document(documentCaller("alice", "finance"), upsert("finance-report"), null, "api", null);

        assertThat(((DynamicJobService.Accepted) result).state()).isEqualTo(DynamicJobService.State.PENDING_APPROVAL);
        assertThat(built).singleElement().satisfies(connector -> assertThat(connector.disconnected).isTrue());
        String jobId = ((DynamicJobService.Accepted) result).jobId();
        assertThat(service.status(documentCaller("alice"), jobId).kind()).isEqualTo(DynamicJobService.Kind.DOCUMENT);

        service.approve(admin("olga"), jobId);
        assertThat(awaitEnd(documentCaller("alice"), jobId).state()).isEqualTo(DynamicJobService.State.COMPLETED);
        assertThat(built).hasSize(2);
        assertThat(runner.starts).containsExactly("finance-report");

        assertThat(service.document(documentCaller("alice"), delete("deleted-old"), null, "api", null))
                .isInstanceOf(DynamicJobService.DeletionAccepted.class);
        assertThat(runner.starts).containsExactly("finance-report", "fake://deleted-old");
    }

    // ---- Idempotency ----

    @Test
    void repeatingARequestWithItsKeyReturnsTheFirstJob() {
        DynamicJobService.Accepted first = service.start(caller("alice"), request(null), "order-42", "api", null);
        DynamicJobService.Accepted again = service.start(caller("alice"), request(null), "order-42", "api", null);
        DynamicJobService.Accepted bobs = service.start(caller("bob"), request(null), "order-42", "api", null);

        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(again.replayed()).isTrue();
        assertThat(bobs.jobId()).isNotEqualTo(first.jobId());
        awaitEnd(caller("alice"), first.jobId());
        awaitEnd(caller("bob"), bobs.jobId());
        assertThat(runner.starts).hasSize(2);
        assertThat(statusOf(() -> service.start(caller("alice"), request("finance-folder"), "order-42", "api", null)))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(() -> service.start(caller("alice"), request(null), "bad key!", "api", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- Limits ----

    @Test
    void aUserMayRunOnlySoManyDynamicJobsAtOnce() {
        runner.hold = new CountDownLatch(1);
        properties.setMaxRunningPerUser(2);
        service.start(caller("alice"), request(null), null, "api", null);
        service.start(caller("alice"), request(null), null, "api", null);

        assertThat(statusOf(() -> service.start(caller("alice"), request(null), "k1", "api", null)))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(service.start(caller("bob"), request(null), null, "api", null).state()).isEqualTo(DynamicJobService.State.RUNNING);
        assertThat(service.list(caller("alice"))).hasSize(2);
    }

    @Test
    void theRuntimesCapsApplyAndARefusedJobLeavesNoTrace() {
        runner.hold = new CountDownLatch(1);
        registry = new JobRunRegistry(1);
        service();
        service.start(caller("alice"), request(null), null, "api", null);

        assertThat(statusOf(() -> service.start(caller("bob"), request(null), "retry-me", "api", null)))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(service.list(caller("bob"))).isEmpty();
        runner.hold.countDown();
        awaitNoRuns();
        // The key was not spent on the refused attempt.
        assertThat(service.start(caller("bob"), request(null), "retry-me", "api", null).replayed()).isFalse();
    }

    // ---- Retention ----

    @Test
    void finishedJobsAndTheirKeysAreForgottenAfterTheRetention() {
        properties.setRetention(Duration.ofHours(1));
        String jobId = service.start(caller("alice"), request(null), "once", "api", null).jobId();
        awaitEnd(caller("alice"), jobId);

        clock.advance(Duration.ofMinutes(59));
        assertThat(service.status(caller("alice"), jobId).jobId()).isEqualTo(jobId);
        clock.advance(Duration.ofMinutes(2));
        assertThat(statusOf(() -> service.status(caller("alice"), jobId))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(service.start(caller("alice"), request(null), "once", "api", null).jobId()).isNotEqualTo(jobId);
    }

    @Test
    void onlyTheNewestFinishedJobsAreKeptAndRunningOnesNeverDrop() {
        properties.setMaxRetained(2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String jobId = service.start(caller("alice"), request(null), null, "api", null).jobId();
            awaitEnd(caller("alice"), jobId);
            ids.add(jobId);
        }
        runner.hold = new CountDownLatch(1);
        String running = service.start(caller("alice"), request(null), null, "api", null).jobId();

        assertThat(service.heldJobIds()).containsExactly(ids.get(1), ids.get(2), running);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-06T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
