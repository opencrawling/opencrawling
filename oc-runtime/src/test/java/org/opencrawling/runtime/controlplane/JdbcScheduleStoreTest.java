/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import org.flywaydb.core.Flyway;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.UUID;

/** Contract test for {@link JdbcScheduleStore} backed by H2 + Flyway V5 (#197). */
class JdbcScheduleStoreTest extends ScheduleStoreContract {

    private JdbcTemplate jdbc;

    @Override
    ScheduleStore freshStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schedules-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        return runtime(jdbc);
    }

    @Override
    ScheduleStore otherRuntime() {
        return runtime(jdbc);
    }

    @Override
    ScheduleStore restarted() {
        return runtime(jdbc);
    }

    private static JdbcScheduleStore runtime(JdbcTemplate jdbc) {
        JdbcControlPlaneStore cp = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                Path.of("target", "no-data"));
        return new JdbcScheduleStore(jdbc, cp);
    }
}
