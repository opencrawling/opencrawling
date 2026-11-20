/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import java.util.function.Supplier;

/**
 * Where runs are kept (#315, #316): every run of every kind, the runtime that runs it, its state and the history of its
 * states.
 *
 * <p>{@link InMemoryRunStore} keeps them in this runtime (the {@code file} control-plane store);
 * {@code JdbcRunStore} keeps them in the control-plane database, where several runtimes share them.
 *
 * <p>One state machine for every kind of run:
 * <ul>
 *   <li>waiting: {@link RunState#QUEUED} (asked for, waiting for its slot) or {@link RunState#PENDING_APPROVAL}
 *   (waiting for someone to approve it);</li>
 *   <li>active: {@link RunState#RUNNING} or {@link RunState#PAUSED}; only active runs hold a slot, count towards the
 *   caps, renew a heartbeat and can be interrupted;</li>
 *   <li>final: {@link RunState#COMPLETED}, {@link RunState#STOPPED}, {@link RunState#FAILED},
 *   {@link RunState#INTERRUPTED}, {@link RunState#REJECTED} or {@link RunState#EXPIRED}.</li>
 * </ul>
 * A job run is stored when it starts ({@link #claim}). A run a caller asks for, such as a dynamic job, is stored when it
 * is asked for ({@link #request}), waits, and becomes active when its slot is claimed: it is one record from the
 * request to its end.
 *
 * <p>Every change of a run's state is recorded as a {@link RunStep}. Rules that depend on the other runs (one run per
 * job, the caps) are checked and applied in one step by {@link #claim}.
 */
public interface RunStore {

    /** The state of a run. */
    enum RunState {
        QUEUED, PENDING_APPROVAL, RUNNING, PAUSED, COMPLETED, STOPPED, FAILED, INTERRUPTED, REJECTED, EXPIRED;

        /** Running or paused: it holds a slot. */
        public boolean active() {
            return this == RUNNING || this == PAUSED;
        }

        /** Asked for, not started yet. */
        public boolean waiting() {
            return this == QUEUED || this == PENDING_APPROVAL;
        }

        /** Ended: nothing changes its state any more. */
        public boolean ended() {
            return !active() && !waiting();
        }
    }

    /** What happened to a run, as recorded in its history. */
    enum RunEvent {
        QUEUED, APPROVAL_REQUESTED, APPROVED, STARTED, PAUSED, RESUMED, STOP_REQUESTED,
        COMPLETED, STOPPED, FAILED, INTERRUPTED, REJECTED, EXPIRED;

        public static RunEvent ended(RunState state) {
            return switch (state) {
                case COMPLETED -> COMPLETED;
                case STOPPED -> STOPPED;
                case FAILED -> FAILED;
                case INTERRUPTED -> INTERRUPTED;
                case REJECTED -> REJECTED;
                case EXPIRED -> EXPIRED;
                default -> throw new IllegalArgumentException(state + " is not a final state");
            };
        }

        /** The event that files a run in a waiting state. */
        public static RunEvent waiting(RunState state) {
            return switch (state) {
                case QUEUED -> QUEUED;
                case PENDING_APPROVAL -> APPROVAL_REQUESTED;
                default -> throw new IllegalArgumentException(state + " is not a waiting state");
            };
        }
    }

    /** What a run runs. */
    enum RunKind {
        /** A run of a saved job. */
        JOB,
        /** A dynamic job crawling a folder. */
        DYNAMIC_FOLDER,
        /** A dynamic job ingesting one document. */
        DYNAMIC_DOCUMENT
    }

    /**
     * A run as stored.
     *
     * @param runtimeId   the runtime that runs or ran it; {@code null} while it waits
     * @param startedAt   when it started; {@code null} while it waits
     * @param heartbeatAt its runtime's last heartbeat; {@code null} while it waits
     * @param endedAt     when it ended; {@code null} while it waits or is active
     * @param owner       who asked for it, for the runs a caller asks for; {@code null} for job runs
     * @param createdAt   when it was asked for (a job run: when it started)
     * @param body        the record of what was asked for, for the runs a caller asks for; {@code null} for job runs
     */
    record RunRecord(String executionId, String jobId, String caller, String runtimeId, RunState state,
                     boolean stopRequested, Instant startedAt, Instant heartbeatAt, Instant endedAt,
                     RunKind kind, String owner, Instant createdAt, String body) {

        /** A job run. */
        public RunRecord(String executionId, String jobId, String caller, String runtimeId, RunState state,
                         boolean stopRequested, Instant startedAt, Instant heartbeatAt, Instant endedAt) {
            this(executionId, jobId, caller, runtimeId, state, stopRequested, startedAt, heartbeatAt, endedAt,
                    RunKind.JOB, null, startedAt, null);
        }

        /** A run asked for and waiting in {@code state}, not started yet. */
        public static RunRecord waiting(String executionId, String jobId, String caller, RunState state, RunKind kind,
                                        String owner, Instant createdAt, String body) {
            if (!state.waiting()) {
                throw new IllegalArgumentException(state + " is not a waiting state");
            }
            return new RunRecord(executionId, jobId, caller, null, state, false, null, null, null, kind, owner,
                    createdAt, body);
        }
    }

    /** One entry of a run's history, numbered from 1. */
    record RunStep(String executionId, int seq, RunEvent event, Instant at, String runtimeId, String detail) {}

    /**
     * Whether other runtimes share these runs. Only then can a run's runtime stop renewing its heartbeat while this
     * one keeps running, so only then are stale runs interrupted.
     */
    default boolean shared() {
        return false;
    }

    /**
     * Runs {@code work} with no other change of runs in between, in this runtime or another sharing the store: for
     * changes that depend on what is stored (count, check, then change).
     */
    <T> T locked(Supplier<T> work);

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
     * Starts a run of {@code jobId} as {@link RunState#RUNNING}, unless the job already has an active run,
     * {@code maxRuns} runs are active, or {@code caller} holds {@code maxRunsPerCaller} active runs (0 means no cap; a
     * {@code null} caller only counts towards {@code maxRuns}). A run asked for earlier and still waiting under
     * {@code executionId} is started in place; otherwise a new run is stored.
     */
    Claim claim(String jobId, String executionId, String caller, String runtimeId, Instant now,
                int maxRuns, int maxRunsPerCaller);

    /**
     * Undoes a claim of a run that never started: a new run is forgotten with its history; a run that had waited goes
     * back to {@link RunState#QUEUED}.
     */
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

    /** Ends an active run in the final {@code state}; a run that is not active is left as it is. */
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

    /**
     * The runs kept, of {@code jobId} or of every job when it is {@code null}: the newest first (by the time they were
     * asked for, then id). What a run leaves is kept for the run retention.
     */
    List<RunRecord> list(String jobId);

    /** The run's history, in order; empty when the run is unknown. */
    List<RunStep> steps(String executionId);

    /**
     * Deletes the runs that ended before {@code cutoff}, with their history.
     *
     * @return how many runs it deleted
     */
    int deleteEndedBefore(Instant cutoff);

    // ---- Runs a caller asks for (dynamic jobs, #316) ----

    /**
     * Stores a run asked for, in its waiting state ({@link RunRecord#waiting}), and records why it waits.
     *
     * @param runtimeId the runtime that took the request, for its history
     */
    void request(RunRecord run, String runtimeId, String detail);

    /** Replaces the record of what was asked for ({@link RunRecord#body}). */
    void updateBody(String executionId, String body);

    /**
     * Ends a waiting run in the final {@code state} (it never started).
     *
     * @return {@code false} when the run is not waiting
     */
    boolean settle(String executionId, RunState state, String runtimeId, Instant now, String detail);

    /** Records an event in a run's history that does not change its state, such as {@link RunEvent#APPROVED}. */
    void record(String executionId, RunEvent event, String runtimeId, Instant now, String detail);

    /** Forgets a run and its history, whatever its state. */
    void delete(String executionId);

    /**
     * The runs asked for by {@code owner}, or by anyone when it is {@code null}, newest first (by the time they were
     * asked for, then the order they were stored in).
     */
    List<RunRecord> requested(String owner);

    /** How many of {@code owner}'s requested runs are in one of {@code states}. */
    int countRequested(String owner, Collection<RunState> states);

    /** The requested runs in {@code state} asked for before {@code cutoff}, in the order they were stored in. */
    List<RunRecord> requestedBefore(RunState state, Instant cutoff);

    /**
     * Forgets the requested runs that ended before {@code cutoff}, then the oldest ended ones beyond {@code keep}.
     *
     * @return the job ids of the runs it forgot
     */
    List<String> forgetEndedRequested(Instant cutoff, int keep);

    /** For tests: the job ids of the requested runs, in the order they were stored in. */
    List<String> requestedJobIds();
}
