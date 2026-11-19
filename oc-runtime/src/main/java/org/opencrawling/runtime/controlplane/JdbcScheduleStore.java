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

import org.opencrawling.runtime.scheduler.DispatchOutcome;
import org.opencrawling.runtime.scheduler.JobScheduleState;
import org.opencrawling.runtime.scheduler.ScheduleDispatchRecord;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-backed {@link ScheduleStore} ({@code oc_schedule_state}, {@code oc_schedule_dispatch}, Flyway V5)
 * shared by every runtime replica on the control-plane database (#197, #315).
 */
public class JdbcScheduleStore implements ScheduleStore {

    private static final int MAX_DETAIL = 1024;

    private static final RowMapper<JobScheduleState> STATE = (rs, row) -> {
        String outcomeRaw = rs.getString("last_outcome");
        return new JobScheduleState(
                rs.getString("job_id"),
                rs.getBoolean("enabled"),
                instant(rs.getTimestamp("next_fire_at")),
                instant(rs.getTimestamp("last_fired_at")),
                rs.getString("last_execution_id"),
                outcomeRaw != null ? DispatchOutcome.valueOf(outcomeRaw) : null,
                rs.getString("last_detail"),
                rs.getBoolean("coalesce_pending"),
                rs.getInt("consecutive_failures"),
                instant(rs.getTimestamp("updated_at")));
    };

    private static final RowMapper<ScheduleDispatchRecord> DISPATCH = (rs, row) -> new ScheduleDispatchRecord(
            rs.getString("id"),
            rs.getString("job_id"),
            instant(rs.getTimestamp("scheduled_for")),
            instant(rs.getTimestamp("dispatched_at")),
            rs.getString("runtime_id"),
            rs.getString("execution_id"),
            DispatchOutcome.valueOf(rs.getString("outcome")),
            rs.getString("detail"));

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore lockStore;

    public JdbcScheduleStore(JdbcTemplate jdbc, ControlPlaneStore lockStore) {
        this.jdbc = jdbc;
        this.lockStore = lockStore;
    }

    @Override
    public boolean shared() {
        return true;
    }

    @Override
    public Optional<JobScheduleState> getState(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        List<JobScheduleState> rows = jdbc.query(
                "SELECT job_id, enabled, next_fire_at, last_fired_at, last_execution_id, last_outcome, "
                        + "last_detail, coalesce_pending, consecutive_failures, updated_at "
                        + "FROM oc_schedule_state WHERE job_id = ?",
                STATE, jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Map<String, JobScheduleState> getAllStates() {
        List<JobScheduleState> rows = jdbc.query(
                "SELECT job_id, enabled, next_fire_at, last_fired_at, last_execution_id, last_outcome, "
                        + "last_detail, coalesce_pending, consecutive_failures, updated_at "
                        + "FROM oc_schedule_state",
                STATE);
        Map<String, JobScheduleState> result = new LinkedHashMap<>();
        for (JobScheduleState row : rows) {
            result.put(row.jobId(), row);
        }
        return result;
    }

    @Override
    public void saveState(JobScheduleState state) {
        lockStore.locked(() -> {
            String outcome = state.lastOutcome() != null ? state.lastOutcome().name() : null;
            String detail = trimDetail(state.lastDetail());
            int updated = jdbc.update(
                    "UPDATE oc_schedule_state SET enabled = ?, next_fire_at = ?, last_fired_at = ?, "
                            + "last_execution_id = ?, last_outcome = ?, last_detail = ?, coalesce_pending = ?, "
                            + "consecutive_failures = ?, updated_at = ? WHERE job_id = ?",
                    state.enabled(), ts(state.nextFireAt()), ts(state.lastFiredAt()),
                    state.lastExecutionId(), outcome, detail, state.coalescePending(),
                    state.consecutiveFailures(), ts(state.updatedAt()), state.jobId());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO oc_schedule_state (job_id, enabled, next_fire_at, last_fired_at, "
                                + "last_execution_id, last_outcome, last_detail, coalesce_pending, "
                                + "consecutive_failures, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        state.jobId(), state.enabled(), ts(state.nextFireAt()), ts(state.lastFiredAt()),
                        state.lastExecutionId(), outcome, detail, state.coalescePending(),
                        state.consecutiveFailures(), ts(state.updatedAt()));
            }
            return null;
        });
    }

    @Override
    public void deleteState(String jobId) {
        if (jobId == null) {
            return;
        }
        lockStore.locked(() -> {
            jdbc.update("DELETE FROM oc_schedule_state WHERE job_id = ?", jobId);
            return null;
        });
    }

    @Override
    public List<JobScheduleState> findDue(Instant now, int limit) {
        int cap = Math.max(1, limit);
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT job_id, enabled, next_fire_at, last_fired_at, last_execution_id, last_outcome, "
                            + "last_detail, coalesce_pending, consecutive_failures, updated_at "
                            + "FROM oc_schedule_state WHERE enabled = ? AND next_fire_at IS NOT NULL "
                            + "AND next_fire_at <= ? ORDER BY next_fire_at ASC, job_id ASC");
            ps.setBoolean(1, true);
            ps.setTimestamp(2, ts(now));
            ps.setMaxRows(cap);
            return ps;
        }, STATE);
    }

    @Override
    public void recordDispatch(ScheduleDispatchRecord dispatch) {
        lockStore.locked(() -> {
            jdbc.update(
                    "INSERT INTO oc_schedule_dispatch (id, job_id, scheduled_for, dispatched_at, runtime_id, "
                            + "execution_id, outcome, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    dispatch.id(), dispatch.jobId(), ts(dispatch.scheduledFor()), ts(dispatch.dispatchedAt()),
                    dispatch.runtimeId(), dispatch.executionId(), dispatch.outcome().name(),
                    trimDetail(dispatch.detail()));
            return null;
        });
    }

    @Override
    public List<ScheduleDispatchRecord> listDispatches(String jobId) {
        return jdbc.query(
                "SELECT id, job_id, scheduled_for, dispatched_at, runtime_id, execution_id, outcome, detail "
                        + "FROM oc_schedule_dispatch WHERE job_id = ? ORDER BY dispatched_at DESC, id DESC",
                DISPATCH, jobId);
    }

    @Override
    public int countDispatchesSince(Instant since, List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM oc_schedule_dispatch WHERE dispatched_at >= ?", Integer.class, ts(since));
            return count == null ? 0 : count;
        }
        String placeholders = String.join(",", outcomes.stream().map(o -> "?").toList());
        Object[] args = new Object[outcomes.size() + 1];
        args[0] = ts(since);
        for (int i = 0; i < outcomes.size(); i++) {
            args[i + 1] = outcomes.get(i).name();
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oc_schedule_dispatch WHERE dispatched_at >= ? AND outcome IN (" + placeholders + ")",
                Integer.class, args);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteDispatchesBefore(Instant cutoff) {
        return lockStore.locked(() ->
                jdbc.update("DELETE FROM oc_schedule_dispatch WHERE dispatched_at < ?", ts(cutoff)));
    }

    private static String trimDetail(String detail) {
        return detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
