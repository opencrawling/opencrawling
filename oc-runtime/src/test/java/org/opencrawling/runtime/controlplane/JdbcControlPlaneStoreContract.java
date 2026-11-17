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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the JDBC control-plane store must do on every database (#315); run on H2 and on PostgreSQL. Each test gets a
 * fresh, migrated database from {@link #freshDatabase()}.
 */
abstract class JdbcControlPlaneStoreContract {

    @TempDir
    Path dataDir;

    /** A database with the Flyway migrations applied and no rows. */
    abstract JdbcTemplate freshDatabase();

    abstract JdbcControlPlaneStore store(JdbcTemplate jdbc, Path dataDir);

    private static JobDTO job(String id, String name, String path) {
        return new JobDTO(id, name, "FileSystem_Local", "PGVector_Output", "", path, "Ready", "Idle", 0, "N/A",
                "Ollama_Embedding_Default", null);
    }

    private static ConnectorDTO connector(String name, String basePath) {
        return new ConnectorDTO(name, name, "repository", "org.example.Repo", 10, Map.of("basePath", basePath));
    }

    @Test
    void definitionsSurviveARestartInTheirOrder() {
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore first = store(jdbc, dataDir);
        first.save("jobs.json", List.of(job("2", "second", "/b"), job("1", "first", "/a")));
        first.save("connectors.json", List.of(connector("Zeta", "/z"), connector("Alpha", "/a")));
        first.save("settings.json", Map.of("theme", "dark"));

        JdbcControlPlaneStore restarted = store(jdbc, dataDir);
        assertThat(restarted.loadList("jobs.json", JobDTO.class, List.of())).extracting(JobDTO::id).containsExactly("2", "1");
        assertThat(restarted.loadList("jobs.json", JobDTO.class, List.of()).getFirst()).isEqualTo(job("2", "second", "/b"));
        assertThat(restarted.loadList("connectors.json", ConnectorDTO.class, List.of())).extracting(ConnectorDTO::name)
                .containsExactly("Zeta", "Alpha");
        assertThat(restarted.loadObject("settings.json", Map.class, Map.of())).containsEntry("theme", "dark");
    }

    @Test
    void aRowsVersionGrowsOnlyWhenItChangesAndRemovedDefinitionsAreDeleted() {
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore store = store(jdbc, dataDir);
        store.save("connectors.json", List.of(connector("A", "/a"), connector("B", "/b")));
        store.save("connectors.json", List.of(connector("B", "/b"), connector("A", "/a2")));

        assertThat(version(jdbc, "oc_connector", "name", "A")).as("A changed").isEqualTo(2);
        assertThat(version(jdbc, "oc_connector", "name", "B")).as("B only moved").isEqualTo(1);
        assertThat(store.loadList("connectors.json", ConnectorDTO.class, List.of())).extracting(ConnectorDTO::name)
                .containsExactly("B", "A");

        store.save("connectors.json", List.of(connector("B", "/b")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oc_connector", Integer.class)).isEqualTo(1);

        store.save("settings.json", Map.of("theme", "dark"));
        store.save("settings.json", Map.of("theme", "dark"));
        assertThat(version(jdbc, "oc_setting", "name", "settings.json")).as("saved twice, unchanged").isEqualTo(1);
        store.save("settings.json", Map.of("theme", "light"));
        assertThat(version(jdbc, "oc_setting", "name", "settings.json")).isEqualTo(2);
    }

    @Test
    void anEmptiedListStaysEmptyAndIsNotRefilledWithTheDefaults() {
        JdbcControlPlaneStore store = store(freshDatabase(), dataDir);
        List<JobDTO> defaults = List.of(job("1", "Default_Job", "/data"));
        assertThat(store.loadList("jobs.json", JobDTO.class, defaults)).as("first start: the defaults").isEqualTo(defaults);

        store.save("jobs.json", List.of());

        assertThat(store.loadList("jobs.json", JobDTO.class, defaults)).isEmpty();
    }

    @Test
    void theFirstStartImportsTheFilesOfAFileBasedRuntimeAndLeavesThemUnchanged() throws Exception {
        String jobs = "[{\"id\":\"7\",\"name\":\"imported\",\"repositoryConnector\":\"FileSystem_Local\",\"outputConnector\":"
                + "\"PGVector_Output\",\"authorityConnector\":\"\",\"path\":\"/legacy\",\"status\":\"Ready\",\"currentStage\":\"Idle\","
                + "\"documents\":0,\"lastRun\":\"N/A\",\"transformationConnector\":\"Ollama_Embedding_Default\"}]";
        Files.writeString(dataDir.resolve("jobs.json"), jobs);
        Files.writeString(dataDir.resolve("settings.json"), "{\"theme\":\"dark\"}");
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore store = store(jdbc, dataDir);

        List<JobDTO> loaded = store.loadList("jobs.json", JobDTO.class, List.of(job("1", "Default_Job", "/data")));

        assertThat(loaded).extracting(JobDTO::id, JobDTO::path).containsExactly(org.assertj.core.groups.Tuple.tuple("7", "/legacy"));
        assertThat(store.loadObject("settings.json", Map.class, Map.of())).containsEntry("theme", "dark");
        assertThat(Files.readString(dataDir.resolve("jobs.json"))).isEqualTo(jobs);

        Files.writeString(dataDir.resolve("jobs.json"), "[]");
        assertThat(store(jdbc, dataDir).loadList("jobs.json", JobDTO.class, List.of())).as("imported once, then the database")
                .extracting(JobDTO::id).containsExactly("7");
    }

    @Test
    void anElementWithoutAUniqueKeyIsNotSaved() {
        JdbcControlPlaneStore store = store(freshDatabase(), dataDir);
        store.save("connectors.json", List.of(connector("A", "/a"), connector("A", "/again"), connector(null, "/none")));

        assertThat(store.loadList("connectors.json", ConnectorDTO.class, List.of()))
                .extracting(c -> c.configuration().get("basePath")).containsExactly("/a");
    }

    private static long version(JdbcTemplate jdbc, String table, String keyColumn, String key) {
        return jdbc.queryForObject("SELECT version FROM " + table + " WHERE " + keyColumn + " = ?", Long.class, key);
    }
}
