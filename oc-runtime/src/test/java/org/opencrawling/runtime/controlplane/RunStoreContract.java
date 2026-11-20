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
package org.opencrawling.runtime.controlplane;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.orchestrator.RunStore;
import org.opencrawling.runtime.orchestrator.RunStore.Busy;
import org.opencrawling.runtime.orchestrator.RunStore.CallerFull;
import org.opencrawling.runtime.orchestrator.RunStore.Claim;
import org.opencrawling.runtime.orchestrator.RunStore.Claimed;
import org.opencrawling.runtime.orchestrator.RunStore.Full;
import org.opencrawling.runtime.orchestrator.RunStore.RunEvent;
import org.opencrawling.runtime.orchestrator.RunStore.RunKind;
import org.opencrawling.runtime.orchestrator.RunStore.RunRecord;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.opencrawling.runtime.orchestrator.RunStore.RunStep;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every {@link RunStore} does (#315, part 3). The shared stores also run the tests with two runtimes: two store
 * instances, as two runtimes have, on one database.
 */
abstract class RunStoreContract {

    static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");

    RunStore a;
    RunStore b;

    /** A new, empty store. */
    abstract RunStore freshStore();

    /** Another runtime's store on the same database as {@link #a}; {@code null} when the store is not shared. */
    abstract RunStore otherRuntime();

    /** {@link #a} as a restarted runtime sees it: a new instance on the same database. */
    abstract RunStore restarted();

    @BeforeEach
    void stores() {
        a = freshStore();
        b = otherRuntime();
    }

    @Test
    void aJobHasOneActiveRunAndTheCapsHold() {
        assertThat(a.claim("job-1", "exec-1", "alice", "rt-a", T0, 3, 2)).isInstanceOf(Claimed.class);
        assertThat(a.claim("job-1", "exec-2", "bob", "rt-a", T0, 3, 2))
                .isInstanceOfSatisfying(Busy.class, busy -> assertThat(busy.run().executionId()).isEqualTo("exec-1"));
        assertThat(a.claim("job-2", "exec-3", "alice", "rt-a", T0, 3, 2)).isInstanceOf(Claimed.class);
        assertThat(a.claim("job-3", "exec-4", "alice", "rt-a", T0, 3, 2)).isEqualTo(new CallerFull("alice", 2));
        assertThat(a.claim("job-3", "exec-5", null, "rt-a", T0, 3, 2)).isInstanceOf(Claimed.class);
        assertThat(a.claim("job-4", "exec-6", null, "rt-a", T0, 3, 2)).isEqualTo(new Full(3));

        a.release("exec-5");
        assertThat(a.get("exec-5")).isEmpty();
        assertThat(a.steps("exec-5")).isEmpty();
        a.finish("exec-1", RunState.COMPLETED, "rt-a", T0.plusSeconds(5), null);
        assertThat(a.active()).extracting(RunRecord::executionId).containsExactly("exec-3");
        assertThat(a.claim("job-1", "exec-7", "bob", "rt-a", T0.plusSeconds(6), 3, 2)).isInstanceOf(Claimed.class);
    }

    @Test
    void theRunsKeptAreListedNewestFirstEndedOnesIncluded() {
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        a.finish("exec-1", RunState.COMPLETED, "rt-a", T0.plusSeconds(5), null);
        a.claim("job-2", "exec-2", null, "rt-a", T0.plusSeconds(10), 0, 0);
        a.claim("job-1", "exec-3", null, "rt-a", T0.plusSeconds(20), 0, 0);

        assertThat(a.list(null)).extracting(RunRecord::executionId).containsExactly("exec-3", "exec-2", "exec-1");
        assertThat(a.list("job-1")).extracting(RunRecord::executionId).containsExactly("exec-3", "exec-1");
        assertThat(a.list("job-1").get(1).state()).isEqualTo(RunState.COMPLETED);
        assertThat(a.list("job-9")).isEmpty();
    }

    @Test
    void everyChangeOfStateIsRecordedInOrder() {
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        assertThat(a.pause("exec-1", "rt-b", T0.plusSeconds(1))).isTrue();
        assertThat(a.pause("exec-1", "rt-b", T0.plusSeconds(2))).as("a paused run stays paused").isTrue();
        assertThat(a.active("job-1")).get().extracting(RunRecord::state).isEqualTo(RunState.PAUSED);
        assertThat(a.resume("exec-1", "rt-a", T0.plusSeconds(3))).isTrue();
        assertThat(a.requestStop("exec-1", "rt-b", T0.plusSeconds(4))).isTrue();
        assertThat(a.requestStop("exec-1", "rt-b", T0.plusSeconds(5))).isTrue();
        a.finish("exec-1", RunState.STOPPED, "rt-a", T0.plusSeconds(6), null);
        a.finish("exec-1", RunState.FAILED, "rt-a", T0.plusSeconds(7), "too late");

        RunRecord run = a.get("exec-1").orElseThrow();
        assertThat(run.state()).isEqualTo(RunState.STOPPED);
        assertThat(run.stopRequested()).isTrue();
        assertThat(run.endedAt()).isEqualTo(T0.plusSeconds(6));
        List<RunStep> steps = a.steps("exec-1");
        assertThat(steps).extracting(RunStep::event).containsExactly(
                RunEvent.STARTED, RunEvent.PAUSED, RunEvent.RESUMED, RunEvent.STOP_REQUESTED, RunEvent.STOPPED);
        assertThat(steps).extracting(RunStep::seq).containsExactly(1, 2, 3, 4, 5);
        assertThat(steps.get(1).runtimeId()).isEqualTo("rt-b");
        assertThat(steps.get(1).at()).isEqualTo(T0.plusSeconds(1));

        assertThat(a.pause("exec-1", "rt-a", T0.plusSeconds(8))).as("an ended run cannot be paused").isFalse();
        assertThat(a.resume("exec-1", "rt-a", T0.plusSeconds(8))).isFalse();
        assertThat(a.requestStop("exec-1", "rt-a", T0.plusSeconds(8))).isFalse();
        assertThat(a.pause("exec-unknown", "rt-a", T0)).isFalse();
    }

    @Test
    void aHeartbeatRenewsOnlyItsRuntimesActiveRunsAndShowsWhatChanged() {
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        a.claim("job-2", "exec-2", null, "rt-b", T0, 0, 0);
        a.claim("job-3", "exec-3", null, "rt-a", T0, 0, 0);
        a.pause("exec-1", "rt-b", T0.plusSeconds(1));
        a.finish("exec-3", RunState.INTERRUPTED, "rt-b", T0.plusSeconds(1), "gone");

        List<RunRecord> seen = a.heartbeat("rt-a", List.of("exec-1", "exec-2", "exec-3", "exec-unknown"), T0.plusSeconds(10));

        assertThat(seen).extracting(RunRecord::executionId).containsExactlyInAnyOrder("exec-1", "exec-2", "exec-3");
        RunRecord one = seen.stream().filter(r -> r.executionId().equals("exec-1")).findFirst().orElseThrow();
        assertThat(one.state()).isEqualTo(RunState.PAUSED);
        assertThat(one.heartbeatAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(a.get("exec-2").orElseThrow().heartbeatAt()).as("another runtime's run").isEqualTo(T0);
        assertThat(a.get("exec-3").orElseThrow().state()).isEqualTo(RunState.INTERRUPTED);
    }

    @Test
    void runsWhoseHeartbeatIsTooOldAreInterrupted() {
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        a.claim("job-2", "exec-2", null, "rt-a", T0, 0, 0);
        a.heartbeat("rt-a", List.of("exec-2"), T0.plusSeconds(50));

        List<RunRecord> interrupted = a.interruptStale(T0.plusSeconds(30), "rt-b", T0.plusSeconds(61));

        assertThat(interrupted).singleElement().satisfies(run -> {
            assertThat(run.executionId()).isEqualTo("exec-1");
            assertThat(run.state()).isEqualTo(RunState.INTERRUPTED);
            assertThat(run.endedAt()).isEqualTo(T0.plusSeconds(61));
        });
        assertThat(a.steps("exec-1")).last().satisfies(step -> {
            assertThat(step.event()).isEqualTo(RunEvent.INTERRUPTED);
            assertThat(step.runtimeId()).isEqualTo("rt-b");
            assertThat(step.detail()).contains("rt-a");
        });
        assertThat(a.active()).extracting(RunRecord::executionId).containsExactly("exec-2");
        assertThat(a.claim("job-1", "exec-3", null, "rt-b", T0.plusSeconds(62), 0, 0))
                .as("the job can run again").isInstanceOf(Claimed.class);
        assertThat(a.interruptStale(T0.plusSeconds(30), "rt-b", T0.plusSeconds(63))).isEmpty();
    }

    @Test
    void endedRunsOlderThanTheCutoffAreDeletedWithTheirHistory() {
        a.claim("job-1", "exec-old", null, "rt-a", T0, 0, 0);
        a.finish("exec-old", RunState.COMPLETED, "rt-a", T0.plusSeconds(10), null);
        a.claim("job-1", "exec-new", null, "rt-a", T0.plusSeconds(20), 0, 0);
        a.finish("exec-new", RunState.FAILED, "rt-a", T0.plusSeconds(100), "boom");
        a.claim("job-2", "exec-active", null, "rt-a", T0, 0, 0);

        assertThat(a.deleteEndedBefore(T0.plusSeconds(50))).isEqualTo(1);

        assertThat(a.get("exec-old")).isEmpty();
        assertThat(a.steps("exec-old")).isEmpty();
        assertThat(a.get("exec-new")).isPresent();
        assertThat(a.steps("exec-new")).last().extracting(RunStep::detail).isEqualTo("boom");
        assertThat(a.get("exec-active")).isPresent();
    }

    @Test
    void runsAndTheirHistoryAreReadAfterARestart() {
        assumeTrue(b != null, "kept in memory: a restart loses them");
        a.claim("job-1", "exec-1", "alice", "rt-a", T0, 0, 0);
        a.pause("exec-1", "rt-a", T0.plusSeconds(1));
        a.claim("job-2", "exec-2", null, "rt-a", T0, 0, 0);
        a.finish("exec-2", RunState.COMPLETED, "rt-a", T0.plusSeconds(2), null);

        RunStore after = restarted();

        RunRecord paused = after.get("exec-1").orElseThrow();
        assertThat(paused.state()).isEqualTo(RunState.PAUSED);
        assertThat(paused.caller()).isEqualTo("alice");
        assertThat(paused.startedAt()).isEqualTo(T0);
        assertThat(after.steps("exec-2")).extracting(RunStep::event).containsExactly(RunEvent.STARTED, RunEvent.COMPLETED);
    }

    @Test
    void twoRuntimesStartingTheSameJobAtOnceGetOneRun() throws Exception {
        assumeTrue(b != null, "not shared");
        for (int round = 0; round < 5; round++) {
            String job = "job-" + round;
            List<Callable<Claim>> claims = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                RunStore store = i % 2 == 0 ? a : b;
                String executionId = "exec-" + round + "-" + i;
                claims.add(() -> store.claim(job, executionId, null, store == a ? "rt-a" : "rt-b", T0, 0, 0));
            }
            assertThat(runAtOnce(claims)).filteredOn(Claimed.class::isInstance).hasSize(1);
            assertThat(b.active(job)).isPresent();
        }
    }

    @Test
    void theCapHoldsAcrossRuntimes() throws Exception {
        assumeTrue(b != null, "not shared");
        List<Callable<Claim>> claims = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RunStore store = i % 2 == 0 ? a : b;
            int n = i;
            claims.add(() -> store.claim("job-" + n, "exec-" + n, "alice", "rt-" + (n % 2), T0, 4, 3));
        }
        assertThat(runAtOnce(claims)).filteredOn(Claimed.class::isInstance).hasSize(3);
        assertThat(a.active()).hasSize(3);
    }

    @Test
    void aChangeMadeByAnotherRuntimeIsSeenAtOnce() {
        assumeTrue(b != null, "not shared");
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        assertThat(b.pause("exec-1", "rt-b", T0.plusSeconds(1))).isTrue();
        assertThat(b.requestStop("exec-1", "rt-b", T0.plusSeconds(2))).isTrue();

        RunRecord seen = a.heartbeat("rt-a", List.of("exec-1"), T0.plusSeconds(3)).getFirst();
        assertThat(seen.state()).isEqualTo(RunState.PAUSED);
        assertThat(seen.stopRequested()).isTrue();
        assertThat(a.steps("exec-1")).extracting(RunStep::runtimeId).containsExactly("rt-a", "rt-b", "rt-b");
    }

    @Test
    void historiesWrittenAtTheSameTimeByTwoRuntimesHaveNoGaps() throws Exception {
        assumeTrue(b != null, "not shared");
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);
        List<Callable<Boolean>> changes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RunStore store = i % 2 == 0 ? a : b;
            boolean pause = i % 4 < 2;
            Instant at = T0.plus(i, ChronoUnit.MILLIS);
            changes.add(() -> pause ? store.pause("exec-1", "rt", at) : store.resume("exec-1", "rt", at));
        }
        runAtOnce(changes);
        List<RunStep> steps = a.steps("exec-1");
        for (int i = 0; i < steps.size(); i++) {
            assertThat(steps.get(i).seq()).isEqualTo(i + 1);
        }
        // Every recorded change was a real one: PAUSED and RESUMED alternate after STARTED.
        for (int i = 1; i < steps.size(); i++) {
            assertThat(steps.get(i).event()).isEqualTo(i % 2 == 1 ? RunEvent.PAUSED : RunEvent.RESUMED);
        }
    }

    // ---- Runs a caller asks for (#316, part 2) ----

    static RunRecord asked(String executionId, String jobId, RunState state, String owner, Instant at) {
        return RunRecord.waiting(executionId, jobId, null, state, RunKind.DYNAMIC_FOLDER, owner, at, "{\"n\":\"" + jobId + "\"}");
    }

    @Test
    void aRunAskedForWaitsWithoutASlotAndStartsInPlace() {
        a.request(asked("exec-q", "dyn-1", RunState.QUEUED, "t/alice", T0), "rt-a", null);
        a.request(asked("exec-p", "dyn-2", RunState.PENDING_APPROVAL, "t/alice", T0), "rt-a", "needs approval");

        assertThat(a.active()).as("waiting runs hold no slot").isEmpty();
        assertThat(a.claim("job-x", "exec-x", null, "rt-a", T0, 1, 0)).as("nor count towards the cap").isInstanceOf(Claimed.class);
        a.finish("exec-x", RunState.COMPLETED, "rt-a", T0, null);

        assertThat(a.claim("dyn-1", "exec-q", "alice", "rt-b", T0.plusSeconds(1), 0, 0))
                .isInstanceOfSatisfying(Claimed.class, claimed -> {
                    assertThat(claimed.run().state()).isEqualTo(RunState.RUNNING);
                    assertThat(claimed.run().kind()).as("the same run, started in place").isEqualTo(RunKind.DYNAMIC_FOLDER);
                    assertThat(claimed.run().owner()).isEqualTo("t/alice");
                    assertThat(claimed.run().createdAt()).isEqualTo(T0);
                    assertThat(claimed.run().startedAt()).isEqualTo(T0.plusSeconds(1));
                    assertThat(claimed.run().body()).isEqualTo("{\"n\":\"dyn-1\"}");
                });
        assertThat(a.steps("exec-q")).extracting(RunStep::event).containsExactly(RunEvent.QUEUED, RunEvent.STARTED);
        assertThat(a.steps("exec-p")).singleElement().satisfies(step -> {
            assertThat(step.event()).isEqualTo(RunEvent.APPROVAL_REQUESTED);
            assertThat(step.detail()).isEqualTo("needs approval");
        });
        assertThat(a.list(null)).extracting(RunRecord::executionId).as("newest first, by the time asked for")
                .containsExactly("exec-p", "exec-q", "exec-x");
    }

    @Test
    void aReleasedClaimPutsAWaitingRunBackAndForgetsANewOne() {
        a.request(asked("exec-q", "dyn-1", RunState.QUEUED, "t/alice", T0), "rt-a", null);
        a.claim("dyn-1", "exec-q", null, "rt-a", T0, 0, 0);
        a.claim("job-1", "exec-1", null, "rt-a", T0, 0, 0);

        a.release("exec-q");
        a.release("exec-1");

        assertThat(a.get("exec-1")).as("a new run is forgotten").isEmpty();
        assertThat(a.get("exec-q")).hasValueSatisfying(run -> {
            assertThat(run.state()).isEqualTo(RunState.QUEUED);
            assertThat(run.runtimeId()).isNull();
            assertThat(run.startedAt()).isNull();
        });
        assertThat(a.steps("exec-q")).extracting(RunStep::event).as("without the start it never made")
                .containsExactly(RunEvent.QUEUED);
    }

    @Test
    void aWaitingRunEndsWithoutStartingAndAnApprovalIsRecorded() {
        a.request(asked("exec-p", "dyn-1", RunState.PENDING_APPROVAL, "t/alice", T0), "rt-a", null);
        a.record("exec-p", RunEvent.APPROVED, "rt-b", T0.plusSeconds(1), "carol");
        a.request(asked("exec-r", "dyn-2", RunState.PENDING_APPROVAL, "t/alice", T0), "rt-a", null);

        assertThat(a.settle("exec-r", RunState.REJECTED, "rt-b", T0.plusSeconds(2), "Rejected by carol.")).isTrue();
        assertThat(a.settle("exec-r", RunState.EXPIRED, "rt-b", T0.plusSeconds(3), null)).as("only once").isFalse();
        assertThat(a.get("exec-r")).hasValueSatisfying(run -> {
            assertThat(run.state()).isEqualTo(RunState.REJECTED);
            assertThat(run.endedAt()).isEqualTo(T0.plusSeconds(2));
        });
        assertThat(a.steps("exec-p")).extracting(RunStep::event, RunStep::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(RunEvent.APPROVAL_REQUESTED, null),
                        org.assertj.core.groups.Tuple.tuple(RunEvent.APPROVED, "carol"));
        a.claim("dyn-1", "exec-p", null, "rt-a", T0, 0, 0);
        assertThat(a.settle("exec-p", RunState.REJECTED, "rt-a", T0, null)).as("not once it started").isFalse();
        a.finish("exec-r", RunState.COMPLETED, "rt-a", T0, null);
        assertThat(a.get("exec-r").orElseThrow().state()).as("finish leaves a run that is not active alone")
                .isEqualTo(RunState.REJECTED);
    }

    @Test
    void theRunsAskedForAreFoundByOwnerCountedAndForgottenOldestFirst() {
        a.claim("job-1", "exec-job", null, "rt-a", T0, 0, 0);
        for (int i = 1; i <= 4; i++) {
            a.request(asked("exec-" + i, "dyn-" + i, i == 4 ? RunState.PENDING_APPROVAL : RunState.QUEUED,
                    i == 2 ? "t/bob" : "t/alice", T0), "rt-a", null);
        }
        a.updateBody("exec-1", "{\"n\":\"changed\"}");

        assertThat(a.requested("t/alice")).extracting(RunRecord::jobId).as("asked at the same time: stored order")
                .containsExactly("dyn-1", "dyn-3", "dyn-4");
        assertThat(a.requested(null)).hasSize(4).noneMatch(run -> run.kind() == RunKind.JOB);
        assertThat(a.get("exec-1").orElseThrow().body()).isEqualTo("{\"n\":\"changed\"}");
        assertThat(a.countRequested("t/alice", java.util.Set.of(RunState.QUEUED))).isEqualTo(2);
        assertThat(a.countRequested("t/alice", java.util.Set.of(RunState.QUEUED, RunState.PENDING_APPROVAL))).isEqualTo(3);
        assertThat(a.requestedBefore(RunState.PENDING_APPROVAL, T0.plusSeconds(1))).extracting(RunRecord::jobId)
                .containsExactly("dyn-4");
        assertThat(a.requestedBefore(RunState.PENDING_APPROVAL, T0)).isEmpty();

        a.settle("exec-1", RunState.STOPPED, "rt-a", T0.plusSeconds(10), null);
        a.settle("exec-2", RunState.STOPPED, "rt-a", T0.plusSeconds(20), null);
        a.settle("exec-3", RunState.STOPPED, "rt-a", T0.plusSeconds(30), null);
        assertThat(a.forgetEndedRequested(T0.plusSeconds(15), 1)).as("ended before the cutoff, then the oldest beyond 1")
                .containsExactlyInAnyOrder("dyn-1", "dyn-2");
        assertThat(a.requestedJobIds()).containsExactly("dyn-3", "dyn-4");
        assertThat(a.get("exec-job")).as("job runs are not touched").isPresent();
        assertThat(a.steps("exec-1")).isEmpty();

        a.delete("exec-4");
        assertThat(a.requestedJobIds()).containsExactly("dyn-3");
    }

    @Test
    void theRunsAskedForAreListedNewestFirst() {
        a.request(asked("exec-1", "dyn-1", RunState.QUEUED, "t/alice", T0), "rt-a", null);
        a.request(asked("exec-2", "dyn-2", RunState.QUEUED, "t/bob", T0.plusSeconds(2)), "rt-a", null);
        a.request(asked("exec-3", "dyn-3", RunState.QUEUED, "t/alice", T0.plusSeconds(1)), "rt-a", null);

        assertThat(a.requested(null)).extracting(RunRecord::jobId).containsExactly("dyn-2", "dyn-3", "dyn-1");
        assertThat(a.requested("t/alice")).extracting(RunRecord::jobId).containsExactly("dyn-3", "dyn-1");
        assertThat(a.requestedJobIds()).as("in the order they were stored in").containsExactly("dyn-1", "dyn-2", "dyn-3");
    }

    @Test
    void aRunAskedForOnOneRuntimeIsStartedAndSeenOnAnother() {
        assumeTrue(b != null, "only shared stores have two runtimes");
        a.request(asked("exec-p", "dyn-1", RunState.PENDING_APPROVAL, "t/alice", T0), "rt-a", null);

        assertThat(b.requested("t/alice")).extracting(RunRecord::state).containsExactly(RunState.PENDING_APPROVAL);
        assertThat(b.claim("dyn-1", "exec-p", "alice", "rt-b", T0, 0, 0)).isInstanceOf(Claimed.class);
        assertThat(a.get("exec-p").orElseThrow().runtimeId()).isEqualTo("rt-b");
        assertThat(restarted().steps("exec-p")).extracting(RunStep::event)
                .containsExactly(RunEvent.APPROVAL_REQUESTED, RunEvent.STARTED);
    }

    private static <T> List<T> runAtOnce(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
