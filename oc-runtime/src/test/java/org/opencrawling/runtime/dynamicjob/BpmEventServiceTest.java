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
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BpmEventServiceTest {

    final List<FakeDiscoverableConnector> built = Collections.synchronizedList(new ArrayList<>());
    final DynamicJobServiceTest.MutableClock clock = new DynamicJobServiceTest.MutableClock();
    final DynamicJobServiceTest jobsTest = new DynamicJobServiceTest();
    final DynamicJobServiceTest.Runner runner = jobsTest.new Runner();
    final DynamicJobProperties jobProperties = new DynamicJobProperties();
    final BpmEventProperties properties = new BpmEventProperties();
    DynamicJobService jobs;
    BpmEventService service;

    static BpmEventProperties.Route route(String name, String engine, String process, String transition, String connector,
                                          DynamicJobService.DocumentRequest.Action action) {
        BpmEventProperties.Route route = new BpmEventProperties.Route();
        route.setName(name);
        route.setEngine(engine);
        route.setProcess(process);
        route.setTransition(transition);
        route.setConnector(connector);
        route.setAction(action);
        return route;
    }

    static BpmEventService.Event event(String id, String process, String transition, String document) {
        return new BpmEventService.Event(id, "flowable", process, "4711", transition, document);
    }

    @BeforeEach
    void setUp() {
        // Agents may use no connector at all: events go by their routes, not by this list.
        jobProperties.setConnectors(List.of());
        jobProperties.setAdminGroups(List.of("oc-admins"));
        jobs = new DynamicJobService(() -> List.of(DynamicJobServiceTest.FAKE, DynamicJobServiceTest.UNLISTED,
                DynamicJobServiceTest.FILES), dto -> {
            FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
            built.add(connector);
            return connector;
        }, new JobRunRegistry(0), runner, executionId -> 1L, jobProperties, clock);
        properties.setRoutes(new ArrayList<>(List.of(
                route("signed", "flowable", "contract", "signed", "Fake_Repo", null),
                route("any-archive", null, "*", "archived", "Other_Repo", DynamicJobService.DocumentRequest.Action.UPSERT),
                route("purged", null, "contract", "purged", "Fake_Repo", DynamicJobService.DocumentRequest.Action.DELETE),
                route("broken", null, "contract", "broken", "Nowhere", DynamicJobService.DocumentRequest.Action.UPSERT),
                route("files", null, "contract", "files", "Files", DynamicJobService.DocumentRequest.Action.UPSERT))));
        properties.getRoutes().get(0).setAction(DynamicJobService.DocumentRequest.Action.UPSERT);
        service = new BpmEventService(jobs, properties, clock);
    }

    @AfterEach
    void release() {
        runner.hold.countDown();
    }

    DynamicJobService.JobStatus awaitEnd(String jobId) {
        Caller admin = new Caller("root", List.of("oc-admins"), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("oc:jobs:write")));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            DynamicJobService.JobStatus status = jobs.status(admin, jobId);
            if (status.state() != DynamicJobService.State.RUNNING) {
                return status;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Job " + jobId + " did not end");
    }

    static HttpStatus statusOf(Runnable call) {
        DynamicJobException e = catchThrowableOfType(DynamicJobException.class, call::run);
        assertThat(e).as("expected a DynamicJobException").isNotNull();
        return e.status();
    }

    @Test
    void aMatchingRouteIngestsTheEventsDocumentWhateverItsAcl() {
        BpmEventService.EventResult result = service.fromSource("flowable", event("e-1", "contract", "signed", "finance-report"));

        assertThat(result.status()).isEqualTo(BpmEventService.ACCEPTED);
        assertThat(result.route()).isEqualTo("signed");
        assertThat(result.connector()).isEqualTo("Fake_Repo");
        assertThat(result.jobId()).startsWith("dyn-");
        assertThat(result.duplicate()).isFalse();
        DynamicJobService.JobStatus status = awaitEnd(result.jobId());
        assertThat(status.kind()).isEqualTo(DynamicJobService.Kind.DOCUMENT);
        assertThat(status.owner()).isEqualTo("bpm:flowable");
        assertThat(status.triggerSource()).isEqualTo(ExecutionProvenance.SOURCE_BPM);
        assertThat(runner.starts).containsExactly("finance-report");
        // The route decides; the sender's own access is not checked, and the document keeps its ACL.
        assertThat(built.get(0).lastFilter).isSameAs(VisibilityFilter.ALL);
        ExecutionProvenance provenance = runner.provenances.get(0);
        assertThat(provenance.triggeredBy()).isEqualTo("bpm:flowable");
        assertThat(provenance.correlationId()).isEqualTo("e-1");
        assertThat(provenance.callerVerified()).isTrue();
    }

    @Test
    void bpmEventsAreNeverHeldForApproval() {
        jobProperties.setRequireApproval(true);

        BpmEventService.EventResult result = service.fromSource("flowable", event("e-a", "contract", "signed", "public-guide"));

        assertThat(awaitEnd(result.jobId()).state()).isEqualTo(DynamicJobService.State.COMPLETED);
    }

    @Test
    void routesMatchEngineProcessAndTransitionInOrder() {
        assertThat(service.fromSource("aps", new BpmEventService.Event("e-2", "aps", "contract", null, "signed", "finance-report")).route())
                .as("engine flowable only").isNull();
        assertThat(service.fromSource("aps", new BpmEventService.Event("e-2b", null, "contract", null, "signed", "finance-report")).route())
                .as("no engine named: an engine-specific route does not match").isNull();
        assertThat(service.fromSource("aps", new BpmEventService.Event("e-3", "APS", "claims", null, "archived", "public-guide")).route())
                .isEqualTo("any-archive");
        assertThat(service.fromSource("aps", new BpmEventService.Event("e-4", "FLOWABLE", "contract", null, "signed", "public-guide")).route())
                .as("engine names compare without case").isEqualTo("signed");
    }

    @Test
    void anEventNoRouteMatchesIsIgnoredAndRemembered() {
        BpmEventService.EventResult ignored = service.fromSource("flowable", event("e-5", "contract", "drafted", "finance-report"));

        assertThat(ignored.status()).isEqualTo(BpmEventService.IGNORED);
        assertThat(ignored.note()).isEqualTo(BpmEventService.NO_ROUTE);
        assertThat(service.fromSource("flowable", event("e-5", "contract", "drafted", "finance-report")).duplicate()).isTrue();
        assertThat(built).isEmpty();
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void anEventIdIsActedOnOncePerSender() {
        BpmEventService.EventResult first = service.fromSource("flowable", event("e-6", "contract", "signed", "public-guide"));
        BpmEventService.EventResult again = service.fromSource("flowable", event("e-6", "contract", "signed", "public-guide"));
        BpmEventService.EventResult otherSender = service.fromSource("aps", event("e-6", "contract", "signed", "public-guide"));

        assertThat(again.duplicate()).isTrue();
        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(otherSender.duplicate()).isFalse();
        awaitEnd(first.jobId());
        awaitEnd(otherSender.jobId());
        assertThat(runner.starts).hasSize(2);
    }

    @Test
    void anEventThatFailedIsNotRememberedSoItCanBeRetried() {
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-7", "contract", "signed", "nope"))))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-7", "contract", "signed", "nope"))))
                .as("not a duplicate: tried again").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(service.fromSource("flowable", event("e-7", "contract", "signed", "public-guide")).status())
                .isEqualTo(BpmEventService.ACCEPTED);
    }

    @Test
    void aDeleteRouteRemovesOnlyDocumentsTheRepositoryNoLongerHas() {
        BpmEventService.EventResult removed = service.fromSource("flowable", event("e-8", "contract", "purged", "deleted-x"));

        assertThat(removed.status()).isEqualTo(BpmEventService.ACCEPTED);
        assertThat(removed.note()).isEqualTo(DynamicJobService.DELETION_NOTE);
        assertThat(runner.starts).containsExactly("fake://deleted-x");
        assertThat(DynamicJobServiceTest.emitted(runner.connectors.get(0))).singleElement()
                .satisfies(tombstone -> assertThat(tombstone.action()).isEqualTo(DocumentAction.DELETE));
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-9", "contract", "purged", "finance-report"))))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aRouteToAnUnknownOrUnfitConnectorIsAConfigurationError() {
        DynamicJobException broken = catchThrowableOfType(DynamicJobException.class,
                () -> service.fromSource("flowable", event("e-10", "contract", "broken", "public-guide")));
        DynamicJobException files = catchThrowableOfType(DynamicJobException.class,
                () -> service.fromSource("flowable", event("e-11", "contract", "files", "public-guide")));

        assertThat(broken.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(broken.getMessage()).isEqualTo("Route 'broken' is not configured correctly.");
        assertThat(files.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(runner.starts).isEmpty();
    }

    @Test
    void aTokenCallerNeedsTheEventScope() {
        Caller engine = new Caller("svc-flowable", List.of(), "acme", true, Map.of(JwtCallerMapper.SCOPES, List.of("oc:events:write")));
        Caller noScope = new Caller("svc-flowable", List.of(), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("oc:jobs:run")));

        assertThat(statusOf(() -> service.fromCaller(null, event("e-12", "contract", "signed", "public-guide")))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> service.fromCaller(Caller.asserted("svc", List.of()), event("e-12", "contract", "signed", "public-guide"))))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> service.fromCaller(noScope, event("e-12", "contract", "signed", "public-guide")))).isEqualTo(HttpStatus.FORBIDDEN);
        BpmEventService.EventResult result = service.fromCaller(engine, event("e-12", "contract", "signed", "public-guide"));
        assertThat(result.status()).isEqualTo(BpmEventService.ACCEPTED);
        awaitEnd(result.jobId());
        assertThat(runner.provenances.get(0).triggeredBy()).isEqualTo("svc-flowable");
        assertThat(runner.provenances.get(0).callerTenant()).isEqualTo("acme");

        properties.setRequiredScope(" ");
        assertThat(statusOf(() -> service.fromCaller(engine, event("e-13", "contract", "signed", "public-guide"))))
                .as("no scope configured: tokens are not accepted").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsAreChecked() {
        assertThat(statusOf(() -> service.fromSource("flowable", null))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("bad id!", "contract", "signed", "x")))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-14", null, "signed", "x")))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-14", "contract", " ", "x")))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-14", "contract", "signed", null)))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(() -> service.fromSource("flowable", event("e-14", "c".repeat(201), "signed", "x")))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handledEventsAreForgottenAfterTheRetention() {
        properties.setDedupRetention(Duration.ofHours(1));
        service.fromSource("flowable", event("e-15", "contract", "drafted", "x"));

        clock.advance(Duration.ofMinutes(59));
        assertThat(service.fromSource("flowable", event("e-15", "contract", "drafted", "x")).duplicate()).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.fromSource("flowable", event("e-15", "contract", "drafted", "x")).duplicate()).isFalse();
    }

    @Test
    void oneSendersEventsMayRunOnlySoManyJobsAtOnce() {
        runner.hold = new CountDownLatch(1);
        properties.setMaxRunning(1);
        service.fromSource("flowable", event("e-16", "contract", "signed", "public-guide"));

        assertThat(statusOf(() -> service.fromSource("flowable", event("e-17", "contract", "signed", "public-guide"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(service.fromSource("aps", event("e-17", "contract", "signed", "public-guide")).status())
                .isEqualTo(BpmEventService.ACCEPTED);
    }
}
