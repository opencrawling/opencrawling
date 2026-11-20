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
package org.opencrawling.runtime.controlplane;

import org.opencrawling.runtime.orchestrator.RunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs kept in the control-plane database ({@code oc_run}, {@code oc_run_step}; migrations V3 and V7), shared by every
 * runtime on it: job runs from their start, and the runs a caller asks for (dynamic jobs) from their request, each one
 * row from beginning to end.
 *
 * <p>Every change of a run's state runs under {@link ControlPlaneStore#locked}, the lock the runtimes already take for
 * definitions, so the rules that depend on the other runs (one run per job, the caps) hold across runtimes, and a
 * run's history is numbered without gaps or duplicates. Heartbeats only touch their own rows and take no lock. Runs
 * never change the store's revision: they are not definitions, and no definition cache depends on them.
 */
public class JdbcRunStore implements RunStore {

    private static final String COLUMNS = "execution_id, job_id, caller, runtime_id, state, stop_requested, started_at,"
            + " heartbeat_at, ended_at, kind, owner, created_at, body";
    private static final String ACTIVE = "state IN ('" + RunState.RUNNING + "', '" + RunState.PAUSED + "')";
    private static final String WAITING = "state IN ('" + RunState.QUEUED + "', '" + RunState.PENDING_APPROVAL + "')";
    private static final String REQUESTED = "kind <> '" + RunKind.JOB + "'";
    private static final int MAX_DETAIL = 1024;
    private static final int IN_BATCH = 500;

    private static final RowMapper<RunRecord> RUN = (rs, row) -> new RunRecord(
            rs.getString("execution_id"), rs.getString("job_id"), rs.getString("caller"), rs.getString("runtime_id"),
            RunState.valueOf(rs.getString("state")), rs.getBoolean("stop_requested"),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("heartbeat_at")),
            instant(rs.getTimestamp("ended_at")), RunKind.valueOf(rs.getString("kind")), rs.getString("owner"),
            instant(rs.getTimestamp("created_at")), rs.getString("body"));

    private static final RowMapper<RunStep> STEP = (rs, row) -> new RunStep(
            rs.getString("execution_id"), rs.getInt("seq"), RunEvent.valueOf(rs.getString("event")),
            instant(rs.getTimestamp("occurred_at")), rs.getString("runtime_id"), rs.getString("detail"));

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore store;

    public JdbcRunStore(JdbcTemplate jdbc, ControlPlaneStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public boolean shared() {
        return true;
    }

    @Override
    public <T> T locked(Supplier<T> work) {
        return store.locked(work);
    }

    @Override
    public Claim claim(String jobId, String executionId, String caller, String runtimeId, Instant now,
                       int maxRuns, int maxRunsPerCaller) {
        return store.locked(() -> {
            Optional<RunRecord> busy = active(jobId);
            if (busy.isPresent()) {
                return new Busy(busy.get());
            }
            if (maxRuns > 0 && count("SELECT COUNT(*) FROM oc_run WHERE " + ACTIVE) >= maxRuns) {
                return new Full(maxRuns);
            }
            if (caller != null && maxRunsPerCaller > 0
                    && count("SELECT COUNT(*) FROM oc_run WHERE " + ACTIVE + " AND caller = ?", caller) >= maxRunsPerCaller) {
                return new CallerFull(caller, maxRunsPerCaller);
            }
            // A run asked for earlier starts in place; otherwise a new run is stored.
            if (jdbc.update("UPDATE oc_run SET caller = ?, runtime_id = ?, state = ?, stop_requested = ?, started_at = ?,"
                            + " heartbeat_at = ? WHERE execution_id = ? AND job_id = ? AND " + WAITING,
                    caller, runtimeId, RunState.RUNNING.name(), false, ts(now), ts(now), executionId, jobId) == 0) {
                jdbc.update("INSERT INTO oc_run (" + COLUMNS + ", position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, NULL, ?)",
                        executionId, jobId, caller, runtimeId, RunState.RUNNING.name(), false, ts(now), ts(now),
                        RunKind.JOB.name(), ts(now), nextPosition());
            }
            record(executionId, RunEvent.STARTED, now, runtimeId, null);
            return new Claimed(get(executionId).orElseThrow());
        });
    }

    @Override
    public void release(String executionId) {
        store.locked(() -> {
            RunRecord run = get(executionId).orElse(null);
            if (run == null) {
                return null;
            }
            if (run.kind() == RunKind.JOB) {
                delete(executionId);
                return null;
            }
            // A run that had waited goes back to waiting, without the start it never made.
            jdbc.update("UPDATE oc_run SET runtime_id = NULL, state = ?, stop_requested = ?, started_at = NULL,"
                    + " heartbeat_at = NULL WHERE execution_id = ?", RunState.QUEUED.name(), false, executionId);
            jdbc.update("DELETE FROM oc_run_step WHERE execution_id = ? AND event = ? AND seq = (SELECT MAX(seq)"
                    + " FROM oc_run_step WHERE execution_id = ?)", executionId, RunEvent.STARTED.name(), executionId);
            return null;
        });
    }

    @Override
    public boolean requestStop(String executionId, String runtimeId, Instant now) {
        return store.locked(() -> {
            Optional<RunRecord> run = activeRun(executionId);
            if (run.isEmpty()) {
                return false;
            }
            if (!run.get().stopRequested()) {
                jdbc.update("UPDATE oc_run SET stop_requested = ? WHERE execution_id = ?", true, executionId);
                record(executionId, RunEvent.STOP_REQUESTED, now, runtimeId, null);
            }
            return true;
        });
    }

    @Override
    public boolean pause(String executionId, String runtimeId, Instant now) {
        return change(executionId, RunState.RUNNING, RunState.PAUSED, RunEvent.PAUSED, runtimeId, now);
    }

    @Override
    public boolean resume(String executionId, String runtimeId, Instant now) {
        return change(executionId, RunState.PAUSED, RunState.RUNNING, RunEvent.RESUMED, runtimeId, now);
    }

    @Override
    public void finish(String executionId, RunState state, String runtimeId, Instant now, String detail) {
        RunEvent event = RunEvent.ended(state);
        store.locked(() -> {
            if (jdbc.update("UPDATE oc_run SET state = ?, ended_at = ? WHERE execution_id = ? AND " + ACTIVE,
                    state.name(), ts(now), executionId) > 0) {
                record(executionId, event, now, runtimeId, detail);
            }
            return null;
        });
    }

    @Override
    public List<RunRecord> heartbeat(String runtimeId, Collection<String> executionIds, Instant now) {
        List<RunRecord> current = new ArrayList<>();
        List<String> ids = List.copyOf(executionIds);
        for (int from = 0; from < ids.size(); from += IN_BATCH) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + IN_BATCH));
            String in = String.join(", ", Collections.nCopies(batch.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(ts(now), runtimeId));
            args.addAll(batch);
            jdbc.update("UPDATE oc_run SET heartbeat_at = ? WHERE runtime_id = ? AND " + ACTIVE
                    + " AND execution_id IN (" + in + ")", args.toArray());
            current.addAll(jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE execution_id IN (" + in + ")",
                    RUN, batch.toArray()));
        }
        return current;
    }

    @Override
    public List<RunRecord> interruptStale(Instant staleBefore, String runtimeId, Instant now) {
        // Checked without the lock first: nearly always nothing is stale, and a heartbeat must not wait for it.
        if (count("SELECT COUNT(*) FROM oc_run WHERE " + ACTIVE + " AND heartbeat_at < ?", ts(staleBefore)) == 0) {
            return List.of();
        }
        return store.locked(() -> {
            List<RunRecord> stale = jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE " + ACTIVE
                    + " AND heartbeat_at < ?", RUN, ts(staleBefore));
            List<RunRecord> interrupted = new ArrayList<>();
            for (RunRecord run : stale) {
                finish(run.executionId(), RunState.INTERRUPTED, runtimeId, now, detail(run));
                get(run.executionId()).ifPresent(interrupted::add);
            }
            return interrupted;
        });
    }

    @Override
    public Optional<RunRecord> get(String executionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE execution_id = ?", RUN, executionId)
                .stream().findFirst();
    }

    @Override
    public Optional<RunRecord> active(String jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE job_id = ? AND " + ACTIVE
                + " ORDER BY started_at, execution_id", RUN, jobId).stream().findFirst();
    }

    @Override
    public List<RunRecord> active() {
        return jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE " + ACTIVE + " ORDER BY started_at, execution_id", RUN);
    }

    @Override
    public List<RunRecord> list(String jobId) {
        return jobId == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM oc_run ORDER BY created_at DESC, execution_id", RUN)
                : jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE job_id = ? ORDER BY created_at DESC, execution_id", RUN, jobId);
    }

    @Override
    public List<RunStep> steps(String executionId) {
        return jdbc.query("SELECT execution_id, seq, event, occurred_at, runtime_id, detail FROM oc_run_step"
                + " WHERE execution_id = ? ORDER BY seq", STEP, executionId);
    }

    @Override
    public int deleteEndedBefore(Instant cutoff) {
        return store.locked(() -> {
            jdbc.update("DELETE FROM oc_run_step WHERE execution_id IN (SELECT execution_id FROM oc_run"
                    + " WHERE ended_at IS NOT NULL AND ended_at < ?)", ts(cutoff));
            return jdbc.update("DELETE FROM oc_run WHERE ended_at IS NOT NULL AND ended_at < ?", ts(cutoff));
        });
    }

    // ---- Runs a caller asks for ----

    @Override
    public void request(RunRecord run, String runtimeId, String detail) {
        if (!run.state().waiting()) {
            throw new IllegalArgumentException(run.state() + " is not a waiting state");
        }
        store.locked(() -> {
            jdbc.update("INSERT INTO oc_run (" + COLUMNS + ", position) VALUES (?, ?, ?, NULL, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?)",
                    run.executionId(), run.jobId(), run.caller(), run.state().name(), false, run.kind().name(),
                    run.owner(), ts(run.createdAt()), run.body(), nextPosition());
            record(run.executionId(), RunEvent.waiting(run.state()), run.createdAt(), runtimeId, detail);
            return null;
        });
    }

    @Override
    public void updateBody(String executionId, String body) {
        jdbc.update("UPDATE oc_run SET body = ? WHERE execution_id = ?", body, executionId);
    }

    @Override
    public boolean settle(String executionId, RunState state, String runtimeId, Instant now, String detail) {
        RunEvent event = RunEvent.ended(state);
        return store.locked(() -> {
            if (jdbc.update("UPDATE oc_run SET state = ?, ended_at = ? WHERE execution_id = ? AND " + WAITING,
                    state.name(), ts(now), executionId) == 0) {
                return false;
            }
            record(executionId, event, now, runtimeId, detail);
            return true;
        });
    }

    @Override
    public void record(String executionId, RunEvent event, String runtimeId, Instant now, String detail) {
        store.locked(() -> {
            if (get(executionId).isPresent()) {
                record(executionId, event, now, runtimeId, detail);
            }
            return null;
        });
    }

    @Override
    public void delete(String executionId) {
        store.locked(() -> {
            jdbc.update("DELETE FROM oc_run_step WHERE execution_id = ?", executionId);
            jdbc.update("DELETE FROM oc_run WHERE execution_id = ?", executionId);
            return null;
        });
    }

    @Override
    public List<RunRecord> requested(String owner) {
        return owner == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE " + REQUESTED + " ORDER BY created_at DESC, position", RUN)
                : jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE " + REQUESTED + " AND owner = ?"
                        + " ORDER BY created_at DESC, position", RUN, owner);
    }

    @Override
    public int countRequested(String owner, Collection<RunState> states) {
        if (states.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>();
        args.add(owner);
        states.forEach(state -> args.add(state.name()));
        return (int) count("SELECT COUNT(*) FROM oc_run WHERE " + REQUESTED + " AND owner = ? AND state IN ("
                + String.join(", ", Collections.nCopies(states.size(), "?")) + ")", args.toArray());
    }

    @Override
    public List<RunRecord> requestedBefore(RunState state, Instant cutoff) {
        return jdbc.query("SELECT " + COLUMNS + " FROM oc_run WHERE " + REQUESTED + " AND state = ? AND created_at < ?"
                + " ORDER BY position", RUN, state.name(), ts(cutoff));
    }

    @Override
    public List<String> forgetEndedRequested(Instant cutoff, int keep) {
        return store.locked(() -> {
            List<String[]> old = jdbc.query("SELECT execution_id, job_id FROM oc_run WHERE " + REQUESTED
                    + " AND ended_at IS NOT NULL AND ended_at < ?", (rs, n) -> new String[]{rs.getString(1), rs.getString(2)},
                    ts(cutoff));
            List<String[]> ended = jdbc.query("SELECT execution_id, job_id FROM oc_run WHERE " + REQUESTED
                    + " AND ended_at IS NOT NULL AND NOT ended_at < ? ORDER BY position",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, ts(cutoff));
            List<String[]> forget = new ArrayList<>(old);
            forget.addAll(ended.subList(0, Math.max(0, ended.size() - keep)));
            List<String> jobIds = new ArrayList<>();
            for (String[] run : forget) {
                delete(run[0]);
                jobIds.add(run[1]);
            }
            return jobIds;
        });
    }

    @Override
    public List<String> requestedJobIds() {
        return jdbc.queryForList("SELECT job_id FROM oc_run WHERE " + REQUESTED + " ORDER BY position", String.class);
    }

    private boolean change(String executionId, RunState from, RunState to, RunEvent event, String runtimeId, Instant now) {
        return store.locked(() -> {
            Optional<RunRecord> run = activeRun(executionId);
            if (run.isEmpty()) {
                return false;
            }
            if (run.get().state() == from) {
                jdbc.update("UPDATE oc_run SET state = ? WHERE execution_id = ?", to.name(), executionId);
                record(executionId, event, now, runtimeId, null);
            }
            return true;
        });
    }

    private Optional<RunRecord> activeRun(String executionId) {
        return get(executionId).filter(run -> run.state().active());
    }

    /** Called under the lock, so a number is never taken twice. */
    private long nextPosition() {
        Long last = jdbc.queryForObject("SELECT MAX(position) FROM oc_run", Long.class);
        return last == null ? 1 : last + 1;
    }

    /** Called under the lock, so the next number is never taken twice. */
    private void record(String executionId, RunEvent event, Instant at, String runtimeId, String detail) {
        Integer last = jdbc.queryForObject("SELECT MAX(seq) FROM oc_run_step WHERE execution_id = ?", Integer.class, executionId);
        String trimmed = detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
        jdbc.update("INSERT INTO oc_run_step (execution_id, seq, event, occurred_at, runtime_id, detail) VALUES (?, ?, ?, ?, ?, ?)",
                executionId, last == null ? 1 : last + 1, event.name(), ts(at), runtimeId, trimmed);
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static String detail(RunRecord run) {
        return "No heartbeat from runtime " + run.runtimeId() + " since " + run.heartbeatAt();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
