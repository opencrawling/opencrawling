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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The JDBC idempotency store on H2, the runtime's default database, with the real Flyway migrations. */
class JdbcIdempotencyStoreTest extends JdbcIdempotencyStoreContract {

    @Override
    JdbcTemplate freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:idempotency-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    @Test
    void anUnreachableDatabaseMakesTheStoreUnavailableButNeverFailsASucceededRequest() {
        JdbcTemplate down = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:tcp://127.0.0.1:1/nowhere", "sa", ""));
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(down, JsonMapper.builder().build(),
                Duration.ofHours(1), Duration.ofMinutes(1));

        assertThatThrownBy(() -> store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.UnavailableException.class);
        store.complete("alice", "k", "f", new IdempotencyStore.StoredResponse(200, Map.of(), new byte[0]));
        store.completeWithoutAnswer("alice", "k", "f", 200);
        store.abandon("alice", "k");
    }
}
