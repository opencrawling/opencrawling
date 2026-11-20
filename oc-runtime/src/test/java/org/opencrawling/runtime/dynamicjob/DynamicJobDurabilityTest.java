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
package org.opencrawling.runtime.dynamicjob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcRunStore;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FAKE;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FILES;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.UNLISTED;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.caller;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.request;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.statusOf;

/**
 * Dynamic jobs kept in the control-plane database (#316): what survives a restart, and two runtimes (each with its own
 * stores and run registry, sharing only the database) that see, approve, pause, stop and interrupt each other's jobs.
 */
class DynamicJobDurabilityTest {

    /** The fake connectors, runner and clock of the service tests, shared by both runtimes. */
    final DynamicJobServiceTest fakes = new DynamicJobServiceTest();
    final JdbcTemplate jdbc = DynamicJobServiceJdbcTest.database();

    /** One runtime: its run registry and its dynamic-job service, on the shared database. */
    record Runtime(JobRunRegistry registry, DynamicJobService service) {}

    Runtime runtime(String runtimeId) {
        JdbcControlPlaneStore controlPlane = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), Path.of("target", "no-data"));
        JobRunRegistry registry = new JobRunRegistry(0, 0, new JdbcRunStore(jdbc, controlPlane), runtimeId,
                Duration.ofSeconds(60), fakes.clock);
        DynamicJobService service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES), dto -> fakes.builder.apply(dto),
                registry, fakes.runner, fakes.documentCounts::get, fakes.properties, fakes.clock,
                new JdbcDynamicJobStore(jdbc, controlPlane));
        return new Runtime(registry, service);
    }

    @BeforeEach
    void setUp() {
        fakes.properties.setConnectors(List.of("fake_repo", "Files"));
        fakes.properties.setAdminGroups(List.of("oc-admins"));
    }

    @AfterEach
    void release() {
        fakes.runner.hold.countDown();
    }

    @Test
    void aRestartedRuntimeKeepsTheJobsTheirKeysAndAPendingApprovalWhichItCanStart() {
        fakes.properties.setRequireApproval(true);
        Runtime before = runtime("runtime-a");
        DynamicJobService.Accepted filed = before.service().start(caller("alice"), request(null), "nightly", "mcp", "corr-1");
        assertThat(filed.state()).isEqualTo(State.PENDING_APPROVAL);

        Runtime after = runtime("runtime-a"); // the same runtime, restarted: new objects, the same database
        assertThat(after.service().status(caller("alice"), filed.jobId()).state()).isEqualTo(State.PENDING_APPROVAL);
        DynamicJobService.Accepted retry = after.service().start(caller("alice"), request(null), "nightly", "mcp", "corr-1");
        assertThat(retry.replayed()).as("the key still answers after the restart").isTrue();
        assertThat(retry.jobId()).isEqualTo(filed.jobId());
        assertThat(statusOf(() -> after.service().start(caller("alice"), new DynamicJobService.JobRequest("Fake_Repo", null, "Another job"), "nightly", "mcp", null)))
                .as("and still refuses another request").isEqualTo(HttpStatus.CONFLICT);

        DynamicJobService.JobStatus approved = after.service().approve(caller("carol", "oc-admins"), filed.jobId());
        assertThat(approved.approvedBy()).isEqualTo("carol");
        assertThat(awaitEnd(after.service(), filed.jobId()).state()).isEqualTo(State.COMPLETED);
        assertThat(fakes.runner.provenances).as("it ran for alice, the identity kept with the pending request")
                .anySatisfy(provenance -> assertThat(provenance.triggeredBy()).isEqualTo("alice"));
        assertThat(jdbc.queryForObject("SELECT body FROM " + JdbcDynamicJobStore.JOBS + " WHERE job_id = ?", String.class,
                filed.jobId())).as("the requester's identity is dropped once it is approved").doesNotContain("scopes");
    }

    @Test
    void aJobFiledOnOneRuntimeIsSeenApprovedAndCountedOnAnother() {
        fakes.properties.setRequireApproval(true);
        fakes.properties.setMaxPendingPerUser(1);
        Runtime a = runtime("runtime-a");
        Runtime b = runtime("runtime-b");
        String jobId = a.service().start(caller("alice"), request(null), null, "mcp", null).jobId();

        assertThat(b.service().list(caller("alice"))).extracting(DynamicJobService.JobStatus::jobId).containsExactly(jobId);
        assertThat(statusOf(() -> b.service().start(caller("alice"), request(null), null, "mcp", null)))
                .as("alice's pending limit counts the jobs filed on every runtime").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(statusOf(() -> b.service().status(caller("mallory"), jobId))).isEqualTo(HttpStatus.NOT_FOUND);

        b.service().approve(caller("carol", "oc-admins"), jobId);
        assertThat(statusOf(() -> a.service().approve(caller("dave", "oc-admins"), jobId)))
                .as("approved once, wherever").isEqualTo(HttpStatus.CONFLICT);
        assertThat(awaitEnd(a.service(), jobId).state()).isEqualTo(State.COMPLETED);
    }

    @Test
    void aPauseAndAStopAskedOnAnotherRuntimeReachTheRun() {
        fakes.runner.hold = new CountDownLatch(1);
        Runtime a = runtime("runtime-a");
        Runtime b = runtime("runtime-b");
        String jobId = a.service().start(caller("alice"), request(null), null, "api", null).jobId();

        assertThat(b.service().pause(caller("alice"), jobId).state()).isEqualTo(State.PAUSED);
        assertThat(a.service().status(caller("alice"), jobId).state()).isEqualTo(State.PAUSED);
        assertThat(a.registry().execution(a.service().status(caller("alice"), jobId).executionId()).orElseThrow().state())
                .as("the run itself is paused").hasToString("PAUSED");
        assertThat(b.service().resume(caller("alice"), jobId).state()).isEqualTo(State.RUNNING);

        assertThat(b.service().stop(caller("alice"), jobId).stopRequested()).isTrue();
        a.registry().heartbeat(); // A picks the stop up at its next heartbeat and ends the run
        assertThat(awaitEnd(b.service(), jobId).state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aJobWhoseRuntimeStoppedIsInterruptedAndItsRuntimeDoesNotOverwriteThat() {
        fakes.runner.hold = new CountDownLatch(1);
        Runtime a = runtime("runtime-a");
        Runtime b = runtime("runtime-b");
        String jobId = a.service().start(caller("alice"), request(null), null, "api", null).jobId();

        fakes.clock.advance(Duration.ofMinutes(2)); // A renews no heartbeat for longer than the timeout
        b.registry().heartbeat();
        DynamicJobService.JobStatus interrupted = b.service().status(caller("alice"), jobId);
        assertThat(interrupted.state()).isEqualTo(State.INTERRUPTED);
        assertThat(interrupted.note()).contains("Interrupted");

        fakes.runner.hold.countDown(); // A was only slow: its crawl now ends
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!a.registry().runs().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(a.service().status(caller("alice"), jobId).state()).as("the interruption stands").isEqualTo(State.INTERRUPTED);
        assertThat(b.service().start(caller("alice"), request(null), null, "api", null).state())
                .as("alice may start again").isEqualTo(State.RUNNING);
    }

    @Test
    void onlyARunningJobCanBePausedAndOnlyByThoseWhoSeeIt() {
        fakes.runner.hold = new CountDownLatch(1);
        Runtime a = runtime("runtime-a");
        String jobId = a.service().start(caller("alice"), request(null), null, "api", null).jobId();

        assertThat(statusOf(() -> a.service().pause(caller("mallory"), jobId))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(a.service().pause(caller("carol", "oc-admins"), jobId).state()).as("an admin may").isEqualTo(State.PAUSED);
        assertThat(a.service().stop(caller("alice"), jobId).stopRequested()).as("a paused job can be stopped").isTrue();
        assertThat(awaitEnd(a.service(), jobId).state()).isEqualTo(State.STOPPED);
        assertThat(statusOf(() -> a.service().resume(caller("alice"), jobId))).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theStoreFollowsTheControlPlaneStore() {
        JdbcControlPlaneStore controlPlane = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), Path.of("target", "no-data"));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<JdbcTemplate> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(jdbc);
        assertThat(DynamicJobConfiguration.store(controlPlane, provider)).isInstanceOf(JdbcDynamicJobStore.class);
        assertThat(DynamicJobConfiguration.store(org.opencrawling.runtime.controlplane.ControlPlaneStore.files(), provider))
                .isInstanceOf(InMemoryDynamicJobStore.class);
    }

    private DynamicJobService.JobStatus awaitEnd(DynamicJobService service, String jobId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            DynamicJobService.JobStatus status = service.status(caller("alice"), jobId);
            if (!status.state().active() && status.state() != State.PENDING_APPROVAL) {
                return status;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Job " + jobId + " did not end");
    }
}
