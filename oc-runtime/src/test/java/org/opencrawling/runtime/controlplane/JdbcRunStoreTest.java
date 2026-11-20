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
package org.opencrawling.runtime.controlplane;

import org.flywaydb.core.Flyway;
import org.opencrawling.runtime.orchestrator.RunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Runs in the control-plane database on H2, the runtime's default database, with the real Flyway migrations. Each
 * runtime has its own stores (its own Java locks) on the one database, as separate processes would.
 */
class JdbcRunStoreTest extends RunStoreContract {

    private JdbcTemplate jdbc;

    @Override
    RunStore freshStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        return runtime(jdbc);
    }

    @Override
    RunStore otherRuntime() {
        return runtime(jdbc);
    }

    @Override
    RunStore restarted() {
        return runtime(jdbc);
    }

    static RunStore runtime(JdbcTemplate jdbc) {
        JdbcControlPlaneStore store = new JdbcControlPlaneStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), Path.of("target", "no-data"));
        return new JdbcRunStore(jdbc, store);
    }
}
