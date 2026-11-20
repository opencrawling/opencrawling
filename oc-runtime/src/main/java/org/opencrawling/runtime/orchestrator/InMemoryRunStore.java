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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs kept in this runtime's memory: the {@code file} control-plane store, where one runtime runs everything. Lost on
 * restart, like the runs themselves.
 */
public class InMemoryRunStore implements RunStore {

    static final Comparator<RunRecord> OLDEST_FIRST =
            Comparator.comparing(RunRecord::startedAt).thenComparing(RunRecord::executionId);

    /** By execution id, in the order they were stored in. */
    private final Map<String, RunRecord> runs = new LinkedHashMap<>();
    private final Map<String, List<RunStep>> steps = new LinkedHashMap<>();

    @Override
    public synchronized <T> T locked(Supplier<T> work) {
        return work.get();
    }

    @Override
    public synchronized Claim claim(String jobId, String executionId, String caller, String runtimeId, Instant now,
                                    int maxRuns, int maxRunsPerCaller) {
        List<RunRecord> active = active();
        Optional<RunRecord> busy = active.stream().filter(run -> run.jobId().equals(jobId)).findFirst();
        if (busy.isPresent()) {
            return new Busy(busy.get());
        }
        if (maxRuns > 0 && active.size() >= maxRuns) {
            return new Full(maxRuns);
        }
        if (caller != null && maxRunsPerCaller > 0
                && active.stream().filter(run -> caller.equals(run.caller())).count() >= maxRunsPerCaller) {
            return new CallerFull(caller, maxRunsPerCaller);
        }
        RunRecord waiting = runs.get(executionId);
        RunRecord run = waiting != null && waiting.state().waiting() && waiting.jobId().equals(jobId)
                ? new RunRecord(executionId, jobId, caller, runtimeId, RunState.RUNNING, false, now, now, null,
                        waiting.kind(), waiting.owner(), waiting.createdAt(), waiting.body())
                : new RunRecord(executionId, jobId, caller, runtimeId, RunState.RUNNING, false, now, now, null);
        runs.put(executionId, run);
        record(executionId, RunEvent.STARTED, now, runtimeId, null);
        return new Claimed(run);
    }

    @Override
    public synchronized void release(String executionId) {
        RunRecord run = runs.get(executionId);
        if (run == null) {
            return;
        }
        if (run.kind() == RunKind.JOB) {
            delete(executionId);
            return;
        }
        // A run that had waited goes back to waiting, without the start it never made.
        runs.put(executionId, new RunRecord(executionId, run.jobId(), run.caller(), null, RunState.QUEUED, false, null,
                null, null, run.kind(), run.owner(), run.createdAt(), run.body()));
        List<RunStep> history = steps.get(executionId);
        if (history != null && !history.isEmpty() && history.getLast().event() == RunEvent.STARTED) {
            history.removeLast();
        }
    }

    @Override
    public synchronized boolean requestStop(String executionId, String runtimeId, Instant now) {
        RunRecord run = activeRun(executionId);
        if (run == null) {
            return false;
        }
        if (!run.stopRequested()) {
            put(run, run.state(), true, null);
            record(executionId, RunEvent.STOP_REQUESTED, now, runtimeId, null);
        }
        return true;
    }

    @Override
    public synchronized boolean pause(String executionId, String runtimeId, Instant now) {
        return change(executionId, RunState.RUNNING, RunState.PAUSED, RunEvent.PAUSED, runtimeId, now);
    }

    @Override
    public synchronized boolean resume(String executionId, String runtimeId, Instant now) {
        return change(executionId, RunState.PAUSED, RunState.RUNNING, RunEvent.RESUMED, runtimeId, now);
    }

    @Override
    public synchronized void finish(String executionId, RunState state, String runtimeId, Instant now, String detail) {
        RunRecord run = activeRun(executionId);
        if (run != null) {
            put(run, state, run.stopRequested(), now);
            record(executionId, RunEvent.ended(state), now, runtimeId, detail);
        }
    }

    @Override
    public synchronized List<RunRecord> heartbeat(String runtimeId, Collection<String> executionIds, Instant now) {
        List<RunRecord> current = new ArrayList<>();
        for (String executionId : executionIds) {
            RunRecord run = runs.get(executionId);
            if (run == null) {
                continue;
            }
            if (run.state().active() && Objects.equals(run.runtimeId(), runtimeId)) {
                run = new RunRecord(run.executionId(), run.jobId(), run.caller(), run.runtimeId(), run.state(),
                        run.stopRequested(), run.startedAt(), now, null, run.kind(), run.owner(), run.createdAt(),
                        run.body());
                runs.put(executionId, run);
            }
            current.add(run);
        }
        return current;
    }

    @Override
    public synchronized List<RunRecord> interruptStale(Instant staleBefore, String runtimeId, Instant now) {
        List<RunRecord> stale = active().stream().filter(run -> run.heartbeatAt().isBefore(staleBefore)).toList();
        stale.forEach(run -> finish(run.executionId(), RunState.INTERRUPTED, runtimeId, now, staleDetail(run)));
        return stale.stream().map(run -> runs.get(run.executionId())).toList();
    }

    @Override
    public synchronized Optional<RunRecord> get(String executionId) {
        return Optional.ofNullable(runs.get(executionId));
    }

    @Override
    public synchronized Optional<RunRecord> active(String jobId) {
        return active().stream().filter(run -> run.jobId().equals(jobId)).findFirst();
    }

    @Override
    public synchronized List<RunRecord> active() {
        return runs.values().stream().filter(run -> run.state().active()).sorted(OLDEST_FIRST).toList();
    }

    @Override
    public synchronized List<RunRecord> list(String jobId) {
        return runs.values().stream().filter(run -> jobId == null || run.jobId().equals(jobId))
                .sorted(Comparator.comparing(RunRecord::createdAt).reversed().thenComparing(RunRecord::executionId))
                .toList();
    }

    @Override
    public synchronized List<RunStep> steps(String executionId) {
        return List.copyOf(steps.getOrDefault(executionId, List.of()));
    }

    @Override
    public synchronized int deleteEndedBefore(Instant cutoff) {
        List<String> old = runs.values().stream()
                .filter(run -> run.endedAt() != null && run.endedAt().isBefore(cutoff))
                .map(RunRecord::executionId).toList();
        old.forEach(this::delete);
        return old.size();
    }

    // ---- Runs a caller asks for ----

    @Override
    public synchronized void request(RunRecord run, String runtimeId, String detail) {
        if (!run.state().waiting()) {
            throw new IllegalArgumentException(run.state() + " is not a waiting state");
        }
        runs.put(run.executionId(), run);
        record(run.executionId(), RunEvent.waiting(run.state()), run.createdAt(), runtimeId, detail);
    }

    @Override
    public synchronized void updateBody(String executionId, String body) {
        RunRecord run = runs.get(executionId);
        if (run != null) {
            runs.put(executionId, new RunRecord(run.executionId(), run.jobId(), run.caller(), run.runtimeId(),
                    run.state(), run.stopRequested(), run.startedAt(), run.heartbeatAt(), run.endedAt(), run.kind(),
                    run.owner(), run.createdAt(), body));
        }
    }

    @Override
    public synchronized boolean settle(String executionId, RunState state, String runtimeId, Instant now, String detail) {
        RunRecord run = runs.get(executionId);
        if (run == null || !run.state().waiting()) {
            return false;
        }
        put(run, state, run.stopRequested(), now);
        record(executionId, RunEvent.ended(state), now, runtimeId, detail);
        return true;
    }

    @Override
    public synchronized void record(String executionId, RunEvent event, String runtimeId, Instant now, String detail) {
        if (runs.containsKey(executionId)) {
            record(executionId, event, now, runtimeId, detail);
        }
    }

    @Override
    public synchronized void delete(String executionId) {
        runs.remove(executionId);
        steps.remove(executionId);
    }

    @Override
    public synchronized List<RunRecord> requested(String owner) {
        List<RunRecord> stored = requestedInOrder().stream()
                .filter(run -> owner == null || owner.equals(run.owner())).toList();
        // Newest first; runs asked for at the same time keep the order they were stored in.
        List<RunRecord> newestFirst = new ArrayList<>(stored);
        newestFirst.sort(Comparator.comparing(RunRecord::createdAt).reversed());
        return newestFirst;
    }

    @Override
    public synchronized int countRequested(String owner, Collection<RunState> states) {
        return (int) requestedInOrder().stream()
                .filter(run -> Objects.equals(owner, run.owner()) && states.contains(run.state())).count();
    }

    @Override
    public synchronized List<RunRecord> requestedBefore(RunState state, Instant cutoff) {
        return requestedInOrder().stream()
                .filter(run -> run.state() == state && run.createdAt().isBefore(cutoff)).toList();
    }

    @Override
    public synchronized List<String> forgetEndedRequested(Instant cutoff, int keep) {
        List<String> forgotten = new ArrayList<>();
        for (RunRecord run : requestedInOrder()) {
            if (run.endedAt() != null && run.endedAt().isBefore(cutoff)) {
                delete(run.executionId());
                forgotten.add(run.jobId());
            }
        }
        List<RunRecord> ended = requestedInOrder().stream().filter(run -> run.endedAt() != null).toList();
        for (RunRecord run : ended.subList(0, Math.max(0, ended.size() - keep))) {
            delete(run.executionId());
            forgotten.add(run.jobId());
        }
        return forgotten;
    }

    @Override
    public synchronized List<String> requestedJobIds() {
        return requestedInOrder().stream().map(RunRecord::jobId).toList();
    }

    private List<RunRecord> requestedInOrder() {
        return runs.values().stream().filter(run -> run.kind() != RunKind.JOB).toList();
    }

    static String staleDetail(RunRecord run) {
        return "No heartbeat from runtime " + run.runtimeId() + " since " + run.heartbeatAt();
    }

    private boolean change(String executionId, RunState from, RunState to, RunEvent event, String runtimeId, Instant now) {
        RunRecord run = activeRun(executionId);
        if (run == null) {
            return false;
        }
        if (run.state() == from) {
            put(run, to, run.stopRequested(), null);
            record(executionId, event, now, runtimeId, null);
        }
        return true;
    }

    private RunRecord activeRun(String executionId) {
        RunRecord run = runs.get(executionId);
        return run != null && run.state().active() ? run : null;
    }

    private void put(RunRecord run, RunState state, boolean stopRequested, Instant endedAt) {
        runs.put(run.executionId(), new RunRecord(run.executionId(), run.jobId(), run.caller(), run.runtimeId(), state,
                stopRequested, run.startedAt(), run.heartbeatAt(), endedAt, run.kind(), run.owner(), run.createdAt(),
                run.body()));
    }

    private void record(String executionId, RunEvent event, Instant at, String runtimeId, String detail) {
        List<RunStep> history = steps.computeIfAbsent(executionId, id -> new ArrayList<>());
        history.add(new RunStep(executionId, history.size() + 1, event, at, Objects.requireNonNull(runtimeId), detail));
    }
}
