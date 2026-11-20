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

import org.opencrawling.runtime.orchestrator.RunStore.RunRecord;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The job runs: at most one run per job, an optional cap on runs at the same time, a real stop and a real pause.
 *
 * <p>Starting is two steps so that a rejected start changes nothing: {@link #reserve} takes the job's slot (or
 * says why it cannot), then the caller prepares the run and either {@link Reservation#start starts} it or
 * {@link Reservation#release releases} the slot. A run ends when its body returns.
 *
 * <p>Runs are kept in a {@link RunStore} (#315): in this runtime's memory with the {@code file} control-plane store,
 * in the control-plane database with {@code jdbc}, where the rules hold across every runtime that shares it and a
 * run and its history (its {@link RunStore.RunStep steps}) outlive a restart. A run's thread is always in the runtime
 * that started it; the others act on it through the store:
 * <ul>
 *   <li>{@link #stop} sets the run's stop flag and interrupts its thread. The body receives the flag and must end soon
 *   after it becomes true; {@link JobOrchestrator} checks it before every document.</li>
 *   <li>{@link #pause} holds the run before its next document, where it checks the flag, until {@link #resume} or a
 *   stop.</li>
 *   <li>Asked in another runtime, both reach the run at its runtime's next heartbeat
 *   ({@code opencrawling.control-plane.run-heartbeat-interval}).</li>
 * </ul>
 *
 * <p>Each runtime renews the heartbeat of its runs. In a shared store, a run whose heartbeat is older than
 * {@code opencrawling.control-plane.run-heartbeat-timeout} is marked {@link RunState#INTERRUPTED} by any runtime, so
 * the job can run again; should its runtime still be running it, that runtime stops it at its next heartbeat.
 *
 * <p>A run may name the verified caller that started it. With a per-caller cap, one caller can hold at most that
 * many runs at the same time; runs without a known caller count only towards the overall cap.
 */
@Component
public class JobRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRunRegistry.class);

    /** What a run looks like from outside. {@code endedAt} is {@code null} while it is active. */
    public record RunInfo(String jobId, String executionId, Instant startedAt, boolean stopRequested, String caller,
                          RunState state, String runtimeId, Instant endedAt) {

        static RunInfo of(RunRecord run) {
            return new RunInfo(run.jobId(), run.executionId(), run.startedAt(), run.stopRequested(), run.caller(),
                    run.state(), run.runtimeId(), run.endedAt());
        }
    }

    /** The outcome of {@link #reserve}. */
    public sealed interface ReserveResult permits Reserved, AlreadyRunning, LimitReached, CallerLimitReached {}

    public record Reserved(Reservation reservation) implements ReserveResult {}

    public record AlreadyRunning(RunInfo run) implements ReserveResult {}

    public record LimitReached(int limit) implements ReserveResult {}

    /** The caller already holds as many runs as the per-caller cap allows. */
    public record CallerLimitReached(String caller, int limit) implements ReserveResult {}

    private final int maxConcurrentRuns;
    private final int maxConcurrentRunsPerCaller;
    private final RunStore store;
    private final String runtimeId;
    private final Duration heartbeatTimeout;
    private final Clock clock;
    /** The runs whose thread is in this runtime, by execution id. */
    private final Map<String, Run> local = new ConcurrentHashMap<>();
    private final List<Consumer<RunInfo>> interruptionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<RunInfo>> finishedListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public JobRunRegistry(@Value("${opencrawling.jobs.max-concurrent-runs:0}") int maxConcurrentRuns,
                          @Value("${opencrawling.jobs.max-concurrent-runs-per-caller:0}") int maxConcurrentRunsPerCaller,
                          ObjectProvider<RunStore> store,
                          @Value("${opencrawling.control-plane.runtime-id:}") String runtimeId,
                          @Value("${opencrawling.control-plane.run-heartbeat-timeout:60s}") String heartbeatTimeout) {
        this(maxConcurrentRuns, maxConcurrentRunsPerCaller, store.getIfAvailable(InMemoryRunStore::new),
                runtimeId == null || runtimeId.isBlank() ? defaultRuntimeId() : runtimeId.strip(),
                DurationStyle.detectAndParse(heartbeatTimeout),
                Clock.systemUTC());
    }

    /** A registry that keeps its runs in memory. */
    public JobRunRegistry(int maxConcurrentRuns, int maxConcurrentRunsPerCaller) {
        this(maxConcurrentRuns, maxConcurrentRunsPerCaller, Clock.systemUTC());
    }

    /** A registry with no per-caller cap. */
    public JobRunRegistry(int maxConcurrentRuns) {
        this(maxConcurrentRuns, 0, Clock.systemUTC());
    }

    JobRunRegistry(int maxConcurrentRuns, Clock clock) {
        this(maxConcurrentRuns, 0, clock);
    }

    JobRunRegistry(int maxConcurrentRuns, int maxConcurrentRunsPerCaller, Clock clock) {
        this(maxConcurrentRuns, maxConcurrentRunsPerCaller, new InMemoryRunStore(), defaultRuntimeId(),
                Duration.ofSeconds(60), clock);
    }

    public JobRunRegistry(int maxConcurrentRuns, int maxConcurrentRunsPerCaller, RunStore store, String runtimeId,
                          Duration heartbeatTimeout, Clock clock) {
        this.maxConcurrentRuns = Math.max(0, maxConcurrentRuns);
        this.maxConcurrentRunsPerCaller = Math.max(0, maxConcurrentRunsPerCaller);
        this.store = store;
        this.runtimeId = runtimeId;
        this.heartbeatTimeout = heartbeatTimeout;
        this.clock = clock;
    }

    /** The configured cap on runs at the same time; 0 means no cap. */
    public int maxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /** The configured cap on runs one caller may hold at the same time; 0 means no cap. */
    public int maxConcurrentRunsPerCaller() {
        return maxConcurrentRunsPerCaller;
    }

    /** The name this runtime's runs carry in the store. */
    public String runtimeId() {
        return runtimeId;
    }

    /**
     * Where the runs are kept: also the runs a caller asks for, such as dynamic jobs, which are stored there from their
     * request and started through {@link #reserve} under their own execution id (#316).
     */
    public RunStore store() {
        return store;
    }

    /**
     * Calls {@code listener} with each run this runtime marks {@link RunState#INTERRUPTED} because its runtime stopped
     * renewing its heartbeat.
     */
    public void onInterrupted(Consumer<RunInfo> listener) {
        interruptionListeners.add(listener);
    }

    /**
     * Calls {@code listener} after any local run has finished and released its slot in {@link RunStore}, or after a
     * stale shared run was marked {@link RunState#INTERRUPTED}.
     */
    public void onFinished(Consumer<RunInfo> listener) {
        finishedListeners.add(listener);
    }

    /** Takes the job's slot for a run with no known caller. */
    public ReserveResult reserve(String jobId, String executionId) {
        return reserve(jobId, executionId, null);
    }

    /**
     * Takes the job's slot. Fails when the job already has a run, when the cap on runs is reached, or when
     * {@code caller} already holds as many runs as the per-caller cap allows.
     *
     * @param caller the verified caller starting the run, or {@code null} when unknown (then only the overall cap
     *               applies)
     */
    public ReserveResult reserve(String jobId, String executionId, String caller) {
        interruptStale();
        return switch (store.claim(jobId, executionId, caller, runtimeId, clock.instant(),
                maxConcurrentRuns, maxConcurrentRunsPerCaller)) {
            case RunStore.Busy busy -> new AlreadyRunning(RunInfo.of(busy.run()));
            case RunStore.Full full -> new LimitReached(full.limit());
            case RunStore.CallerFull full -> new CallerLimitReached(full.caller(), full.limit());
            case RunStore.Claimed claimed -> {
                Run run = new Run(jobId, executionId);
                local.put(executionId, run);
                yield new Reserved(new Reservation(run));
            }
        };
    }

    /**
     * Asks a job's run to stop: sets its stop flag and interrupts its thread.
     *
     * @return {@code false} when the job has no run
     */
    public boolean stop(String jobId) {
        return store.active(jobId).map(run -> stopExecution(jobId, run.executionId())).orElse(false);
    }

    /**
     * Asks one run to stop: only if it is still the job's run. A run that ended, or a newer run of the same job, is
     * left alone.
     *
     * @return {@code false} when that run is not running
     */
    public boolean stopExecution(String jobId, String executionId) {
        if (!isActiveRunOf(jobId, executionId) || !store.requestStop(executionId, runtimeId, clock.instant())) {
            return false;
        }
        Run run = local.get(executionId);
        if (run != null) {
            run.requestStop();
        }
        log.info("Stop requested for job {} [Exec ID: {}].", jobId, executionId);
        return true;
    }

    /**
     * Pauses one run of a job: it holds before its next document until it is resumed or stopped.
     *
     * @return {@code false} when that run is not running
     */
    public boolean pause(String jobId, String executionId) {
        if (!isActiveRunOf(jobId, executionId) || !store.pause(executionId, runtimeId, clock.instant())) {
            return false;
        }
        Run run = local.get(executionId);
        if (run != null) {
            run.paused(true);
        }
        log.info("Pause requested for job {} [Exec ID: {}].", jobId, executionId);
        return true;
    }

    /**
     * Resumes a paused run of a job; a running run keeps running.
     *
     * @return {@code false} when that run is not running
     */
    public boolean resume(String jobId, String executionId) {
        if (!isActiveRunOf(jobId, executionId) || !store.resume(executionId, runtimeId, clock.instant())) {
            return false;
        }
        Run run = local.get(executionId);
        if (run != null) {
            run.paused(false);
        }
        log.info("Resume requested for job {} [Exec ID: {}].", jobId, executionId);
        return true;
    }

    /** The job's active run. */
    public Optional<RunInfo> run(String jobId) {
        return store.active(jobId).map(RunInfo::of);
    }

    /** The active runs, oldest first. */
    public List<RunInfo> runs() {
        return store.active().stream().map(RunInfo::of).toList();
    }

    /** A run, active or ended, while it is kept. */
    /** The runs kept, active and ended, of {@code jobId} or of every job when it is {@code null}, newest first. */
    public List<RunInfo> list(String jobId) {
        return store.list(jobId).stream().map(RunInfo::of).toList();
    }

    public Optional<RunInfo> execution(String executionId) {
        return store.get(executionId).map(RunInfo::of);
    }

    /** A run's history, in order. */
    public List<RunStore.RunStep> steps(String executionId) {
        return store.steps(executionId);
    }

    /**
     * Renews the heartbeat of this runtime's runs and applies to them what was decided elsewhere: a stop, a pause or a
     * resume asked in another runtime, or an interruption. Then interrupts the runs whose runtime stopped renewing
     * theirs.
     */
    @Scheduled(fixedDelayString = "${opencrawling.control-plane.run-heartbeat-interval:5s}")
    public void heartbeat() {
        if (!local.isEmpty()) {
            for (RunRecord stored : store.heartbeat(runtimeId, List.copyOf(local.keySet()), clock.instant())) {
                Run run = local.get(stored.executionId());
                if (run == null) {
                    continue;
                }
                if (!stored.state().active()) {
                    log.warn("Job {} run [Exec ID: {}] was marked {} elsewhere; stopping it here.",
                            stored.jobId(), stored.executionId(), stored.state());
                    run.requestStop();
                } else {
                    if (stored.stopRequested()) {
                        run.requestStop();
                    }
                    run.paused(stored.state() == RunState.PAUSED);
                }
            }
        }
        interruptStale();
    }

    /**
     * Marks this runtime's runs that have no thread here as {@link RunState#INTERRUPTED}: left by an earlier start of
     * a runtime with the same {@code opencrawling.control-plane.runtime-id}, they would otherwise block their jobs until
     * their heartbeat is too old. Called once the application is ready; the default runtime id is new at every start,
     * so it finds nothing.
     *
     * @return how many it interrupted
     */
    @EventListener(ApplicationReadyEvent.class)
    public int interruptLeftoverRuns() {
        Instant now = clock.instant();
        int interrupted = 0;
        for (RunRecord run : store.active()) {
            if (!run.runtimeId().equals(runtimeId) || local.containsKey(run.executionId())) {
                continue;
            }
            store.finish(run.executionId(), RunState.INTERRUPTED, runtimeId, now, "Runtime " + runtimeId + " restarted");
            Optional<RunRecord> ended = store.get(run.executionId()).filter(r -> r.state() == RunState.INTERRUPTED);
            if (ended.isPresent()) {
                interrupted++;
                log.warn("Job {} run [Exec ID: {}] interrupted: runtime {} restarted.", run.jobId(), run.executionId(), runtimeId);
                notifyInterrupted(ended.get());
            }
        }
        return interrupted;
    }

    private void notifyInterrupted(RunRecord run) {
        RunInfo info = RunInfo.of(run);
        for (Consumer<RunInfo> listener : interruptionListeners) {
            try {
                listener.accept(info);
            } catch (RuntimeException e) {
                log.warn("Interruption listener failed for job {}: {}", run.jobId(), e.getMessage(), e);
            }
        }
        notifyFinished(info);
    }

    private void notifyFinished(RunInfo info) {
        for (Consumer<RunInfo> listener : finishedListeners) {
            try {
                listener.accept(info);
            } catch (RuntimeException e) {
                log.warn("Finished listener failed for job {} [Exec ID: {}]: {}",
                        info.jobId(), info.executionId(), e.getMessage(), e);
            }
        }
    }

    private void interruptStale() {
        if (!store.shared() || heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            return;
        }
        Instant now = clock.instant();
        for (RunRecord run : store.interruptStale(now.minus(heartbeatTimeout), runtimeId, now)) {
            log.warn("Job {} run [Exec ID: {}] interrupted: runtime {} stopped renewing its heartbeat.",
                    run.jobId(), run.executionId(), run.runtimeId());
            notifyInterrupted(run);
        }
    }

    private boolean isActiveRunOf(String jobId, String executionId) {
        return store.get(executionId).filter(run -> run.state().active() && run.jobId().equals(jobId)).isPresent();
    }

    private void end(Run run, RunState state, String detail) {
        RunInfo endedInfo = null;
        try {
            store.finish(run.executionId, state, runtimeId, clock.instant(), detail);
            endedInfo = store.get(run.executionId).map(RunInfo::of).orElseGet(
                    () -> new RunInfo(run.jobId, run.executionId, null, run.stopRequested.get(), null,
                            state, runtimeId, clock.instant()));
        } catch (RuntimeException e) {
            log.error("Job {} run [Exec ID: {}] ended {} but could not be recorded: {}",
                    run.jobId, run.executionId, state, e.getMessage(), e);
        } finally {
            local.remove(run.executionId, run);
        }
        if (endedInfo != null) {
            notifyFinished(endedInfo);
        }
    }

    static String defaultRuntimeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "runtime";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
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
         * Runs {@code body} on a new virtual thread. The body gets the run's stop flag, which also holds the run while
         * it is paused; the run ends when the body returns ({@link RunState#COMPLETED}, or {@link RunState#STOPPED}
         * after a stop) or throws ({@link RunState#FAILED}).
         */
        public void start(Consumer<BooleanSupplier> body) {
            startJob(stopRequested -> {
                body.accept(stopRequested);
                return true;
            });
        }

        /**
         * Runs {@code body} as {@link #start} does; {@code false} from the body ends the run as
         * {@link RunState#FAILED}.
         */
        public void startJob(Predicate<BooleanSupplier> body) {
            if (!used.compareAndSet(false, true)) {
                throw new IllegalStateException("Reservation for job " + run.jobId + " already used.");
            }
            Thread thread = Thread.ofVirtual().name("oc-job-" + run.jobId).unstarted(() -> {
                RunState state = RunState.FAILED;
                String detail = null;
                try {
                    boolean succeeded = body.test(run::checkpoint);
                    state = run.stopRequested.get() ? RunState.STOPPED : succeeded ? RunState.COMPLETED : RunState.FAILED;
                } catch (RuntimeException e) {
                    log.error("Job {} run ended with an error: {}", run.jobId, e.getMessage(), e);
                    state = run.stopRequested.get() ? RunState.STOPPED : RunState.FAILED;
                    detail = e.getMessage();
                } finally {
                    // A stop interrupts this thread; clear it so recording the end is not cut short.
                    Thread.interrupted();
                    end(run, state, detail);
                }
            });
            run.attach(thread);
            thread.start();
        }

        /** Frees the slot without running anything. */
        public void release() {
            if (used.compareAndSet(false, true)) {
                try {
                    store.release(run.executionId);
                } finally {
                    local.remove(run.executionId, run);
                }
            }
        }
    }

    private static final class Run {
        private final String jobId;
        private final String executionId;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private boolean paused;
        private Thread thread;

        private Run(String jobId, String executionId) {
            this.jobId = jobId;
            this.executionId = executionId;
        }

        private synchronized void attach(Thread thread) {
            this.thread = thread;
            if (stopRequested.get()) {
                thread.interrupt();
            }
        }

        private synchronized void requestStop() {
            stopRequested.set(true);
            notifyAll();
            if (thread != null) {
                thread.interrupt();
            }
        }

        private synchronized void paused(boolean paused) {
            this.paused = paused;
            notifyAll();
        }

        /** The stop flag the body checks before each document; holds there while the run is paused. */
        private boolean checkpoint() {
            synchronized (this) {
                while (paused && !stopRequested.get()) {
                    try {
                        wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return stopRequested.get();
        }
    }
}
