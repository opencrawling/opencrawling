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
package org.opencrawling.runtime.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.api.JobController.StartOutcome;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.MisfirePolicy;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.OverlapPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Built-in cluster-safe cron job scheduler (#197).
 *
 * <p>Polls {@link ScheduleStore} for due jobs at {@code opencrawling.scheduler.poll-interval}, takes an atomic
 * cluster claim under {@link ControlPlaneStore#locked} ({@code oc_lock} on {@code jdbc}), advances {@code nextFireAt}
 * before starting the crawl, enforces misfire and overlap policies alongside {@link JobRunRegistry}, and handles
 * consecutive-failure circuit-breaking.
 */
@Service
public class JobSchedulerEngine {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerEngine.class);

    private final JobController jobController;
    private final ControlPlaneStore controlPlaneStore;
    private final ScheduleStore scheduleStore;
    private final JobRunRegistry runRegistry;
    private final SchedulerProperties properties;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final boolean crawlOnStartup;
    private final Clock clock;

    private final AtomicInteger activeGauge = new AtomicInteger();
    private final AtomicInteger pausedGauge = new AtomicInteger();

    private record ClaimedTick(JobDTO job, Instant scheduledFor, boolean valid) {
        static ClaimedTick none() {
            return new ClaimedTick(null, null, false);
        }
    }

    @Autowired
    public JobSchedulerEngine(
            JobController jobController,
            ControlPlaneStore controlPlaneStore,
            ScheduleStore scheduleStore,
            JobRunRegistry runRegistry,
            SchedulerProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<OpenTelemetry> openTelemetry,
            @Value("${spring.opencrawling.crawl-on-startup:false}") boolean crawlOnStartup) {
        this(jobController, controlPlaneStore, scheduleStore, runRegistry, properties,
                meterRegistry.getIfAvailable(), openTelemetry.getIfAvailable(), crawlOnStartup, Clock.systemUTC());
    }

    public JobSchedulerEngine(
            JobController jobController,
            ControlPlaneStore controlPlaneStore,
            ScheduleStore scheduleStore,
            JobRunRegistry runRegistry,
            SchedulerProperties properties,
            MeterRegistry meterRegistry,
            OpenTelemetry openTelemetry,
            boolean crawlOnStartup,
            Clock clock) {
        this.jobController = jobController;
        this.controlPlaneStore = controlPlaneStore != null ? controlPlaneStore : ControlPlaneStore.files();
        this.scheduleStore = scheduleStore;
        this.runRegistry = runRegistry;
        this.properties = properties != null ? properties : new SchedulerProperties();
        this.meterRegistry = meterRegistry;
        this.tracer = openTelemetry != null ? openTelemetry.getTracer("org.opencrawling.runtime.scheduler") : null;
        this.crawlOnStartup = crawlOnStartup;
        this.clock = clock != null ? clock : Clock.systemUTC();

        this.jobController.setScheduleStore(this.scheduleStore, this.properties, this.clock);
        this.runRegistry.onFinished(this::onRunFinished);

        if (this.meterRegistry != null) {
            Gauge.builder("opencrawling.scheduler.jobs.active", activeGauge, AtomicInteger::get)
                    .description("Number of jobs with enabled schedules")
                    .register(this.meterRegistry);
            Gauge.builder("opencrawling.scheduler.jobs.paused", pausedGauge, AtomicInteger::get)
                    .description("Number of jobs with paused schedules")
                    .register(this.meterRegistry);
        }
    }

    /** Whether background scheduled ticks are active on this runtime replica. */
    public boolean isActiveOnThisRuntime() {
        return properties.isEnabled() && !crawlOnStartup;
    }

    /** Hydrates missing schedule state rows on startup without overwriting existing {@code nextFireAt} timestamps. */
    @EventListener(ApplicationReadyEvent.class)
    public void hydrate() {
        if (crawlOnStartup) {
            log.info("Native job scheduler is disabled in standalone crawl-on-startup mode.");
            return;
        }
        Instant now = clock.instant();
        controlPlaneStore.locked(() -> {
            List<JobDTO> jobs = jobController.allJobsUnfiltered();
            Set<String> validScheduledJobIds = new HashSet<>();
            Map<String, JobScheduleState> existingStates = scheduleStore.getAllStates();
            for (JobDTO job : jobs) {
                JobScheduleConfig config = job.schedule();
                if (config == null) {
                    continue;
                }
                validScheduledJobIds.add(job.id());
                if (!existingStates.containsKey(job.id())) {
                    Instant nextFireAt = CronScheduleParser.computeNextFireAt(
                            config, properties.getDefaultTimezone(), now).orElse(null);
                    scheduleStore.saveState(new JobScheduleState(
                            job.id(),
                            config.enabled() && nextFireAt != null,
                            nextFireAt,
                            null,
                            null,
                            null,
                            null,
                            false,
                            0,
                            now));
                }
            }
            for (String storedJobId : existingStates.keySet()) {
                if (!validScheduledJobIds.contains(storedJobId)) {
                    scheduleStore.deleteState(storedJobId);
                }
            }
            return null;
        });
        refreshGauges();
    }

    @Scheduled(
            initialDelayString = "${opencrawling.scheduler.poll-interval:5s}",
            fixedDelayString = "${opencrawling.scheduler.poll-interval:5s}")
    public void scheduledPoll() {
        if (!isActiveOnThisRuntime()) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Scheduler poll tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Evaluates and claims any schedules due at or before {@link Clock#instant()}, returning how many dispatches
     * were claimed (started, skipped, rejected, or misfired).
     */
    public int tick() {
        if (!isActiveOnThisRuntime()) {
            return 0;
        }
        Instant now = clock.instant();
        List<JobScheduleState> dueCandidates = scheduleStore.findDue(now, properties.getMaxBatchPerTick());
        int processed = 0;
        for (JobScheduleState candidate : dueCandidates) {
            ClaimedTick claimed = claimDueTick(candidate.jobId(), now);
            if (claimed.valid()) {
                processed++;
                executeClaimedTick(claimed.job(), claimed.scheduledFor(), now);
            }
        }
        refreshGauges();
        return processed;
    }

    private ClaimedTick claimDueTick(String jobId, Instant now) {
        return controlPlaneStore.locked(() -> {
            JobDTO job = jobController.findJobById(jobId);
            if (job == null || job.schedule() == null) {
                scheduleStore.deleteState(jobId);
                return ClaimedTick.none();
            }
            JobScheduleConfig config = job.schedule();
            JobScheduleState state = scheduleStore.getState(jobId).orElse(null);
            if (state == null || !state.enabled() || state.nextFireAt() == null || state.nextFireAt().isAfter(now)) {
                return ClaimedTick.none();
            }
            if (!config.enabled()) {
                scheduleStore.saveState(new JobScheduleState(
                        jobId, false, null, state.lastFiredAt(), state.lastExecutionId(),
                        state.lastOutcome(), state.lastDetail(), false, state.consecutiveFailures(), now));
                return ClaimedTick.none();
            }

            Instant scheduledFor = state.nextFireAt();
            if (config.endAt() != null && scheduledFor.isAfter(config.endAt())) {
                disableExhaustedScheduleLocked(job, state, now, "Schedule endAt window elapsed");
                return ClaimedTick.none();
            }

            Instant nextFuture = CronScheduleParser.computeNextFireAt(
                    config, properties.getDefaultTimezone(), now).orElse(null);

            Duration lag = Duration.between(scheduledFor, now);
            Duration grace = properties.getMisfireGracePeriod();
            if (grace != null && !grace.isNegative() && lag.compareTo(grace) > 0
                    && config.effectiveMisfirePolicy() == MisfirePolicy.SKIP) {
                String detail = "Missed scheduled tick at " + scheduledFor + " by " + lag.toSeconds()
                        + "s (exceeded grace " + grace.toSeconds() + "s)";
                boolean stillEnabled = nextFuture != null;
                if (!stillEnabled) {
                    jobController.updateScheduleDefinition(jobId, config.withEnabled(false));
                }
                scheduleStore.saveState(new JobScheduleState(
                        jobId, stillEnabled, nextFuture, now, state.lastExecutionId(),
                        DispatchOutcome.MISSED_SKIPPED, detail, state.coalescePending(),
                        state.consecutiveFailures(), now));
                recordDispatchAndTelemetry(job, scheduledFor, now, null, DispatchOutcome.MISSED_SKIPPED, detail);
                return ClaimedTick.none();
            }

            boolean remainEnabled = nextFuture != null;
            if (!remainEnabled) {
                jobController.updateScheduleDefinition(jobId, config.withEnabled(false));
            }
            scheduleStore.saveState(new JobScheduleState(
                    jobId, remainEnabled, nextFuture, now, state.lastExecutionId(),
                    state.lastOutcome(), state.lastDetail(), state.coalescePending(),
                    state.consecutiveFailures(), now));
            return new ClaimedTick(job, scheduledFor, true);
        });
    }

    private void executeClaimedTick(JobDTO job, Instant scheduledFor, Instant dispatchedAt) {
        JobScheduleConfig config = job.schedule();
        Span span = tracer == null ? null : tracer.spanBuilder("scheduler.dispatch")
                .setAttribute("opencrawling.job.id", job.id())
                .setAttribute("opencrawling.schedule.cron", config.cron())
                .setAttribute("opencrawling.schedule.timezone", config.effectiveTimezone(properties.getDefaultTimezone()))
                .setAttribute("opencrawling.schedule.scheduled_for", scheduledFor.toString())
                .startSpan();
        try (Scope ignored = span != null ? span.makeCurrent() : null) {
            StartOutcome outcome = dispatchWithOverlapHandling(job, config, scheduledFor);
            recordOutcome(job, scheduledFor, dispatchedAt, outcome, span);
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    private StartOutcome dispatchWithOverlapHandling(JobDTO job, JobScheduleConfig config, Instant scheduledFor) {
        StartOutcome outcome = jobController.startScheduled(job.id(), scheduledFor);
        if (outcome instanceof StartOutcome.AlreadyRunning running
                && config.effectiveOverlapPolicy() == OverlapPolicy.STOP_PREVIOUS) {
            log.info("Scheduled tick for job '{}' [ID: {}] stopping active execution [Exec ID: {}] (STOP_PREVIOUS).",
                    job.name(), job.id(), running.executionId());
            runRegistry.stopExecution(job.id(), running.executionId());
            long deadlineMs = System.currentTimeMillis() + Math.max(0L, properties.getStopPreviousWait().toMillis());
            while (System.currentTimeMillis() < deadlineMs && runRegistry.run(job.id()).isPresent()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            outcome = jobController.startScheduled(job.id(), scheduledFor);
        }
        return outcome;
    }

    private void recordOutcome(JobDTO job, Instant scheduledFor, Instant dispatchedAt, StartOutcome outcome, Span span) {
        switch (outcome) {
            case StartOutcome.Started started -> {
                updateOutcomeState(job.id(), started.executionId(), DispatchOutcome.STARTED, null, false, dispatchedAt);
                recordDispatchAndTelemetry(job, scheduledFor, dispatchedAt, started.executionId(), DispatchOutcome.STARTED, null);
                tagSpan(span, DispatchOutcome.STARTED, started.executionId());
            }
            case StartOutcome.AlreadyRunning running -> {
                OverlapPolicy policy = job.schedule().effectiveOverlapPolicy();
                boolean coalesce = policy == OverlapPolicy.COALESCE || policy == OverlapPolicy.STOP_PREVIOUS;
                String detail = coalesce
                        ? "Active execution " + running.executionId() + " still running; coalesced follow-up pending"
                        : "Previous execution " + running.executionId() + " is still active";
                log.warn("Scheduled run for job '{}' [ID: {}] skipped: {}", job.name(), job.id(), detail);
                updateOutcomeState(job.id(), null, DispatchOutcome.SKIPPED_OVERLAP, detail, coalesce, dispatchedAt);
                recordDispatchAndTelemetry(job, scheduledFor, dispatchedAt, null, DispatchOutcome.SKIPPED_OVERLAP, detail);
                tagSpan(span, DispatchOutcome.SKIPPED_OVERLAP, null);
            }
            case StartOutcome.LimitReached limit -> {
                String detail = "Max concurrent runs limit (" + limit.limit() + ") reached";
                log.warn("Scheduled run for job '{}' [ID: {}] skipped: {}", job.name(), job.id(), detail);
                updateOutcomeState(job.id(), null, DispatchOutcome.SKIPPED_CAPACITY, detail, false, dispatchedAt);
                recordDispatchAndTelemetry(job, scheduledFor, dispatchedAt, null, DispatchOutcome.SKIPPED_CAPACITY, detail);
                tagSpan(span, DispatchOutcome.SKIPPED_CAPACITY, null);
            }
            case StartOutcome.Rejected rejected -> {
                log.error("Scheduled run for job '{}' [ID: {}] rejected: {}", job.name(), job.id(), rejected.reason());
                handleFailureAndMaybeAutoPause(job.id(), scheduledFor, dispatchedAt, null,
                        DispatchOutcome.REJECTED, rejected.reason());
                if (span != null) {
                    span.setStatus(StatusCode.ERROR, rejected.reason());
                }
                tagSpan(span, DispatchOutcome.REJECTED, null);
            }
            case StartOutcome.NotFound ignored -> log.debug("Scheduled job {} vanished before dispatch.", job.id());
            case StartOutcome.Forbidden ignored -> log.warn("Scheduled start for job {} refused as forbidden.", job.id());
        }
    }

    private void onRunFinished(JobRunRegistry.RunInfo run) {
        boolean consumedScheduledFlag = jobController.consumeScheduledExecution(run.executionId());
        Instant now = clock.instant();
        AtomicReference<JobDTO> coalesceTarget = new AtomicReference<>();

        controlPlaneStore.locked(() -> {
            JobDTO job = jobController.findJobById(run.jobId());
            if (job == null || job.schedule() == null) {
                return null;
            }
            JobScheduleState state = scheduleStore.getState(run.jobId()).orElse(null);
            if (state == null) {
                return null;
            }
            boolean wasScheduled = consumedScheduledFlag
                    || (state.lastExecutionId() != null && state.lastExecutionId().equals(run.executionId()));

            int failures = state.consecutiveFailures();
            DispatchOutcome lastOutcome = state.lastOutcome();
            String lastDetail = state.lastDetail();
            boolean enabled = state.enabled() && job.schedule().enabled();
            Instant nextFireAt = state.nextFireAt();
            boolean coalescePending = state.coalescePending();

            if (wasScheduled) {
                if (run.state() == RunState.COMPLETED) {
                    failures = 0;
                } else if (run.state() == RunState.FAILED || run.state() == RunState.INTERRUPTED) {
                    failures = failures + 1;
                    int maxFail = job.schedule().effectiveMaxConsecutiveFailures();
                    if (maxFail > 0 && failures >= maxFail && enabled) {
                        String reason = "Auto-paused after " + failures + " consecutive failed scheduled run(s) (last: "
                                + run.executionId() + " " + run.state() + ")";
                        autoPauseLocked(job, state, failures, now, reason);
                        return null;
                    }
                }
            }

            if (coalescePending && enabled) {
                coalescePending = false;
                coalesceTarget.set(job);
            }

            scheduleStore.saveState(new JobScheduleState(
                    job.id(), enabled, nextFireAt, state.lastFiredAt(),
                    state.lastExecutionId(), lastOutcome, lastDetail, coalescePending, failures, now));
            return null;
        });

        JobDTO target = coalesceTarget.get();
        if (target != null) {
            Thread.ofVirtual().name("oc-sched-coalesce-" + target.id())
                    .start(() -> executeClaimedTick(target, now, now));
        }
        refreshGauges();
    }

    private void updateOutcomeState(String jobId, String executionId, DispatchOutcome outcome, String detail,
                                    boolean setCoalesce, Instant now) {
        controlPlaneStore.locked(() -> {
            JobScheduleState current = scheduleStore.getState(jobId).orElse(null);
            if (current == null) {
                return null;
            }
            boolean alreadyAutoPaused = outcome == DispatchOutcome.STARTED
                    && !current.enabled()
                    && current.lastOutcome() == DispatchOutcome.AUTO_PAUSED;
            scheduleStore.saveState(new JobScheduleState(
                    jobId,
                    current.enabled(),
                    current.nextFireAt(),
                    current.lastFiredAt(),
                    executionId != null ? executionId : current.lastExecutionId(),
                    alreadyAutoPaused ? current.lastOutcome() : outcome,
                    alreadyAutoPaused ? current.lastDetail() : detail,
                    alreadyAutoPaused ? false : (setCoalesce || current.coalescePending()),
                    current.consecutiveFailures(),
                    now));
            return null;
        });
    }

    private void handleFailureAndMaybeAutoPause(String jobId, Instant scheduledFor, Instant now, String executionId,
                                                DispatchOutcome failureOutcome, String reason) {
        JobDTO jobForMetrics = controlPlaneStore.locked(() -> {
            JobDTO job = jobController.findJobById(jobId);
            JobScheduleState current = scheduleStore.getState(jobId).orElse(null);
            if (job == null || job.schedule() == null || current == null) {
                return null;
            }
            int nextFailures = current.consecutiveFailures() + 1;
            int maxFail = job.schedule().effectiveMaxConsecutiveFailures();
            if (maxFail > 0 && nextFailures >= maxFail && current.enabled()) {
                recordDispatchAndTelemetry(job, scheduledFor, now, executionId, failureOutcome, reason);
                autoPauseLocked(job, current, nextFailures, now,
                        "Auto-paused after " + nextFailures + " consecutive failure(s): " + reason);
            } else {
                scheduleStore.saveState(new JobScheduleState(
                        jobId, current.enabled(), current.nextFireAt(), current.lastFiredAt(),
                        current.lastExecutionId(), failureOutcome, reason, current.coalescePending(),
                        nextFailures, now));
                recordDispatchAndTelemetry(job, scheduledFor, now, executionId, failureOutcome, reason);
            }
            return job;
        });
        if (jobForMetrics != null) {
            refreshGauges();
        }
    }

    private void autoPauseLocked(JobDTO job, JobScheduleState current, int failures, Instant now, String reason) {
        log.warn("Auto-pausing schedule for job '{}' [ID: {}]: {}", job.name(), job.id(), reason);
        jobController.updateScheduleDefinition(job.id(), job.schedule().withEnabled(false));
        scheduleStore.saveState(new JobScheduleState(
                job.id(), false, null, current.lastFiredAt(), current.lastExecutionId(),
                DispatchOutcome.AUTO_PAUSED, reason, false, failures, now));
        recordDispatchAndTelemetry(job, now, now, current.lastExecutionId(), DispatchOutcome.AUTO_PAUSED, reason);
    }

    private void disableExhaustedScheduleLocked(JobDTO job, JobScheduleState current, Instant now, String reason) {
        jobController.updateScheduleDefinition(job.id(), job.schedule().withEnabled(false));
        scheduleStore.saveState(new JobScheduleState(
                job.id(), false, null, current.lastFiredAt(), current.lastExecutionId(),
                current.lastOutcome(), reason, false, current.consecutiveFailures(), now));
    }

    private void recordDispatchAndTelemetry(JobDTO job, Instant scheduledFor, Instant dispatchedAt,
                                            String executionId, DispatchOutcome outcome, String detail) {
        String id = "disp-" + UUID.randomUUID().toString().substring(0, 12);
        scheduleStore.recordDispatch(new ScheduleDispatchRecord(
                id, job.id(), scheduledFor, dispatchedAt, runRegistry.runtimeId(), executionId, outcome, detail));
        if (meterRegistry != null) {
            Counter.builder("opencrawling.scheduler.dispatches.total")
                    .tag("outcome", outcome.name())
                    .tag("job_id", job.id())
                    .register(meterRegistry)
                    .increment();
            if (scheduledFor != null && dispatchedAt != null && !dispatchedAt.isBefore(scheduledFor)) {
                Timer.builder("opencrawling.scheduler.lag.seconds")
                        .tag("outcome", outcome.name())
                        .register(meterRegistry)
                        .record(Duration.between(scheduledFor, dispatchedAt));
            }
        }
    }

    private static void tagSpan(Span span, DispatchOutcome outcome, String executionId) {
        if (span == null) {
            return;
        }
        span.setAttribute("opencrawling.schedule.outcome", outcome.name());
        if (executionId != null) {
            span.setAttribute("opencrawling.execution.id", executionId);
        }
    }

    private void refreshGauges() {
        if (meterRegistry == null) {
            return;
        }
        int active = 0;
        int paused = 0;
        for (JobScheduleState state : scheduleStore.getAllStates().values()) {
            if (state.enabled()) {
                active++;
            } else {
                paused++;
            }
        }
        activeGauge.set(active);
        pausedGauge.set(paused);
    }
}
