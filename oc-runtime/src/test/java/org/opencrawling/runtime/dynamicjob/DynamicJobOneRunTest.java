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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.orchestrator.RunStore.RunEvent;
import org.opencrawling.runtime.orchestrator.RunStore.RunStep;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FAKE;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FILES;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.UNLISTED;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.caller;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.request;

/**
 * A dynamic job is one run from the moment it is asked for to its end (#316, part 2): one row in {@code oc_run}, under
 * the execution id it gets when it is filed, whose history tells its whole life. The dynamic-job API shows the same
 * states as before.
 */
class DynamicJobOneRunTest {

    final DynamicJobServiceTest fakes = new DynamicJobServiceTest();
    final JdbcTemplate jdbc = DynamicJobServiceJdbcTest.database();
    JobRunRegistry registry;
    DynamicJobService service;

    @BeforeEach
    void setUp() {
        fakes.properties.setConnectors(List.of("fake_repo", "Files"));
        fakes.properties.setAdminGroups(List.of("oc-admins"));
        registry = DynamicJobServiceJdbcTest.runtimeRegistry(jdbc, 0, "runtime-a", Clock.systemUTC());
        service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES), dto -> fakes.builder.apply(dto), registry,
                fakes.runner, fakes.documentCounts::get, fakes.properties, fakes.clock,
                DynamicJobServiceJdbcTest.runtimeStore(jdbc, registry, fakes.clock));
    }

    @AfterEach
    void release() {
        fakes.runner.hold.countDown();
    }

    @Test
    void anApprovedJobIsOneRunFromItsRequestToItsEnd() {
        fakes.properties.setRequireApproval(true);
        DynamicJobService.Accepted filed = service.start(caller("alice"), request(null), null, "mcp", null);

        Map<String, Object> waiting = run(filed.executionId());
        assertThat(waiting).containsEntry("STATE", "PENDING_APPROVAL").containsEntry("KIND", "DYNAMIC_FOLDER")
                .containsEntry("JOB_ID", filed.jobId()).containsEntry("OWNER", "/alice");
        assertThat(waiting.get("RUNTIME_ID")).as("it has not started anywhere").isNull();

        service.approve(caller("carol", "oc-admins"), filed.jobId());
        awaitEnd(filed.jobId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_run WHERE job_id = ?", Integer.class, filed.jobId()))
                .as("one record, never a second one for its run").isEqualTo(1);
        assertThat(run(filed.executionId())).containsEntry("STATE", "COMPLETED").containsEntry("RUNTIME_ID", "runtime-a");
        List<RunStep> history = service.history(caller("alice"), filed.jobId());
        assertThat(history).extracting(RunStep::event)
                .containsExactly(RunEvent.APPROVAL_REQUESTED, RunEvent.APPROVED, RunEvent.STARTED, RunEvent.COMPLETED);
        assertThat(history.get(1).detail()).as("who approved it").isEqualTo("carol");
    }

    @Test
    void aJobThatNeverRanEndsAsItsRunWithTheReasonInItsHistory() {
        fakes.properties.setRequireApproval(true);
        String rejected = service.start(caller("alice"), request(null), null, "mcp", null).jobId();
        String cancelled = service.start(caller("alice"), request(null), null, "mcp", null).jobId();

        service.reject(caller("carol", "oc-admins"), rejected, "out of scope");
        service.stop(caller("alice"), cancelled);

        assertThat(service.status(caller("alice"), rejected).state()).isEqualTo(State.REJECTED);
        assertThat(registry.execution(service.status(caller("alice"), rejected).executionId()).orElseThrow().state())
                .as("the run's own state").hasToString("REJECTED");
        assertThat(service.history(caller("alice"), rejected)).extracting(RunStep::event, RunStep::detail)
                .containsExactly(tuple(RunEvent.APPROVAL_REQUESTED, null),
                        tuple(RunEvent.REJECTED, "Rejected by carol: out of scope"));
        assertThat(service.history(caller("alice"), cancelled)).extracting(RunStep::event)
                .containsExactly(RunEvent.APPROVAL_REQUESTED, RunEvent.STOPPED);
        assertThat(registry.runs()).as("neither ever held a slot").isEmpty();
    }

    @Test
    void aJobIsQueuedThenStartedInPlaceAndItsRunKeepsTheJobsOwnOutcome() {
        fakes.runner.succeed = false;
        DynamicJobService.Accepted started = service.start(caller("alice"), request(null), null, "api", null);
        assertThat(started.state()).as("the dynamic-job API keeps its states").isEqualTo(State.RUNNING);

        assertThat(awaitEnd(started.jobId()).state()).isEqualTo(State.FAILED);
        assertThat(registry.execution(started.executionId()).orElseThrow().state())
                .as("the run says FAILED too: there is one state").hasToString("FAILED");
        assertThat(service.history(caller("alice"), started.jobId())).extracting(RunStep::event)
                .containsExactly(RunEvent.QUEUED, RunEvent.STARTED, RunEvent.FAILED);
    }

    @Test
    void aRefusedStartLeavesNoRun() {
        registry = DynamicJobServiceJdbcTest.runtimeRegistry(jdbc, 1, "runtime-a", Clock.systemUTC());
        service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES), dto -> fakes.builder.apply(dto), registry,
                fakes.runner, fakes.documentCounts::get, fakes.properties, fakes.clock,
                DynamicJobServiceJdbcTest.runtimeStore(jdbc, registry, fakes.clock));
        fakes.runner.hold = new java.util.concurrent.CountDownLatch(1);
        service.start(caller("alice"), request(null), null, "api", null);

        assertThat(DynamicJobServiceTest.statusOf(() -> service.start(caller("bob"), request(null), null, "api", null)))
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_run WHERE owner = '/bob'", Integer.class))
                .as("the queued run is removed with the refusal").isZero();
    }

    /** The run's columns, named in upper case. */
    @Test
    void aQueuedJobCountsAsRunningForItsOwnersLimit() {
        DynamicJobStore store = DynamicJobServiceJdbcTest.runtimeStore(jdbc, registry, fakes.clock);
        store.save(DynamicJobService.Entry.of("dyn-queued", "exec-queued", "Queued", "Fake_Repo",
                DynamicJobService.Kind.FOLDER, new DynamicJobService.StartView("root", "root", "/"), "/alice", "alice",
                "api", fakes.clock.instant(), null, State.RUNNING, null));

        assertThat(registry.execution("exec-queued").orElseThrow().state()).as("filed, not started").hasToString("QUEUED");
        assertThat(store.get("dyn-queued").orElseThrow().state()).as("shown as running").isEqualTo(State.RUNNING);
        assertThat(store.count("/alice", State.RUNNING, State.PAUSED))
                .as("it holds one of alice's running jobs while it waits for its slot").isEqualTo(1);
        assertThat(store.count("/alice", State.PENDING_APPROVAL)).isZero();
    }

    private Map<String, Object> run(String executionId) {
        Map<String, Object> row = new java.util.HashMap<>();
        jdbc.queryForMap("SELECT job_id, state, kind, owner, runtime_id FROM oc_run WHERE execution_id = ?", executionId)
                .forEach((column, value) -> row.put(column.toUpperCase(java.util.Locale.ROOT), value));
        return row;
    }

    private DynamicJobService.JobStatus awaitEnd(String jobId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            DynamicJobService.JobStatus status = service.status(caller("alice"), jobId);
            if (!status.state().active() && status.state() != State.PENDING_APPROVAL && registry.runs().isEmpty()) {
                return status;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Job " + jobId + " did not end");
    }
}
