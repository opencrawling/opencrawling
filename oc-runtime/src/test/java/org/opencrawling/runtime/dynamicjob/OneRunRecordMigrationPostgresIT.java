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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;

/** {@link OneRunRecordMigrationTest} on a real PostgreSQL: migration V7 moves the dynamic jobs of V6 the same way. */
@Testcontainers
class OneRunRecordMigrationPostgresIT extends OneRunRecordMigrationTest {

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"))
            .withEnv("POSTGRES_DB", "opencrawling")
            .withEnv("POSTGRES_USER", "opencrawling")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    private String schema;

    private static DriverManagerDataSource dataSource(String schema) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/opencrawling"
                + (schema == null ? "" : "?currentSchema=" + schema);
        return new DriverManagerDataSource(url, "opencrawling", "test-only");
    }

    @Override
    DataSource emptyDatabase() {
        schema = "v7_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource(null)).execute("CREATE SCHEMA " + schema);
        return dataSource(schema);
    }

    @Override
    Flyway flyway(DataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema);
        return (target == null ? config : config.target(target)).load();
    }
}
