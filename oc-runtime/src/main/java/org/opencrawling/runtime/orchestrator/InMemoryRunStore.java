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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Runs kept in this runtime's memory: the {@code file} control-plane store, where one runtime owns the state. */
public class InMemoryRunStore implements RunStore {

    static final Comparator<RunRecord> OLDEST_FIRST =
            Comparator.comparing(RunRecord::startedAt).thenComparing(RunRecord::executionId);

    private final Map<String, RunRecord> runs = new LinkedHashMap<>();
    private final Map<String, List<RunStep>> steps = new LinkedHashMap<>();

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
        RunRecord run = new RunRecord(executionId, jobId, caller, runtimeId, RunState.RUNNING, false, now, now, null);
        runs.put(executionId, run);
        record(executionId, RunEvent.STARTED, now, runtimeId, null);
        return new Claimed(run);
    }

    @Override
    public synchronized void release(String executionId) {
        runs.remove(executionId);
        steps.remove(executionId);
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
            if (run.endedAt() == null && run.runtimeId().equals(runtimeId)) {
                run = new RunRecord(run.executionId(), run.jobId(), run.caller(), run.runtimeId(), run.state(),
                        run.stopRequested(), run.startedAt(), now, null);
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
    public synchronized List<RunStep> steps(String executionId) {
        return List.copyOf(steps.getOrDefault(executionId, List.of()));
    }

    @Override
    public synchronized int deleteEndedBefore(Instant cutoff) {
        List<String> old = runs.values().stream()
                .filter(run -> run.endedAt() != null && run.endedAt().isBefore(cutoff))
                .map(RunRecord::executionId).toList();
        old.forEach(this::release);
        return old.size();
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
                stopRequested, run.startedAt(), run.heartbeatAt(), endedAt));
    }

    private void record(String executionId, RunEvent event, Instant at, String runtimeId, String detail) {
        List<RunStep> history = steps.computeIfAbsent(executionId, id -> new ArrayList<>());
        history.add(new RunStep(executionId, history.size() + 1, event, at, Objects.requireNonNull(runtimeId), detail));
    }

    @Override
    public synchronized List<RunRecord> list(String jobId) {
        return runs.values().stream().filter(run -> jobId == null || run.jobId().equals(jobId))
                .sorted(java.util.Comparator.comparing(RunRecord::startedAt).reversed().thenComparing(RunRecord::executionId))
                .toList();
    }
}
