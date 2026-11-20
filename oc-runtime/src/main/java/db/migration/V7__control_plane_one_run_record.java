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
package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One run record for every kind of run (#316): dynamic jobs move from {@code oc_dynamic_job} (V6) into {@code oc_run}
 * (V3), where a run is now one row from the moment it is asked for to its end.
 *
 * <ul>
 *   <li>{@code oc_run} gains {@code kind} ({@code JOB}, {@code DYNAMIC_FOLDER}, {@code DYNAMIC_DOCUMENT}),
 *   {@code owner}, {@code created_at}, {@code position} (the order runs were stored in) and {@code body} (the record of
 *   what was asked for). {@code runtime_id}, {@code started_at} and {@code heartbeat_at} may be {@code NULL}: a run
 *   waiting for approval has not started anywhere.</li>
 *   <li>Each dynamic job becomes the run with its execution id: a job that ran updates its existing run, keeping the
 *   job's own final state (more precise than the run's); one that never ran (waiting for approval, rejected, expired,
 *   cancelled) is inserted, with its history.</li>
 *   <li>{@code oc_dynamic_job} is dropped; the idempotency keys ({@code oc_dynamic_job_key}) stay, by job id.</li>
 * </ul>
 *
 * <p>A Java migration because the job's execution id is inside its JSON record, and H2 and PostgreSQL read JSON
 * differently. Plain SQL otherwise, the same on both.
 */
public class V7__control_plane_one_run_record extends BaseJavaMigration {

    static final String MIGRATED_BY = "migration-V7";
    private static final Set<String> FINAL = Set.of("COMPLETED", "STOPPED", "FAILED", "INTERRUPTED", "REJECTED", "EXPIRED");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE oc_run ADD COLUMN kind VARCHAR(32) DEFAULT 'JOB' NOT NULL");
            sql.execute("ALTER TABLE oc_run ADD COLUMN owner VARCHAR(512)");
            sql.execute("ALTER TABLE oc_run ADD COLUMN created_at TIMESTAMP WITH TIME ZONE");
            sql.execute("ALTER TABLE oc_run ADD COLUMN position BIGINT");
            sql.execute("ALTER TABLE oc_run ADD COLUMN body TEXT");
            sql.execute("ALTER TABLE oc_run ALTER COLUMN runtime_id DROP NOT NULL");
            sql.execute("ALTER TABLE oc_run ALTER COLUMN started_at DROP NOT NULL");
            sql.execute("ALTER TABLE oc_run ALTER COLUMN heartbeat_at DROP NOT NULL");
            sql.execute("UPDATE oc_run SET created_at = started_at");
        }
        long position = numberJobRuns(connection);
        moveDynamicJobs(connection, position);
        try (Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE oc_dynamic_job");
            sql.execute("ALTER TABLE oc_run ALTER COLUMN created_at SET NOT NULL");
            sql.execute("CREATE INDEX oc_run_requested ON oc_run (kind, owner, state)");
            sql.execute("CREATE INDEX oc_run_created ON oc_run (created_at)");
            sql.execute("CREATE INDEX oc_run_position ON oc_run (position)");
        }
    }

    /** Numbers the job runs in the order they started; returns the last number used. */
    private static long numberJobRuns(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT execution_id FROM oc_run ORDER BY started_at, execution_id")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        long position = 0;
        try (PreparedStatement update = connection.prepareStatement("UPDATE oc_run SET position = ? WHERE execution_id = ?")) {
            for (String id : ids) {
                update.setLong(1, ++position);
                update.setString(2, id);
                update.executeUpdate();
            }
        }
        return position;
    }

    private record DynamicJob(String jobId, String owner, String state, Timestamp createdAt, Timestamp finishedAt,
                              String body) {}

    private static void moveDynamicJobs(Connection connection, long position) throws SQLException {
        List<DynamicJob> jobs = new ArrayList<>();
        try (Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT job_id, owner, state, created_at, finished_at, body"
                     + " FROM oc_dynamic_job ORDER BY position")) {
            while (rows.next()) {
                jobs.add(new DynamicJob(rows.getString(1), rows.getString(2), rows.getString(3), rows.getTimestamp(4),
                        rows.getTimestamp(5), rows.getString(6)));
            }
        }
        for (DynamicJob job : jobs) {
            JsonNode record = JSON.readTree(job.body());
            String executionId = text(record, "executionId");
            String kind = "DOCUMENT".equals(text(record, "kind")) ? "DYNAMIC_DOCUMENT" : "DYNAMIC_FOLDER";
            position++;
            if (exists(connection, executionId)) {
                ranAlready(connection, job, executionId, kind, position);
            } else {
                neverRan(connection, job, executionId, kind, position);
            }
        }
    }

    /** The job's run exists: it becomes the job's record, with the job's own final state when it has one. */
    private static void ranAlready(Connection connection, DynamicJob job, String executionId, String kind, long position)
            throws SQLException {
        boolean ended = FINAL.contains(job.state());
        try (PreparedStatement update = connection.prepareStatement(ended
                ? "UPDATE oc_run SET kind = ?, owner = ?, created_at = ?, position = ?, body = ?, state = ?,"
                        + " ended_at = COALESCE(ended_at, ?) WHERE execution_id = ?"
                : "UPDATE oc_run SET kind = ?, owner = ?, created_at = ?, position = ?, body = ? WHERE execution_id = ?")) {
            update.setString(1, kind);
            update.setString(2, job.owner());
            update.setTimestamp(3, job.createdAt());
            update.setLong(4, position);
            update.setString(5, job.body());
            if (ended) {
                update.setString(6, job.state());
                update.setTimestamp(7, job.finishedAt() != null ? job.finishedAt() : Timestamp.from(Instant.now()));
                update.setString(8, executionId);
            } else {
                update.setString(6, executionId);
            }
            update.executeUpdate();
        }
    }

    /** The job never started: it is inserted as the run it now is, waiting or ended, with its history. */
    private static void neverRan(Connection connection, DynamicJob job, String executionId, String kind, long position)
            throws SQLException {
        // A job recorded as running with no run left had lost its runtime.
        String state = "RUNNING".equals(job.state()) || "PAUSED".equals(job.state()) ? "INTERRUPTED" : job.state();
        boolean ended = FINAL.contains(state);
        Timestamp endedAt = ended ? (job.finishedAt() != null ? job.finishedAt() : Timestamp.from(Instant.now())) : null;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO oc_run (execution_id, job_id, caller,"
                + " runtime_id, state, stop_requested, started_at, heartbeat_at, ended_at, kind, owner, created_at,"
                + " position, body) VALUES (?, ?, NULL, NULL, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, executionId);
            insert.setString(2, job.jobId());
            insert.setString(3, state);
            insert.setBoolean(4, false);
            insert.setTimestamp(5, endedAt);
            insert.setString(6, kind);
            insert.setString(7, job.owner());
            insert.setTimestamp(8, job.createdAt());
            insert.setLong(9, position);
            insert.setString(10, job.body());
            insert.executeUpdate();
        }
        int seq = 0;
        step(connection, executionId, ++seq, "PENDING_APPROVAL".equals(state) || ended && !"INTERRUPTED".equals(state)
                ? "APPROVAL_REQUESTED" : "QUEUED", job.createdAt());
        if (ended) {
            step(connection, executionId, ++seq, state, endedAt);
        }
    }

    private static void step(Connection connection, String executionId, int seq, String event, Timestamp at)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO oc_run_step (execution_id, seq, event,"
                + " occurred_at, runtime_id, detail) VALUES (?, ?, ?, ?, ?, NULL)")) {
            insert.setString(1, executionId);
            insert.setInt(2, seq);
            insert.setString(3, event);
            insert.setTimestamp(4, at);
            insert.setString(5, MIGRATED_BY);
            insert.executeUpdate();
        }
    }

    private static boolean exists(Connection connection, String executionId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM oc_run WHERE execution_id = ?")) {
            query.setString(1, executionId);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
