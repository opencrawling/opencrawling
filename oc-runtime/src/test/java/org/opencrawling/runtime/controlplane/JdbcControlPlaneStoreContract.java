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

    // --- Several runtimes on one database (#315, part 2). Two stores over one database stand for two runtimes.

    @Test
    void aChangeSavedByAnotherRuntimeIsReadAtOnce() {
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore first = store(jdbc, dataDir);
        JdbcControlPlaneStore second = store(jdbc, dataDir);
        first.save("jobs.json", List.of(job("1", "one", "/a")));
        first.save("settings.json", Map.of("theme", "dark"));
        assertThat(first.loadList("jobs.json", JobDTO.class, List.of())).hasSize(1); // cached by the first runtime
        assertThat(first.loadObject("settings.json", Map.class, Map.of())).containsEntry("theme", "dark");

        second.save("jobs.json", List.of(job("1", "one", "/a"), job("2", "two", "/b")));
        second.save("settings.json", Map.of("theme", "light"));

        assertThat(first.loadList("jobs.json", JobDTO.class, List.of())).extracting(JobDTO::id).containsExactly("1", "2");
        assertThat(first.loadObject("settings.json", Map.class, Map.of())).containsEntry("theme", "light");
    }

    @Test
    void changesMadeAtTheSameTimeByTwoRuntimesAreAllKept() throws Exception {
        JdbcTemplate jdbc = freshDatabase();
        List<JdbcControlPlaneStore> runtimes = List.of(store(jdbc, dataDir), store(jdbc, dataDir));
        runtimes.getFirst().save("jobs.json", List.of());
        int perThread = 10;
        List<Thread> threads = new java.util.ArrayList<>();
        java.util.Queue<Throwable> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int t = 0; t < 4; t++) {
            JdbcControlPlaneStore store = runtimes.get(t % 2);
            String prefix = "t" + t + "-";
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = prefix + i;
                        // What every controller does: read the saved list, change it, save it, under the lock.
                        store.locked(() -> {
                            List<JobDTO> jobs = store.loadList("jobs.json", JobDTO.class, List.of());
                            jobs.add(job(id, id, "/" + id));
                            store.save("jobs.json", jobs);
                            return null;
                        });
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(failures).isEmpty();
        assertThat(runtimes.get(1).loadList("jobs.json", JobDTO.class, List.of())).as("no change lost").hasSize(4 * perThread);
        assertThat(runtimes.get(0).loadList("jobs.json", JobDTO.class, List.of())).hasSize(4 * perThread);
    }

    @Test
    void aChangeThatFailsKeepsNothingItSaved() {
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore store = store(jdbc, dataDir);
        store.save("jobs.json", List.of(job("1", "one", "/a")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.locked(() -> {
            store.save("jobs.json", List.of(job("1", "one", "/a"), job("2", "two", "/b")));
            assertThat(store.loadList("jobs.json", JobDTO.class, List.of())).as("seen inside the change").hasSize(2);
            throw new IllegalStateException("refused");
        })).hasMessage("refused");

        assertThat(store.loadList("jobs.json", JobDTO.class, List.of())).extracting(JobDTO::id).containsExactly("1");
        assertThat(store(jdbc, dataDir).loadList("jobs.json", JobDTO.class, List.of())).extracting(JobDTO::id).containsExactly("1");
    }

    @Test
    void twoRuntimesStartingTogetherKeepTheSameFirstValue() throws Exception {
        JdbcTemplate jdbc = freshDatabase();
        JdbcControlPlaneStore first = store(jdbc, dataDir);
        JdbcControlPlaneStore second = store(jdbc, dataDir);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> fromFirst = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> fromSecond = new java.util.concurrent.atomic.AtomicReference<>();
        Thread a = Thread.ofVirtual().start(() -> { await(go); fromFirst.set(first.loadObject("key.json", String.class, "made-by-first")); });
        Thread b = Thread.ofVirtual().start(() -> { await(go); fromSecond.set(second.loadObject("key.json", String.class, "made-by-second")); });
        go.countDown();
        a.join();
        b.join();

        assertThat(fromFirst.get()).isNotNull().isEqualTo(fromSecond.get());
        assertThat(store(jdbc, dataDir).loadObject("key.json", String.class, "later")).isEqualTo(fromFirst.get());
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long version(JdbcTemplate jdbc, String table, String keyColumn, String key) {
        return jdbc.queryForObject("SELECT version FROM " + table + " WHERE " + keyColumn + " = ?", Long.class, key);
    }
}
