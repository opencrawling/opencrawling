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
package org.opencrawling.runtime.orchestrator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcRunStore;
import org.opencrawling.runtime.orchestrator.RunStore.RunEvent;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.opencrawling.runtime.orchestrator.RunStore.RunStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two runtimes, A and B, whose registries share one control-plane database (#315, part 3): the rules hold across
 * them, and a stop, pause or resume asked on B reaches A's run at A's next heartbeat.
 */
class JobRunRegistrySharedStoreTest {

    /** A clock both runtimes read, moved by the test. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-17T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
    private JdbcTemplate jdbc;
    private JobRunRegistry a;
    private JobRunRegistry b;

    @BeforeEach
    void runtimes() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:registry-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        a = runtime("rt-a");
        b = runtime("rt-b");
    }

    private JobRunRegistry runtime(String id) {
        JdbcControlPlaneStore store = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), Path.of("target", "no-data"));
        return new JobRunRegistry(2, 0, new JdbcRunStore(jdbc, store), id, Duration.ofSeconds(60), clock);
    }

    /** Starts a run on {@code registry} that counts its documents until it is stopped. */
    private String countingRun(JobRunRegistry registry, String jobId, AtomicInteger documents) {
        JobRunRegistry.Reservation reservation =
                ((JobRunRegistry.Reserved) registry.reserve(jobId, "exec-" + UUID.randomUUID().toString().substring(0, 8))).reservation();
        reservation.start(stop -> count(stop, documents));
        return reservation.executionId();
    }

    private static void count(BooleanSupplier stop, AtomicInteger documents) {
        while (!stop.getAsBoolean()) {
            documents.incrementAndGet();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private RunState state(String executionId) {
        return a.execution(executionId).orElseThrow().state();
    }

    @Test
    void aJobHasOneRunAcrossRuntimesAndBothSeeIt() {
        String executionId = countingRun(a, "job-1", new AtomicInteger());

        JobRunRegistry.ReserveResult second = b.reserve("job-1", "exec-b");
        assertThat(second).isInstanceOfSatisfying(JobRunRegistry.AlreadyRunning.class, running -> {
            assertThat(running.run().executionId()).isEqualTo(executionId);
            assertThat(running.run().runtimeId()).isEqualTo("rt-a");
        });
        assertThat(b.runs()).extracting(JobRunRegistry.RunInfo::executionId).containsExactly(executionId);

        countingRun(b, "job-2", new AtomicInteger());
        assertThat(a.reserve("job-3", "exec-c")).as("the cap of 2 counts both runtimes' runs")
                .isEqualTo(new JobRunRegistry.LimitReached(2));
        a.stop("job-1");
        b.stop("job-2");
    }

    @Test
    void aStopAskedOnAnotherRuntimeEndsTheRunAtItsRuntimesNextHeartbeat() {
        AtomicInteger documents = new AtomicInteger();
        String executionId = countingRun(a, "job-1", documents);
        await().atMost(5, TimeUnit.SECONDS).until(() -> documents.get() > 0);

        assertThat(b.stopExecution("job-1", executionId)).isTrue();
        assertThat(b.run("job-1")).get().extracting(JobRunRegistry.RunInfo::stopRequested).isEqualTo(true);
        assertThat(state(executionId)).as("A has not had its heartbeat yet").isEqualTo(RunState.RUNNING);

        a.heartbeat();

        await().atMost(5, TimeUnit.SECONDS).until(() -> state(executionId) == RunState.STOPPED);
        assertThat(b.steps(executionId)).extracting(RunStep::event)
                .containsExactly(RunEvent.STARTED, RunEvent.STOP_REQUESTED, RunEvent.STOPPED);
        assertThat(b.reserve("job-1", "exec-next")).isInstanceOf(JobRunRegistry.Reserved.class);
    }

    @Test
    void aPauseAskedOnAnotherRuntimeHoldsTheRunUntilItIsResumed() throws Exception {
        AtomicInteger documents = new AtomicInteger();
        String executionId = countingRun(a, "job-1", documents);
        await().atMost(5, TimeUnit.SECONDS).until(() -> documents.get() > 0);

        assertThat(b.pause("job-1", executionId)).isTrue();
        a.heartbeat();
        Thread.sleep(50);
        int held = documents.get();
        Thread.sleep(200);
        assertThat(documents.get()).as("no document while paused").isEqualTo(held);
        assertThat(a.run("job-1")).get().extracting(JobRunRegistry.RunInfo::state).isEqualTo(RunState.PAUSED);

        assertThat(b.resume("job-1", executionId)).isTrue();
        a.heartbeat();
        await().atMost(5, TimeUnit.SECONDS).until(() -> documents.get() > held);

        b.pause("job-1", executionId);
        a.heartbeat();
        assertThat(b.stopExecution("job-1", executionId)).as("a paused run can be stopped").isTrue();
        a.heartbeat();
        await().atMost(5, TimeUnit.SECONDS).until(() -> state(executionId) == RunState.STOPPED);
    }

    @Test
    void theRunOfARuntimeThatStoppedRenewingItsHeartbeatIsInterruptedAndTheJobRunsElsewhere() {
        List<JobRunRegistry.RunInfo> interrupted = new CopyOnWriteArrayList<>();
        b.onInterrupted(interrupted::add);
        AtomicInteger documents = new AtomicInteger();
        String executionId = countingRun(a, "job-1", documents);

        clock.advance(Duration.ofSeconds(30));
        b.heartbeat();
        assertThat(b.reserve("job-1", "exec-b1")).as("30 s without a heartbeat is not yet too long")
                .isInstanceOf(JobRunRegistry.AlreadyRunning.class);

        clock.advance(Duration.ofSeconds(31));
        JobRunRegistry.ReserveResult again = b.reserve("job-1", "exec-b2");

        assertThat(again).isInstanceOf(JobRunRegistry.Reserved.class);
        assertThat(interrupted).singleElement().satisfies(run -> {
            assertThat(run.executionId()).isEqualTo(executionId);
            assertThat(run.state()).isEqualTo(RunState.INTERRUPTED);
        });
        assertThat(b.steps(executionId)).last().satisfies(step -> {
            assertThat(step.event()).isEqualTo(RunEvent.INTERRUPTED);
            assertThat(step.runtimeId()).isEqualTo("rt-b");
        });

        // A was only slow: at its next heartbeat it learns its run was interrupted, and stops it.
        a.heartbeat();
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            int before = documents.get();
            Thread.sleep(100);
            return documents.get() == before;
        });
        assertThat(state(executionId)).as("its end does not overwrite the interruption").isEqualTo(RunState.INTERRUPTED);
        assertThat(a.steps(executionId)).extracting(RunStep::event).containsExactly(RunEvent.STARTED, RunEvent.INTERRUPTED);
        ((JobRunRegistry.Reserved) again).reservation().release();
    }

    @Test
    void aRunThatKeepsItsHeartbeatIsNeverInterrupted() {
        String executionId = countingRun(a, "job-1", new AtomicInteger());
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(40));
            a.heartbeat();
            b.heartbeat();
        }
        assertThat(state(executionId)).isEqualTo(RunState.RUNNING);
        a.stop("job-1");
    }

    @Test
    void aRuntimeRestartedWithTheSameIdInterruptsTheRunsItLeftAtOnce() {
        a.reserve("job-1", "exec-left"); // its thread died with the process
        String other = countingRun(b, "job-2", new AtomicInteger());
        JobRunRegistry restartedA = runtime("rt-a");
        List<JobRunRegistry.RunInfo> interrupted = new CopyOnWriteArrayList<>();
        restartedA.onInterrupted(interrupted::add);

        assertThat(restartedA.interruptLeftoverRuns()).isEqualTo(1);

        assertThat(interrupted).extracting(JobRunRegistry.RunInfo::executionId).containsExactly("exec-left");
        assertThat(restartedA.steps("exec-left")).last().extracting(RunStep::detail).isEqualTo("Runtime rt-a restarted");
        assertThat(restartedA.reserve("job-1", "exec-again")).isInstanceOf(JobRunRegistry.Reserved.class);
        assertThat(state(other)).as("another runtime's run").isEqualTo(RunState.RUNNING);
        assertThat(b.interruptLeftoverRuns()).as("B's run has its thread in B").isZero();
        b.stop("job-2");
    }
}
