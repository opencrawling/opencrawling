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
package org.opencrawling.runtime.dynamicjob;

import org.flywaydb.core.Flyway;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcRunStore;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Every case of {@link DynamicJobServiceTest}, with the jobs kept in the control-plane database (H2, the real Flyway
 * migrations) instead of memory: the service behaves the same on either store.
 */
class DynamicJobServiceJdbcTest extends DynamicJobServiceTest {

    /** One database per test; a service created again in a test (a restart) finds the same jobs. */
    final JdbcTemplate jdbc = database();

    static JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dynamic-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    /** The control-plane store of one runtime on {@code jdbc}: its own lock objects, sharing only the database. */
    static JdbcControlPlaneStore controlPlane(JdbcTemplate jdbc) {
        return new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), Path.of("target", "no-data"));
    }

    /** The run registry of one runtime on {@code jdbc}, whose run store keeps the dynamic jobs too (#316). */
    static JobRunRegistry runtimeRegistry(JdbcTemplate jdbc, int maxConcurrentRuns, String runtimeId, Clock clock) {
        return new JobRunRegistry(maxConcurrentRuns, 0, new JdbcRunStore(jdbc, controlPlane(jdbc)), runtimeId,
                Duration.ofSeconds(60), clock);
    }

    /** The jobs of one runtime on {@code jdbc}: kept as runs in its registry's store, keys in the database. */
    static DynamicJobStore runtimeStore(JdbcTemplate jdbc, JobRunRegistry registry, Clock clock) {
        return new RunDynamicJobStore(registry.store(), RunDynamicJobStore.Keys.jdbc(jdbc), registry::runtimeId, clock);
    }

    @Override
    JobRunRegistry newRegistry(int maxConcurrentRuns) {
        // The system clock, as the in-memory registry of the base test: the test clock jumps ahead by hours.
        return runtimeRegistry(jdbc, maxConcurrentRuns, "runtime-test", Clock.systemUTC());
    }

    @Override
    void service() {
        service = new DynamicJobService(() -> List.of(FAKE, UNLISTED, FILES), dto -> builder.apply(dto), registry,
                runner, documentCounts::get, properties, clock, runtimeStore(jdbc, registry, clock));
    }
}
