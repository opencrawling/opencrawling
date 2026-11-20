/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.api.JobController.SaveOutcome;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcRunStore;
import org.opencrawling.runtime.controlplane.JdbcScheduleStore;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.MisfirePolicy;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.OverlapPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit and multi-replica HA tests for {@link JobSchedulerEngine} (#197). */
class JobSchedulerEngineTest {

    @TempDir
    Path tempDir;

    private record RuntimeNode(
            ControlPlaneStore cpStore,
            ScheduleStore scheduleStore,
            JobRunRegistry registry,
            JobController controller,
            JobSchedulerEngine engine) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant next) {
            current.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    private static final class RecordingOrchestrator extends JobOrchestrator {
        final List<ExecutionProvenance> provenances = new CopyOnWriteArrayList<>();
        final AtomicReference<CountDownLatch> startedLatch = new AtomicReference<>(new CountDownLatch(0));
        final AtomicReference<CountDownLatch> releaseLatch = new AtomicReference<>();
        final AtomicBoolean failRuns = new AtomicBoolean(false);

        RecordingOrchestrator() {
            super(null, null, null);
        }

        void blockNextRun() {
            startedLatch.set(new CountDownLatch(1));
            releaseLatch.set(new CountDownLatch(1));
        }

        void releaseRun() {
            CountDownLatch l = releaseLatch.getAndSet(null);
            if (l != null) {
                l.countDown();
            }
        }

        @Override
        public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path,
                              String transformationConnector, String jobId, String jobName,
                              JobController.NarrativizationConfig narrativization, PipelineMode pipelineMode,
                              String executionId, BooleanSupplier stopRequested, ExecutionProvenance provenance) {
            provenances.add(provenance);
            startedLatch.get().countDown();
            CountDownLatch gate = releaseLatch.get();
            if (gate != null) {
                try {
                    while (!gate.await(10, TimeUnit.MILLISECONDS)) {
                        if (stopRequested.getAsBoolean()) {
                            return false;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !failRuns.get();
        }
    }

    private static ConnectorFactory mockFactory() {
        ConnectorFactory factory = mock(ConnectorFactory.class);
        when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
        when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
        return factory;
    }

    private static JobDTO scheduledJob(String id, String name, JobScheduleConfig schedule) {
        return new JobDTO(id, name, "FS", "Qdrant", "", "/tmp", "Ready", "Idle", 0, "N/A",
                null, null, "rag", null, null, schedule);
    }

    private static SchedulerProperties fastProperties() {
        SchedulerProperties props = new SchedulerProperties();
        props.setPollInterval(Duration.ofSeconds(1));
        props.setMinInterval(Duration.ofSeconds(10));
        props.setMisfireGracePeriod(Duration.ofMinutes(10));
        props.setStopPreviousWait(Duration.ofSeconds(2));
        return props;
    }

    private static JdbcTemplate freshDatabase() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sched-eng-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        return new JdbcTemplate(ds);
    }

    private RuntimeNode createNode(JdbcTemplate jdbc, String runtimeId, JobOrchestrator orchestrator,
                                   SchedulerProperties props, SimpleMeterRegistry meters, boolean crawlOnStartup,
                                   Clock clock) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcControlPlaneStore cp = new JdbcControlPlaneStore(jdbc, tx, tempDir.resolve(runtimeId));
        JdbcRunStore runStore = new JdbcRunStore(jdbc, cp);
        JdbcScheduleStore scheduleStore = new JdbcScheduleStore(jdbc, cp);
        JobRunRegistry registry = new JobRunRegistry(0, 0, runStore, runtimeId, Duration.ofSeconds(60), clock);
        JobController controller = new JobController(orchestrator, mockFactory(), registry, jdbc, cp);
        JobSchedulerEngine engine = new JobSchedulerEngine(controller, cp, scheduleStore, registry,
                props, meters, OpenTelemetry.noop(), crawlOnStartup, clock);
        return new RuntimeNode(cp, scheduleStore, registry, controller, engine);
    }

    @Test
    void hydratePreservesExistingNextFireAtAndPrunesOrphans() {
        Instant bootTime = Instant.parse("2026-11-19T10:00:00Z");
        MutableClock clock = new MutableClock(bootTime);
        JdbcTemplate jdbc = freshDatabase();
        RuntimeNode node = createNode(jdbc, "rt-1", new RecordingOrchestrator(), fastProperties(), null, false, clock);

        JobScheduleConfig cfg = new JobScheduleConfig("0 */5 * * * *", "UTC", true,
                OverlapPolicy.SKIP, MisfirePolicy.FIRE_ONCE, null, null, null, null);
        String existingId = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "Existing", cfg))).job().id();
        String newId = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "New", cfg))).job().id();
        node.scheduleStore().saveState(new JobScheduleState(existingId, true,
                Instant.parse("2026-11-19T09:50:00Z"), null, null, null, null, false, 0, bootTime));
        node.scheduleStore().saveState(new JobScheduleState("orphan-job", true,
                Instant.parse("2026-11-19T09:50:00Z"), null, null, null, null, false, 0, bootTime));
        node.scheduleStore().deleteState(newId);

        node.engine().hydrate();

        assertThat(node.scheduleStore().getState("orphan-job")).isEmpty();
        assertThat(node.scheduleStore().getState(existingId)).hasValueSatisfying(s ->
                assertThat(s.nextFireAt()).isEqualTo(Instant.parse("2026-11-19T09:50:00Z")));
        assertThat(node.scheduleStore().getState(newId)).hasValueSatisfying(s ->
                assertThat(s.nextFireAt()).isEqualTo(Instant.parse("2026-11-19T10:05:00Z")));
    }

    @Test
    void dueTickLaunchesWithSchedulerProvenanceAndRecordsMetrics() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RuntimeNode node = createNode(freshDatabase(), "rt-1", orchestrator, fastProperties(), meters, false, clock);

        String jobId = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "HourlyCrawl",
                new JobScheduleConfig("0 * * * *", "UTC", true, null, null, null, null, null, null)))).job().id();

        clock.set(Instant.parse("2026-11-19T11:00:02Z"));
        assertThat(node.engine().tick()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(orchestrator.provenances).hasSize(1);
            assertThat(orchestrator.provenances.getFirst().triggerSource())
                    .isEqualTo(ExecutionProvenance.SOURCE_SCHEDULER);
            assertThat(orchestrator.provenances.getFirst().correlationId()).startsWith("sched:" + jobId + ":");
            assertThat(orchestrator.provenances.getFirst().triggeredBy()).isNull();
            assertThat(node.scheduleStore().listDispatches(jobId)).hasSize(1);
            assertThat(node.scheduleStore().listDispatches(jobId).getFirst().outcome())
                    .isEqualTo(DispatchOutcome.STARTED);
        });
        assertThat(meters.counter("opencrawling.scheduler.dispatches.total", "outcome", "STARTED", "job_id", jobId).count())
                .isEqualTo(1.0);
    }

    @Test
    void misfirePoliciesSkipOrFireOnceAfterDowntime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        SchedulerProperties props = fastProperties();
        props.setMisfireGracePeriod(Duration.ofMinutes(5));
        RuntimeNode node = createNode(freshDatabase(), "rt-1", orchestrator, props, null, false, clock);

        String skipJob = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "MisfireSkip",
                new JobScheduleConfig("0 * * * *", "UTC", true, OverlapPolicy.SKIP, MisfirePolicy.SKIP,
                        null, null, null, null)))).job().id();
        String fireOnceJob = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "MisfireFireOnce",
                new JobScheduleConfig("0 * * * *", "UTC", true, OverlapPolicy.SKIP, MisfirePolicy.FIRE_ONCE,
                        null, null, null, null)))).job().id();

        clock.set(Instant.parse("2026-11-19T11:30:00Z"));
        node.engine().tick();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(node.scheduleStore().getState(skipJob)).hasValueSatisfying(s ->
                    assertThat(s.lastOutcome()).isEqualTo(DispatchOutcome.MISSED_SKIPPED));
            assertThat(node.scheduleStore().getState(fireOnceJob)).hasValueSatisfying(s ->
                    assertThat(s.lastOutcome()).isEqualTo(DispatchOutcome.STARTED));
        });
        assertThat(orchestrator.provenances).hasSize(1);
    }

    @Test
    void overlapPoliciesSkipCoalesceAndStopPrevious() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        RuntimeNode node = createNode(freshDatabase(), "rt-1", orchestrator, fastProperties(), null, false, clock);

        // 1. COALESCE: tick 1 runs, tick 2 queues coalescePending, completion of tick 1 launches tick 3
        String coalesceJob = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "CoalesceJob",
                new JobScheduleConfig("0 * * * * *", "UTC", true, OverlapPolicy.COALESCE, MisfirePolicy.SKIP,
                        null, null, null, null)))).job().id();

        orchestrator.blockNextRun();
        clock.set(Instant.parse("2026-11-19T10:01:01Z"));
        node.engine().tick();
        assertThat(orchestrator.startedLatch.get().await(5, TimeUnit.SECONDS)).isTrue();

        clock.set(Instant.parse("2026-11-19T10:02:01Z"));
        node.engine().tick();
        assertThat(node.scheduleStore().getState(coalesceJob)).hasValueSatisfying(s -> {
            assertThat(s.lastOutcome()).isEqualTo(DispatchOutcome.SKIPPED_OVERLAP);
            assertThat(s.coalescePending()).isTrue();
        });

        orchestrator.releaseRun();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(orchestrator.provenances).hasSize(2));
        // Disable CoalesceJob so only StopPrevJob ticks next.
        node.controller().updateScheduleDefinition(coalesceJob,
                node.controller().findJobById(coalesceJob).schedule().withEnabled(false));
        node.scheduleStore().deleteState(coalesceJob);

        // 2. STOP_PREVIOUS: interrupts active run and immediately starts replacement
        String stopJob = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "StopPrevJob",
                new JobScheduleConfig("0 * * * * *", "UTC", true, OverlapPolicy.STOP_PREVIOUS, MisfirePolicy.SKIP,
                        null, null, null, null)))).job().id();

        orchestrator.blockNextRun();
        clock.set(Instant.parse("2026-11-19T10:03:01Z"));
        node.engine().tick();
        assertThat(orchestrator.startedLatch.get().await(5, TimeUnit.SECONDS)).isTrue();

        orchestrator.releaseLatch.set(null);
        clock.set(Instant.parse("2026-11-19T10:04:01Z"));
        node.engine().tick();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(orchestrator.provenances).hasSize(4));
        assertThat(node.scheduleStore().getState(stopJob)).hasValueSatisfying(s ->
                assertThat(s.lastOutcome()).isEqualTo(DispatchOutcome.STARTED));
    }

    @Test
    void consecutiveFailureCircuitBreakerAutoPausesSchedule() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        orchestrator.failRuns.set(true);
        RuntimeNode node = createNode(freshDatabase(), "rt-1", orchestrator, fastProperties(), null, false, clock);

        String jobId = ((SaveOutcome.Saved) node.controller().save(scheduledJob(null, "FlakyJob",
                new JobScheduleConfig("0 * * * * *", "UTC", true, OverlapPolicy.SKIP, MisfirePolicy.SKIP,
                        null, null, null, 2)))).job().id();

        clock.set(Instant.parse("2026-11-19T10:01:01Z"));
        node.engine().tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(node.scheduleStore().getState(jobId)).hasValueSatisfying(s ->
                        assertThat(s.consecutiveFailures()).isEqualTo(1)));

        clock.set(Instant.parse("2026-11-19T10:02:01Z"));
        node.engine().tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JobScheduleState state = node.scheduleStore().getState(jobId).orElseThrow();
            assertThat(state.enabled()).isFalse();
            assertThat(state.lastOutcome()).isEqualTo(DispatchOutcome.AUTO_PAUSED);
            assertThat(state.consecutiveFailures()).isEqualTo(2);
            assertThat(node.controller().findJobById(jobId).schedule().enabled()).isFalse();
        });
    }

    @Test
    void crawlOnStartupDisablesEngine() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        RuntimeNode node = createNode(freshDatabase(), "rt-1", new RecordingOrchestrator(),
                fastProperties(), null, true, clock);

        assertThat(node.engine().isActiveOnThisRuntime()).isFalse();
        node.engine().hydrate();
        assertThat(node.engine().tick()).isZero();
    }

    @Test
    void twoClusterReplicasSharingH2ControlPlaneFireEachDueTickOnce() throws Exception {
        JdbcTemplate jdbc = freshDatabase();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        SchedulerProperties props = fastProperties();
        RecordingOrchestrator sharedOrchestrator = new RecordingOrchestrator();

        RuntimeNode nodeA = createNode(jdbc, "replica-A", sharedOrchestrator, props, null, false, clock);
        RuntimeNode nodeB = createNode(jdbc, "replica-B", sharedOrchestrator, props, null, false, clock);

        String jobId = ((SaveOutcome.Saved) nodeA.controller().save(scheduledJob(null, "ClusterJob",
                new JobScheduleConfig("0 * * * *", "UTC", true, OverlapPolicy.SKIP, MisfirePolicy.SKIP,
                        null, null, null, null)))).job().id();

        clock.set(Instant.parse("2026-11-19T11:00:05Z"));
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> {
                gate.await();
                return nodeA.engine().tick();
            });
            Future<Integer> b = pool.submit(() -> {
                gate.await();
                return nodeB.engine().tick();
            });
            gate.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(sharedOrchestrator.provenances).hasSize(1);
            assertThat(nodeA.scheduleStore().listDispatches(jobId)).hasSize(1);
            assertThat(nodeB.scheduleStore().listDispatches(jobId)).hasSize(1);
        });
    }
}
