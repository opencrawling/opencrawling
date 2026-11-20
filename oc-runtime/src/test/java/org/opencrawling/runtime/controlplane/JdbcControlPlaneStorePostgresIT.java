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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC control-plane store on PostgreSQL, in the image the Compose stacks use, with the real Flyway migrations.
 * Each test gets its own schema.
 */
@Testcontainers
class JdbcControlPlaneStorePostgresIT extends JdbcControlPlaneStoreContract {

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"))
            .withEnv("POSTGRES_DB", "opencrawling")
            .withEnv("POSTGRES_USER", "opencrawling")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    private static DriverManagerDataSource dataSource(String schema) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/opencrawling"
                + (schema == null ? "" : "?currentSchema=" + schema);
        return new DriverManagerDataSource(url, "opencrawling", "test-only");
    }

    @Override
    JdbcTemplate freshDatabase() {
        String schema = "cp_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource(null)).execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = dataSource(schema);
        Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    @Override
    JdbcControlPlaneStore store(JdbcTemplate jdbc, Path dataDir) {
        return new JdbcControlPlaneStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                dataDir);
    }

    /** A database that already holds other tables, like the vector store's, gets V1 too (baseline below it). */
    @Test
    void aDatabaseWithOtherTablesIsBaselinedAndMigrated() {
        String schema = "cp_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(null));
        admin.execute("CREATE SCHEMA " + schema);
        admin.execute("CREATE TABLE " + schema + ".vector_store (id TEXT PRIMARY KEY)");

        Flyway.configure().dataSource(dataSource(schema)).schemas(schema).baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();

        assertThat(admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'oc_job'",
                Integer.class, schema)).isEqualTo(1);
    }
}
