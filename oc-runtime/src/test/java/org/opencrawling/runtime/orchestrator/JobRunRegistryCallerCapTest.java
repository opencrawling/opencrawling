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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The per-caller cap: one verified caller holds at most that many runs at the same time; other callers and runs with
 * no known caller are not affected by it.
 */
class JobRunRegistryCallerCapTest {

    private static JobRunRegistry.Reservation reserved(JobRunRegistry.ReserveResult result) {
        assertThat(result).isInstanceOf(JobRunRegistry.Reserved.class);
        return ((JobRunRegistry.Reserved) result).reservation();
    }

    /** Starts a run that blocks until {@code release} opens. */
    private static void hold(JobRunRegistry.Reservation reservation, CountDownLatch release) {
        reservation.start(stopRequested -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void aCallerAtItsCapIsRejectedWhileOthersAreNot() {
        JobRunRegistry registry = new JobRunRegistry(0, 1, Clock.systemUTC());
        CountDownLatch release = new CountDownLatch(1);
        hold(reserved(registry.reserve("job-1", "exec-1", "alice")), release);

        JobRunRegistry.ReserveResult again = registry.reserve("job-2", "exec-2", "alice");

        assertThat(again).isEqualTo(new JobRunRegistry.CallerLimitReached("alice", 1));
        assertThat(registry.reserve("job-3", "exec-3", "bob")).isInstanceOf(JobRunRegistry.Reserved.class);
        assertThat(registry.reserve("job-4", "exec-4", null)).isInstanceOf(JobRunRegistry.Reserved.class);
        assertThat(registry.reserve("job-5", "exec-5")).isInstanceOf(JobRunRegistry.Reserved.class);
        release.countDown();
    }

    @Test
    void aCallersSlotIsFreedWhenItsRunEnds() {
        JobRunRegistry registry = new JobRunRegistry(0, 1, Clock.systemUTC());
        CountDownLatch release = new CountDownLatch(1);
        hold(reserved(registry.reserve("job-1", "exec-1", "alice")), release);
        assertThat(registry.reserve("job-2", "exec-2", "alice")).isInstanceOf(JobRunRegistry.CallerLimitReached.class);

        release.countDown();

        await().until(() -> registry.run("job-1").isEmpty());
        assertThat(registry.reserve("job-2", "exec-2", "alice")).isInstanceOf(JobRunRegistry.Reserved.class);
    }

    @Test
    void aReleasedReservationFreesTheCallersSlot() {
        JobRunRegistry registry = new JobRunRegistry(0, 1, Clock.systemUTC());
        reserved(registry.reserve("job-1", "exec-1", "alice")).release();

        assertThat(registry.reserve("job-2", "exec-2", "alice")).isInstanceOf(JobRunRegistry.Reserved.class);
    }

    @Test
    void theOverallCapIsCheckedFirst() {
        JobRunRegistry registry = new JobRunRegistry(1, 1, Clock.systemUTC());
        CountDownLatch release = new CountDownLatch(1);
        hold(reserved(registry.reserve("job-1", "exec-1", "alice")), release);

        assertThat(registry.reserve("job-2", "exec-2", "alice")).isEqualTo(new JobRunRegistry.LimitReached(1));
        release.countDown();
    }

    @Test
    void withoutAPerCallerCapACallerMayHoldAnyNumberOfRuns() {
        JobRunRegistry registry = new JobRunRegistry(0);

        for (int i = 0; i < 10; i++) {
            assertThat(registry.reserve("job-" + i, "exec-" + i, "alice")).isInstanceOf(JobRunRegistry.Reserved.class);
        }
        assertThat(registry.maxConcurrentRunsPerCaller()).isZero();
    }

    @Test
    void theRunListingNamesEachRunsCaller() {
        JobRunRegistry registry = new JobRunRegistry(0, 2, Clock.systemUTC());
        registry.reserve("job-1", "exec-1", "alice");
        registry.reserve("job-2", "exec-2", null);

        assertThat(registry.run("job-1")).get().extracting(JobRunRegistry.RunInfo::caller).isEqualTo("alice");
        assertThat(registry.run("job-2")).get().extracting(JobRunRegistry.RunInfo::caller).isNull();
    }

    @Test
    void racingStartsByOneCallerGetExactlyTheCap() throws Exception {
        JobRunRegistry registry = new JobRunRegistry(0, 2, Clock.systemUTC());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JobRunRegistry.ReserveResult>> attempts = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 64; i++) {
                String jobId = "job-" + i;
                attempts.add(executor.submit(() -> {
                    go.await();
                    return registry.reserve(jobId, "exec-" + jobId, "alice");
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
        assertThat(reservedCount).isEqualTo(2);
    }
}
