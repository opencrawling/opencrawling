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
package org.opencrawling.runtime.dynamicjob;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.JobRequest;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Kind;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.StartView;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;
import org.opencrawling.runtime.dynamicjob.RunDynamicJobStore.StoredJob;
import org.opencrawling.runtime.dynamicjob.RunDynamicJobStore.StoredPending;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.orchestrator.RunStore.RunEvent;
import org.opencrawling.runtime.orchestrator.RunStore.RunStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FAKE;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.FILES;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.UNLISTED;
import static org.opencrawling.runtime.dynamicjob.DynamicJobServiceTest.caller;

/**
 * Migration V7 (#316, part 2) on a database that holds dynamic jobs in {@code oc_dynamic_job} (V6): each becomes the
 * run with its execution id, and a job still waiting for approval can be approved after the upgrade. On H2 here; the
 * PostgreSQL IT runs the same.
 */
class OneRunRecordMigrationTest {

    static final Instant T0 = Instant.parse("2026-11-18T09:00:00Z");

    /** An empty database to migrate. */
    DataSource emptyDatabase() {
        return new DriverManagerDataSource("jdbc:h2:mem:v7-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** Flyway on {@code dataSource}, as the runtime configures it. */
    Flyway flyway(DataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource);
        return (target == null ? config : config.target(target)).load();
    }

    @Test
    void theDynamicJobsOfV6BecomeRunsAndAWaitingOneCanStillBeApproved() {
        DataSource dataSource = emptyDatabase();
        flyway(dataSource, "6").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jobRun(jdbc, "exec-job", "job-1", "COMPLETED", T0.minusSeconds(60));
        jobRun(jdbc, "exec-f", "dyn-failed", "COMPLETED", T0.plusSeconds(20));
        jobRun(jdbc, "exec-r", "dyn-running", "RUNNING", T0.plusSeconds(30));
        dynamicJob(jdbc, 1, "dyn-pending", "exec-p", Kind.FOLDER, State.PENDING_APPROVAL, T0, null, true);
        dynamicJob(jdbc, 2, "dyn-failed", "exec-f", Kind.DOCUMENT, State.FAILED, T0.plusSeconds(10), T0.plusSeconds(25), false);
        dynamicJob(jdbc, 3, "dyn-running", "exec-r", Kind.FOLDER, State.RUNNING, T0.plusSeconds(30), null, false);
        dynamicJob(jdbc, 4, "dyn-rejected", "exec-x", Kind.FOLDER, State.REJECTED, T0.plusSeconds(40), T0.plusSeconds(50), false);
        jdbc.update("INSERT INTO oc_dynamic_job_key (id, job_id, request) VALUES (?, ?, ?)",
                RunDynamicJobStore.keyId("/alice", "key-1"), "dyn-pending", "{\"folder\":{\"connector\":\"Fake_Repo\"}}");

        flyway(dataSource, null).migrate();

        assertThatThrownBy(() -> jdbc.queryForObject("SELECT COUNT(*) FROM oc_dynamic_job", Integer.class))
                .as("the old table is gone").isNotNull();
        assertThat(row(jdbc, "exec-job")).containsEntry("KIND", "JOB").containsEntry("STATE", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT created_at FROM oc_run WHERE execution_id = 'exec-job'", Timestamp.class)
                .toInstant()).as("a job run was asked for when it started").isEqualTo(T0.minusSeconds(60));

        Map<String, Object> pending = row(jdbc, "exec-p");
        assertThat(pending).containsEntry("JOB_ID", "dyn-pending").containsEntry("STATE", "PENDING_APPROVAL")
                .containsEntry("KIND", "DYNAMIC_FOLDER").containsEntry("OWNER", "/alice");
        assertThat(pending.get("RUNTIME_ID")).isNull();
        assertThat(row(jdbc, "exec-f")).as("the job's own outcome, not the run's").containsEntry("STATE", "FAILED")
                .containsEntry("KIND", "DYNAMIC_DOCUMENT").containsEntry("OWNER", "/alice");
        assertThat(row(jdbc, "exec-r")).containsEntry("STATE", "RUNNING").containsEntry("KIND", "DYNAMIC_FOLDER");
        assertThat(row(jdbc, "exec-x")).containsEntry("STATE", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT position) FROM oc_run", Integer.class)).isEqualTo(5);

        // The runtime picks them up: a pending job's history, its key, and approving it.
        DynamicJobServiceTest fakes = new DynamicJobServiceTest();
        fakes.properties.setConnectors(List.of("fake_repo", "Files"));
        fakes.properties.setAdminGroups(List.of("oc-admins"));
        fakes.properties.setRequireApproval(true);
        JobRunRegistry registry = DynamicJobServiceJdbcTest.runtimeRegistry(jdbc, 0, "runtime-after", Clock.systemUTC());
        DynamicJobService service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES),
                dto -> fakes.builder.apply(dto), registry, fakes.runner, fakes.documentCounts::get, fakes.properties,
                fakes.clock, DynamicJobServiceJdbcTest.runtimeStore(jdbc, registry, fakes.clock));
        try {
            assertThat(service.list(caller("alice"))).extracting(DynamicJobService.JobStatus::jobId)
                    .containsExactlyInAnyOrder("dyn-pending", "dyn-failed", "dyn-running", "dyn-rejected");
            assertThat(service.history(caller("alice"), "dyn-rejected")).extracting(RunStep::event)
                    .containsExactly(RunEvent.APPROVAL_REQUESTED, RunEvent.REJECTED);
            assertThat(service.start(caller("alice"), new JobRequest("Fake_Repo", null, null), "key-1", "mcp", null).replayed())
                    .as("its idempotency key still answers").isTrue();

            service.approve(caller("carol", "oc-admins"), "dyn-pending");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!registry.runs().isEmpty() || service.status(caller("alice"), "dyn-pending").state() == State.RUNNING) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the approved job did not end");
                }
                Thread.onSpinWait();
            }
            assertThat(service.status(caller("alice"), "dyn-pending").state()).isEqualTo(State.COMPLETED);
            assertThat(fakes.runner.provenances).as("it ran for alice, the identity kept across the upgrade")
                    .anySatisfy(provenance -> assertThat(provenance.triggeredBy()).isEqualTo("alice"));
            assertThat(service.history(caller("alice"), "dyn-pending")).extracting(RunStep::event)
                    .containsExactly(RunEvent.APPROVAL_REQUESTED, RunEvent.APPROVED, RunEvent.STARTED, RunEvent.COMPLETED);
        } finally {
            fakes.runner.hold.countDown();
        }
    }

    private static void jobRun(JdbcTemplate jdbc, String executionId, String jobId, String state, Instant startedAt) {
        boolean ended = !state.equals("RUNNING") && !state.equals("PAUSED");
        jdbc.update("INSERT INTO oc_run (execution_id, job_id, caller, runtime_id, state, stop_requested, started_at,"
                        + " heartbeat_at, ended_at) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?)",
                executionId, jobId, "runtime-before", state, false, Timestamp.from(startedAt), Timestamp.from(startedAt),
                ended ? Timestamp.from(startedAt.plusSeconds(5)) : null);
        jdbc.update("INSERT INTO oc_run_step (execution_id, seq, event, occurred_at, runtime_id, detail) VALUES (?, 1, 'STARTED', ?, ?, NULL)",
                executionId, Timestamp.from(startedAt), "runtime-before");
    }

    /** A dynamic job as V6 stored it, its record written by the same codec. */
    private static void dynamicJob(JdbcTemplate jdbc, long position, String jobId, String executionId, Kind kind,
                                   State state, Instant createdAt, Instant finishedAt, boolean pending) {
        StoredPending request = pending ? new StoredPending(caller("alice"), "/alice", "alice",
                ExecutionProvenance.verified("alice", null, List.of(), null, "mcp", null), "Fake_Repo",
                new JobRequest("Fake_Repo", null, null), null) : null;
        StoredJob job = new StoredJob(jobId, executionId, "Job " + jobId, "Fake_Repo", kind,
                new StartView("root", "root", "/"), "/alice", "alice", "mcp", createdAt, null, state, false, finishedAt,
                null, request, null, state == State.REJECTED ? "Rejected by carol." : null);
        jdbc.update("INSERT INTO oc_dynamic_job (job_id, position, owner, state, created_at, finished_at, body)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", jobId, position, "/alice", state.name(), Timestamp.from(createdAt),
                finishedAt == null ? null : Timestamp.from(finishedAt), RunDynamicJobStore.JSON.writeValueAsString(job));
    }

    /** The run's columns, named in upper case whatever the database calls them. */
    private static Map<String, Object> row(JdbcTemplate jdbc, String executionId) {
        Map<String, Object> row = new java.util.HashMap<>();
        jdbc.queryForMap("SELECT job_id, state, kind, owner, runtime_id FROM oc_run WHERE execution_id = ?", executionId)
                .forEach((column, value) -> row.put(column.toUpperCase(java.util.Locale.ROOT), value));
        return row;
    }
}
