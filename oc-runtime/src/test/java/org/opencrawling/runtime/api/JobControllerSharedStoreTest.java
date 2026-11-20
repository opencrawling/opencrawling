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
package org.opencrawling.runtime.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcRunStore;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two runtimes whose job controllers share one database (#315, part 2): each starts from the saved jobs, so neither
 * overwrites what the other saved.
 */
class JobControllerSharedStoreTest {

    @TempDir
    Path dataDir;

    private JdbcTemplate jdbc;

    private JobController runtime() {
        JdbcControlPlaneStore store = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), dataDir);
        return new JobController(null, null, null, null, store);
    }

    private void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:jobs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void documentsPushedThroughTwoRuntimesAtOnceAreAllCounted() throws Exception {
        freshDatabase();
        JobController first = runtime();
        JobController second = runtime();
        String jobId = first.getAllJobs().getFirst().id();
        long before = first.findJobById(jobId).documents();

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            JobController controller = t % 2 == 0 ? first : second;
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 10; i++) {
                    controller.recordPushExecution(jobId, 1, true);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(second.findJobById(jobId).documents()).isEqualTo(before + 40);
        assertThat(first.findJobById(jobId).documents()).isEqualTo(before + 40);
    }

    @Test
    void aJobDeletedByOneRuntimeIsNotBroughtBackByTheOthersNextChange() {
        freshDatabase();
        JobController first = runtime();
        JobController second = runtime();
        String jobId = first.getAllJobs().getFirst().id();
        assertThat(second.findJobById(jobId)).isNotNull();

        first.deleteJob(jobId);
        second.recordPushExecution("no-such-job", 1, true); // a change on the second runtime saves its list

        assertThat(second.findJobById(jobId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_job WHERE id = ?", Integer.class, jobId)).isZero();
    }

    @Test
    void aJobWhoseRunWasInterruptedNoLongerSaysItRuns() {
        freshDatabase();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-17T10:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        JdbcControlPlaneStore storeA = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), dataDir);
        JdbcControlPlaneStore storeB = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), dataDir);
        JobRunRegistry registryA = new JobRunRegistry(0, 0, new JdbcRunStore(jdbc, storeA), "rt-a", java.time.Duration.ofSeconds(60), clock);
        JobRunRegistry registryB = new JobRunRegistry(0, 0, new JdbcRunStore(jdbc, storeB), "rt-b", java.time.Duration.ofSeconds(60), clock);
        JobController a = new JobController(null, null, registryA, null, storeA);
        JobController b = new JobController(null, null, registryB, null, storeB);
        String jobId = a.getAllJobs().getFirst().id();
        // A run on A (its thread never starts: A's runtime is gone), paused through B.
        registryA.reserve(jobId, "exec-gone");
        a.pauseJob(jobId);
        assertThat(b.findJobById(jobId).status()).isEqualTo("Paused");

        now.set(now.get().plusSeconds(61));
        registryB.heartbeat();

        assertThat(b.findJobById(jobId).status()).isEqualTo("Error");
        assertThat(b.findJobById(jobId).currentStage()).isEqualTo("Interrupted");
        assertThat(registryB.run(jobId)).isEmpty();
    }
}
