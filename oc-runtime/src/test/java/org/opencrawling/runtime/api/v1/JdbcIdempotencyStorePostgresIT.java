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
package org.opencrawling.runtime.api.v1;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/** The JDBC idempotency store on PostgreSQL, in the image the Compose stacks use. Each test gets its own schema. */
@Testcontainers
class JdbcIdempotencyStorePostgresIT extends JdbcIdempotencyStoreContract {

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
        String schema = "idem_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource(null)).execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource dataSource = dataSource(schema);
        Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
        return new JdbcTemplate(dataSource);
    }
}
