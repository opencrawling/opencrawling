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
package org.opencrawling.runtime.api.v1;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * With opencrawling.control-plane.store=jdbc (#315), definitions saved through the API are rows in the runtime's
 * database (here H2, migrated by Flyway at startup), and a controller built on the same database reads them back,
 * as one would after a restart.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1JdbcStoreHttpTest.Database.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "opencrawling.control-plane.store=jdbc"})
class PlatformApiV1JdbcStoreHttpTest {

    /** A real database for the store (the shared support mocks JdbcTemplate), migrated as the runtime's Flyway does. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        JdbcTemplate controlPlaneDatabase() {
            org.springframework.jdbc.datasource.DriverManagerDataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:control-plane-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
            return new JdbcTemplate(dataSource);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ControlPlaneStore store;

    @Test
    void definitionsSavedThroughTheApiAreRowsThatARestartedRuntimeReads() {
        assertThat(store).isInstanceOf(JdbcControlPlaneStore.class);
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String name = "Jdbc_" + UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> created = http.send("POST", "/api/v1/connectors", "{\"name\":\"" + name
                + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\",\"configuration\":{\"basePath\":\"/data/jdbc\"}}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        HttpResponse<String> job = http.send("POST", "/api/v1/jobs", "{\"name\":\"job-" + name + "\",\"repositoryConnector\":\""
                + name + "\",\"outputConnector\":\"PGVector_Output\",\"path\":\"/data/jdbc\"}");
        assertThat(job.statusCode()).as(job.body()).isEqualTo(201);
        String jobId = job.headers().firstValue("Location").orElseThrow().replace("/api/v1/jobs/", "");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_connector WHERE name = ?", Integer.class, name)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM oc_job WHERE id = ?", String.class, jobId)).contains("job-" + name);

        ConnectorController restarted = new ConnectorController(mock(org.opencrawling.runtime.service.ConnectorCheckerService.class), store);
        assertThat(restarted.connectorsWithSecrets()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo(name);
            assertThat(c.configuration()).containsEntry("basePath", "/data/jdbc");
        });

        assertThat(http.send("DELETE", "/api/v1/connectors/" + name + "?force=true", null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_connector WHERE name = ?", Integer.class, name)).isZero();
        http.send("DELETE", "/api/v1/jobs/" + jobId, null);
    }
}
