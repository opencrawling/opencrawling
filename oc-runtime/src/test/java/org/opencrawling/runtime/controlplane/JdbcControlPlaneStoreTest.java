/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.UUID;

/** The JDBC control-plane store on H2, the runtime's default database, with the real Flyway migrations. */
class JdbcControlPlaneStoreTest extends JdbcControlPlaneStoreContract {

    private DriverManagerDataSource dataSource;

    @Override
    JdbcTemplate freshDatabase() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:cp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    @Override
    JdbcControlPlaneStore store(JdbcTemplate jdbc, Path dataDir) {
        return new JdbcControlPlaneStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                dataDir);
    }
}
