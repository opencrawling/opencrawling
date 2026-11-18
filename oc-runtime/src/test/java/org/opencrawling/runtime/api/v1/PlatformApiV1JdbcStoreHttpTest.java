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
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.api.SystemController.SystemSettingsDTO;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * With opencrawling.control-plane.store=jdbc (#315), definitions saved through the API are rows in the runtime's
 * database (here H2, migrated by Flyway at startup), and a controller built on the same database reads them back,
 * as one would after a restart. Idempotency keys are rows of the same database (opencrawling.api.idempotency.store=jdbc).
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1JdbcStoreHttpTest.Database.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "opencrawling.control-plane.store=jdbc",
                "opencrawling.api.idempotency.store=jdbc"})
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

    /** Another runtime on the same database: its own store, with its own cache (#315, part 2). */
    private JdbcControlPlaneStore otherRuntime() {
        return new JdbcControlPlaneStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                Path.of("target", "other-runtime-data"));
    }

    @Test
    void aJobChangedByAnotherRuntimeIsServedAtOnceAndAnEditFromTheOldVersionIsRefused() {
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String name = "shared-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", "{\"name\":\"" + name
                + "\",\"repositoryConnector\":\"FileSystem_Local\",\"outputConnector\":\"PGVector_Output\",\"path\":\"/data/shared\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String jobId = created.headers().firstValue("Location").orElseThrow().replace("/api/v1/jobs/", "");
        HttpResponse<String> read = http.send("GET", "/api/v1/jobs/" + jobId, null);
        String readEtag = read.headers().firstValue("ETag").orElseThrow();

        // The other runtime renames the job, the way its controllers change the list: under the shared lock.
        JdbcControlPlaneStore other = otherRuntime();
        other.locked(() -> {
            List<JobDTO> jobs = new ArrayList<>(other.loadList("jobs.json", JobDTO.class, List.of()));
            jobs.replaceAll(job -> job.id().equals(jobId) ? new JobDTO(job.id(), name + "-elsewhere", job.repositoryConnector(),
                    job.outputConnector(), job.authorityConnector(), job.path(), job.status(), job.currentStage(), job.documents(),
                    job.lastRun(), job.transformationConnector(), job.narrativization(), job.pipelineMode(), job.crawlSpec(),
                    job.access()) : job);
            other.save("jobs.json", jobs);
            return null;
        });

        HttpResponse<String> after = http.send("GET", "/api/v1/jobs/" + jobId, null);
        assertThat(after.body()).contains(name + "-elsewhere");
        String currentEtag = after.headers().firstValue("ETag").orElseThrow();
        assertThat(currentEtag).isNotEqualTo(readEtag);

        HttpResponse<String> stale = http.send("PATCH", "/api/v1/jobs/" + jobId, "{\"name\":\"lost-update\"}",
                "Content-Type", "application/merge-patch+json", "If-Match", readEtag);
        assertThat(stale.statusCode()).as("an edit made from the version before the other runtime's change").isEqualTo(412);
        HttpResponse<String> current = http.send("PATCH", "/api/v1/jobs/" + jobId, "{\"name\":\"" + name + "-here\"}",
                "Content-Type", "application/merge-patch+json", "If-Match", currentEtag);
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
        assertThat(other.loadList("jobs.json", JobDTO.class, List.of())).filteredOn(job -> job.id().equals(jobId))
                .extracting(JobDTO::name).containsExactly(name + "-here");

        http.send("DELETE", "/api/v1/jobs/" + jobId, null);
    }

    @Test
    void aConnectorDeletedByAnotherRuntimeIsGoneHereAndNotSavedBackByTheNextChange() {
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String gone = "Gone_" + UUID.randomUUID().toString().substring(0, 8);
        String kept = "Kept_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(http.send("POST", "/api/v1/connectors", "{\"name\":\"" + gone
                + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\",\"configuration\":{}}").statusCode()).isEqualTo(201);
        assertThat(http.send("GET", "/api/v1/connectors/" + gone, null).statusCode()).isEqualTo(200);

        new ConnectorController(mock(org.opencrawling.runtime.service.ConnectorCheckerService.class), otherRuntime())
                .deleteConnector(gone);

        assertThat(http.send("GET", "/api/v1/connectors/" + gone, null).statusCode()).isEqualTo(404);
        assertThat(http.send("POST", "/api/v1/connectors", "{\"name\":\"" + kept
                + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\",\"configuration\":{}}").statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_connector WHERE name = ?", Integer.class, gone))
                .as("this runtime's next save does not bring back what the other one deleted").isZero();
        http.send("DELETE", "/api/v1/connectors/" + kept, null);
    }

    @Test
    void settingsSavedByAnotherRuntimeAreServedAndApplied() {
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        JdbcControlPlaneStore other = otherRuntime();
        SystemSettingsDTO saved = other.loadObject("settings.json", SystemSettingsDTO.class, null);
        int chunkSize = saved.chunkSize() == 512 ? 640 : 512;
        SystemSettingsDTO changed = new SystemSettingsDTO(saved.embeddingProvider(), saved.ollamaBaseUrl(), saved.ollamaModel(),
                saved.vectorDimensions(), saved.chunkerType(), chunkSize, saved.chunkOverlap(), saved.claimCheckStore(),
                saved.ozoneClientType(), saved.ozoneS3Endpoint(), saved.ozoneOmHost(), saved.ozoneOmPort(), saved.ozoneVolume(),
                saved.ozoneBucket(), saved.tikaVersion(), saved.tikaEngine(), saved.tikaForkEnabled(), saved.tikaTimeoutMs(),
                saved.tikaJvmHeap(), saved.tikaMaxFilesPerProcess(), saved.tikaFallbackToEmbedded(), saved.tikaExtractEmbedded(),
                saved.tikaWriteLimit(), saved.tikaNumClients());
        other.save("settings.json", changed);

        HttpResponse<String> read = http.send("GET", "/api/v1/settings/system", null);
        assertThat(read.body()).contains("\"chunkSize\":" + chunkSize);

        org.opencrawling.runtime.orchestrator.ChunkingDefaults defaults = new org.opencrawling.runtime.orchestrator.ChunkingDefaults();
        system.setChunkingDefaults(defaults);
        system.applySavedSettings();
        assertThat(defaults.apply(null).chunkSize()).as("the chunk size jobs without their own now get").isEqualTo(chunkSize);
        other.save("settings.json", saved);
        system.applySavedSettings();
    }

    @Autowired
    org.opencrawling.runtime.api.SystemController system;

    @Autowired
    IdempotencyStore idempotency;

    @Test
    void aRetriedPostIsAnsweredFromTheDatabase() {
        assertThat(idempotency).isInstanceOf(JdbcIdempotencyStore.class);
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String name = "Idem_" + UUID.randomUUID().toString().substring(0, 8);
        String key = "create-" + name;
        String body = "{\"name\":\"" + name + "\",\"type\":\"repository\",\"className\":\"org.example.Repo\",\"configuration\":{}}";
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM oc_idempotency_key", Integer.class);

        HttpResponse<String> first = http.send("POST", "/api/v1/connectors", body, "Idempotency-Key", key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_idempotency_key", Integer.class)).isEqualTo(before + 1);

        HttpResponse<String> retry = http.send("POST", "/api/v1/connectors", body, "Idempotency-Key", key);
        assertThat(retry.statusCode()).as("not created twice: " + retry.body()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(retry.body()).isEqualTo(first.body());

        HttpResponse<String> otherBody = http.send("POST", "/api/v1/connectors", body.replace("org.example.Repo", "org.example.Other"),
                "Idempotency-Key", key);
        assertThat(otherBody.statusCode()).isEqualTo(422);

        assertThat(jdbc.queryForList("SELECT id FROM oc_idempotency_key", String.class)).as("keys are stored as digests")
                .noneSatisfy(id -> assertThat(id).contains(key));

        http.send("DELETE", "/api/v1/connectors/" + name, null);
    }
}
