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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The job runs of this runtime: at most one run per job, an optional cap on runs at the same time, and a real stop.
 *
 * <p>Starting is two steps so that a rejected start changes nothing: {@link #reserve} takes the job's slot (or
 * says why it cannot), then the caller prepares the run and either {@link Reservation#start starts} it or
 * {@link Reservation#release releases} the slot. A run leaves the registry when its body returns.
 *
 * <p>{@link #stop} sets the run's stop flag and interrupts its thread. The body receives the flag and must end
 * soon after it becomes true; {@link JobOrchestrator} checks it before every document.
 *
 * <p>Runs live in memory, in the process that started them. A job whose saved status says "Running" after a
 * restart has no run here and can be started again.
 */
@Component
public class JobRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRunRegistry.class);

    /** What a running job looks like from outside. */
    public record RunInfo(String jobId, String executionId, Instant startedAt, boolean stopRequested) {}

    /** The outcome of {@link #reserve}. */
    public sealed interface ReserveResult permits Reserved, AlreadyRunning, LimitReached {}

    public record Reserved(Reservation reservation) implements ReserveResult {}

    public record AlreadyRunning(RunInfo run) implements ReserveResult {}

    public record LimitReached(int limit) implements ReserveResult {}

    private final int maxConcurrentRuns;
    private final Clock clock;
    private final Map<String, Run> runs = new LinkedHashMap<>();

    @Autowired
    public JobRunRegistry(@Value("${opencrawling.jobs.max-concurrent-runs:0}") int maxConcurrentRuns) {
        this(maxConcurrentRuns, Clock.systemUTC());
    }

    JobRunRegistry(int maxConcurrentRuns, Clock clock) {
        this.maxConcurrentRuns = Math.max(0, maxConcurrentRuns);
        this.clock = clock;
    }

    /** The configured cap on runs at the same time; 0 means no cap. */
    public int maxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /**
     * Takes the job's slot. Fails when the job already has a run, or when the cap on runs is reached.
     */
    public synchronized ReserveResult reserve(String jobId, String executionId) {
        Run existing = runs.get(jobId);
        if (existing != null) {
            return new AlreadyRunning(existing.info());
        }
        if (maxConcurrentRuns > 0 && runs.size() >= maxConcurrentRuns) {
            return new LimitReached(maxConcurrentRuns);
        }
        Run run = new Run(jobId, executionId, clock.instant());
        runs.put(jobId, run);
        return new Reserved(new Reservation(run));
    }

    /**
     * Asks a running job to stop: sets its stop flag and interrupts its thread.
     *
     * @return {@code false} when the job has no run here
     */
    public boolean stop(String jobId) {
        Run run;
        synchronized (this) {
            run = runs.get(jobId);
        }
        if (run == null) {
            return false;
        }
        run.requestStop();
        log.info("Stop requested for job {} [Exec ID: {}].", jobId, run.executionId);
        return true;
    }

    public synchronized Optional<RunInfo> run(String jobId) {
        Run run = runs.get(jobId);
        return run == null ? Optional.empty() : Optional.of(run.info());
    }

    public synchronized List<RunInfo> runs() {
        return runs.values().stream().map(Run::info).sorted(Comparator.comparing(RunInfo::startedAt)).toList();
    }

    private synchronized void remove(Run run) {
        runs.remove(run.jobId, run);
    }

    /** A job's slot, taken but not started yet. */
    public final class Reservation {
        private final Run run;
        private final AtomicBoolean used = new AtomicBoolean();

        private Reservation(Run run) {
            this.run = run;
        }

        public String executionId() {
            return run.executionId;
        }

        /**
         * Runs {@code body} on a new virtual thread. The body gets the run's stop flag; the slot is freed when it
         * returns or throws.
         */
        public void start(Consumer<BooleanSupplier> body) {
            if (!used.compareAndSet(false, true)) {
                throw new IllegalStateException("Reservation for job " + run.jobId + " already used.");
            }
            Thread thread = Thread.ofVirtual().name("oc-job-" + run.jobId).unstarted(() -> {
                try {
                    body.accept(run.stopRequested::get);
                } catch (RuntimeException e) {
                    log.error("Job {} run ended with an error: {}", run.jobId, e.getMessage(), e);
                } finally {
                    remove(run);
                }
            });
            run.attach(thread);
            thread.start();
        }

        /** Frees the slot without running anything. */
        public void release() {
            if (used.compareAndSet(false, true)) {
                remove(run);
            }
        }
    }

    private static final class Run {
        private final String jobId;
        private final String executionId;
        private final Instant startedAt;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private Thread thread;

        private Run(String jobId, String executionId, Instant startedAt) {
            this.jobId = jobId;
            this.executionId = executionId;
            this.startedAt = startedAt;
        }

        private synchronized void attach(Thread thread) {
            this.thread = thread;
            if (stopRequested.get()) {
                thread.interrupt();
            }
        }

        private synchronized void requestStop() {
            stopRequested.set(true);
            if (thread != null) {
                thread.interrupt();
            }
        }

        private RunInfo info() {
            return new RunInfo(jobId, executionId, startedAt, stopRequested.get());
        }
    }
}
