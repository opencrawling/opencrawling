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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobRunRegistryTest {

    /** Starts a run that blocks until released or interrupted, and reports how it ended. */
    private static final class BlockingRun {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean sawStopFlag = new AtomicBoolean();

        void start(JobRunRegistry.Reservation reservation) {
            reservation.start(stopRequested -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                sawStopFlag.set(stopRequested.getAsBoolean());
            });
        }
    }

    private static JobRunRegistry.Reservation reserved(JobRunRegistry.ReserveResult result) {
        assertThat(result).isInstanceOf(JobRunRegistry.Reserved.class);
        return ((JobRunRegistry.Reserved) result).reservation();
    }

    @Test
    void aJobCannotRunTwice() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(0);
        BlockingRun first = new BlockingRun();
        first.start(reserved(registry.reserve("job-1", "exec-a")));
        assertThat(first.started.await(5, TimeUnit.SECONDS)).isTrue();

        JobRunRegistry.ReserveResult second = registry.reserve("job-1", "exec-b");

        assertThat(second).isInstanceOf(JobRunRegistry.AlreadyRunning.class);
        assertThat(((JobRunRegistry.AlreadyRunning) second).run().executionId()).isEqualTo("exec-a");
        first.release.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.run("job-1").isEmpty());
        assertThat(registry.reserve("job-1", "exec-c")).isInstanceOf(JobRunRegistry.Reserved.class);
    }

    @Test
    void racingStartsOfOneJobGetExactlyOneSlot() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(0);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JobRunRegistry.ReserveResult>> attempts = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 64; i++) {
                String executionId = "exec-" + i;
                attempts.add(executor.submit(() -> {
                    go.await();
                    return registry.reserve("job-1", executionId);
                }));
            }
            go.countDown();
        }
        long reservedCount = 0;
        for (Future<JobRunRegistry.ReserveResult> attempt : attempts) {
            if (attempt.get() instanceof JobRunRegistry.Reserved) {
                reservedCount++;
            }
        }
        assertThat(reservedCount).isEqualTo(1);
    }

    @Test
    void theCapOnRunsIsEnforcedAndFreedWhenARunEnds() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(2);
        BlockingRun a = new BlockingRun();
        BlockingRun b = new BlockingRun();
        a.start(reserved(registry.reserve("job-a", "exec-a")));
        b.start(reserved(registry.reserve("job-b", "exec-b")));

        JobRunRegistry.ReserveResult third = registry.reserve("job-c", "exec-c");
        assertThat(third).isInstanceOf(JobRunRegistry.LimitReached.class);
        assertThat(((JobRunRegistry.LimitReached) third).limit()).isEqualTo(2);

        a.release.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.runs().size() == 1);
        assertThat(registry.reserve("job-c", "exec-c")).isInstanceOf(JobRunRegistry.Reserved.class);
        b.release.countDown();
    }

    @Test
    void aReleasedReservationFreesItsSlotWithoutRunning() {
        JobRunRegistry registry = new JobRunRegistry(1);
        JobRunRegistry.Reservation reservation = reserved(registry.reserve("job-1", "exec-a"));
        assertThat(registry.reserve("job-2", "exec-b")).isInstanceOf(JobRunRegistry.LimitReached.class);

        reservation.release();

        assertThat(registry.runs()).isEmpty();
        assertThat(registry.reserve("job-2", "exec-b")).isInstanceOf(JobRunRegistry.Reserved.class);
    }

    @Test
    void stopSetsTheFlagAndInterruptsTheRun() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(0);
        BlockingRun run = new BlockingRun();
        run.start(reserved(registry.reserve("job-1", "exec-a")));
        assertThat(run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.stop("job-1")).isTrue();

        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.run("job-1").isEmpty());
        assertThat(run.interrupted).isTrue();
        assertThat(run.sawStopFlag).isTrue();
    }

    @Test
    void stoppingAJobWithoutARunReportsFalse() {
        assertThat(new JobRunRegistry(0).stop("job-1")).isFalse();
    }

    @Test
    void aRunThatThrowsStillFreesItsSlot() {
        JobRunRegistry registry = new JobRunRegistry(0);
        reserved(registry.reserve("job-1", "exec-a")).start(stopRequested -> {
            throw new IllegalStateException("boom");
        });

        await().atMost(5, TimeUnit.SECONDS).until(() -> registry.run("job-1").isEmpty());
    }

    @Test
    void runsAreListedWithTheirStopState() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(0);
        BlockingRun run = new BlockingRun();
        // a body that ignores interrupts, so the run stays listed after the stop request
        reserved(registry.reserve("job-1", "exec-a")).start(stopRequested -> {
            run.started.countDown();
            while (run.release.getCount() > 0) {
                Thread.onSpinWait();
            }
        });
        assertThat(run.started.await(5, TimeUnit.SECONDS)).isTrue();

        registry.stop("job-1");

        assertThat(registry.runs()).singleElement().satisfies(info -> {
            assertThat(info.jobId()).isEqualTo("job-1");
            assertThat(info.executionId()).isEqualTo("exec-a");
            assertThat(info.stopRequested()).isTrue();
        });
        run.release.countDown();
    }
}
