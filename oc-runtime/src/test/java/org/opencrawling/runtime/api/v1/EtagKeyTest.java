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
package org.opencrawling.runtime.api.v1;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The key of the connectors' ETags is the same in every runtime that shares the store (#315, part 2). */
class EtagKeyTest {

    @TempDir
    Path dataDir;

    private static JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:etag-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private static JdbcControlPlaneStore store(JdbcTemplate jdbc, Path dataDir) {
        return new JdbcControlPlaneStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), dataDir);
    }

    @Test
    void twoRuntimesOnOneDatabaseUseTheSameKey() {
        JdbcTemplate jdbc = database();

        byte[] first = EtagKey.get(store(jdbc, dataDir));
        byte[] second = EtagKey.get(store(jdbc, dataDir));

        assertThat(first).hasSizeGreaterThanOrEqualTo(32).isEqualTo(second);
    }

    @Test
    void theKeyOfAFileBasedRuntimeIsKeptSoItsTagsStayValid() throws Exception {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Files.writeString(dataDir.resolve(EtagKey.NAME), "\"" + Base64.getEncoder().encodeToString(key) + "\"");

        assertThat(EtagKey.get(store(database(), dataDir))).isEqualTo(key);
    }
}
