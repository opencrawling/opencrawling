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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Where job runs are kept (#315): every run, the runtime that runs it, its state and the history of its states.
 *
 * <p>{@link InMemoryRunStore} keeps them in this runtime (the {@code file} control-plane store);
 * {@code JdbcRunStore} keeps them in the control-plane database, where several runtimes share them. A run is
 * active while it is {@link RunState#RUNNING} or {@link RunState#PAUSED}; the other states are final.
 *
 * <p>Every change of a run's state is recorded as a {@link RunStep}. Rules that depend on the other runs (one run per
 * job, the caps) are checked and applied in one step by {@link #claim}.
 */
public interface RunStore {

    /** The state of a run. */
    enum RunState {
        RUNNING, PAUSED, COMPLETED, STOPPED, FAILED, INTERRUPTED;

        public boolean active() {
            return this == RUNNING || this == PAUSED;
        }
    }

    /** What happened to a run, as recorded in its history. */
    enum RunEvent {
        STARTED, PAUSED, RESUMED, STOP_REQUESTED, COMPLETED, STOPPED, FAILED, INTERRUPTED;

        public static RunEvent ended(RunState state) {
            return switch (state) {
                case COMPLETED -> COMPLETED;
                case STOPPED -> STOPPED;
                case FAILED -> FAILED;
                case INTERRUPTED -> INTERRUPTED;
                default -> throw new IllegalArgumentException(state + " is not a final state");
            };
        }
    }

    /** A run as stored. {@code endedAt} is {@code null} while it is active. */
    record RunRecord(String executionId, String jobId, String caller, String runtimeId, RunState state,
                     boolean stopRequested, Instant startedAt, Instant heartbeatAt, Instant endedAt) {}

    /** One entry of a run's history, numbered from 1. */
    record RunStep(String executionId, int seq, RunEvent event, Instant at, String runtimeId, String detail) {}

    /**
     * Whether other runtimes share these runs. Only then can a run's runtime stop renewing its heartbeat while this
     * one keeps running, so only then are stale runs interrupted.
     */
    default boolean shared() {
        return false;
    }

    /** The outcome of {@link #claim}. */
    sealed interface Claim permits Claimed, Busy, Full, CallerFull {}

    /** The run was stored as {@link RunState#RUNNING}. */
    record Claimed(RunRecord run) implements Claim {}

    /** The job already has an active run. */
    record Busy(RunRecord run) implements Claim {}

    /** {@code limit} runs are already active. */
    record Full(int limit) implements Claim {}

    /** The caller already holds {@code limit} active runs. */
    record CallerFull(String caller, int limit) implements Claim {}

    /**
     * Stores a new run of {@code jobId}, unless the job already has an active run, {@code maxRuns} runs are active,
     * or {@code caller} holds {@code maxRunsPerCaller} active runs (0 means no cap; a {@code null} caller only counts
     * towards {@code maxRuns}).
     */
    Claim claim(String jobId, String executionId, String caller, String runtimeId, Instant now,
                int maxRuns, int maxRunsPerCaller);

    /** Forgets a claimed run that never started, with its history. */
    void release(String executionId);

    /**
     * Records that a stop was requested for an active run.
     *
     * @return {@code false} when the run is not active
     */
    boolean requestStop(String executionId, String runtimeId, Instant now);

    /**
     * Pauses an active run; a paused run stays paused.
     *
     * @return {@code false} when the run is not active
     */
    boolean pause(String executionId, String runtimeId, Instant now);

    /**
     * Resumes a paused run; a running run keeps running.
     *
     * @return {@code false} when the run is not active
     */
    boolean resume(String executionId, String runtimeId, Instant now);

    /** Ends an active run in the final {@code state}; a run that already ended is left as it is. */
    void finish(String executionId, RunState state, String runtimeId, Instant now, String detail);

    /**
     * Renews the heartbeat of {@code runtimeId}'s active runs among {@code executionIds}.
     *
     * @return those runs as stored now, ended ones included, so the runtime sees stops, pauses and interruptions
     *         decided elsewhere
     */
    List<RunRecord> heartbeat(String runtimeId, Collection<String> executionIds, Instant now);

    /**
     * Marks the active runs whose heartbeat is older than {@code staleBefore} as {@link RunState#INTERRUPTED}: their
     * runtime stopped renewing it.
     *
     * @return the runs it interrupted
     */
    List<RunRecord> interruptStale(Instant staleBefore, String runtimeId, Instant now);

    Optional<RunRecord> get(String executionId);

    /** The job's active run. */
    Optional<RunRecord> active(String jobId);

    /** The active runs, oldest first. */
    List<RunRecord> active();

    /** The run's history, in order; empty when the run is unknown. */
    List<RunStep> steps(String executionId);

    /**
     * Deletes the runs that ended before {@code cutoff}, with their history.
     *
     * @return how many runs it deleted
     */
    int deleteEndedBefore(Instant cutoff);
}
