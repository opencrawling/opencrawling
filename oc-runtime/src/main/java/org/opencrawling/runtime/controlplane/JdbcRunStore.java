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
import java.util.List;
import java.util.Optional;

/**
 * Runs kept in the control-plane database ({@code oc_run}, {@code oc_run_step}, migration V3), shared by every
 * runtime on it.
 *
 * <p>Every change of a run's state runs under {@link ControlPlaneStore#locked}, the lock the runtimes already take for
 * definitions, so the rules that depend on the other runs (one run per job, the caps) hold across runtimes, and a
 * run's history is numbered without gaps or duplicates. Heartbeats only touch their own rows and take no lock. Runs
 * never change the store's revision: they are not definitions, and no definition cache depends on them.
 */
public class JdbcRunStore implements RunStore {

    private static final String COLUMNS =
            "execution_id, job_id, caller, runtime_id, state, stop_requested, started_at, heartbeat_at, ended_at";
    private static final String ACTIVE = "ended_at IS NULL";
    private static final int MAX_DETAIL = 1024;
    private static final int IN_BATCH = 500;

    private static final RowMapper<RunRecord> RUN = (rs, row) -> new RunRecord(
            rs.getString("execution_id"), rs.getString("job_id"), rs.getString("caller"), rs.getString("runtime_id"),
            RunState.valueOf(rs.getString("state")), rs.getBoolean("stop_requested"),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("heartbeat_at")),
            instant(rs.getTimestamp("ended_at")));

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
            jdbc.update("INSERT INTO oc_run (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    executionId, jobId, caller, runtimeId, RunState.RUNNING.name(), false, ts(now), ts(now));
            record(executionId, RunEvent.STARTED, now, runtimeId, null);
            return new Claimed(new RunRecord(executionId, jobId, caller, runtimeId, RunState.RUNNING, false, now, now, null));
        });
    }

    @Override
    public void release(String executionId) {
        store.locked(() -> {
            jdbc.update("DELETE FROM oc_run_step WHERE execution_id = ?", executionId);
            jdbc.update("DELETE FROM oc_run WHERE execution_id = ?", executionId);
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
            String in = String.join(", ", java.util.Collections.nCopies(batch.size(), "?"));
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
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
