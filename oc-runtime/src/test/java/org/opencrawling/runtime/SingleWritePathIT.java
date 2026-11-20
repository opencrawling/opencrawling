/*
 * Copyright © 2026 Rafa Haro (piergiorgio@apache.org)
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
package org.opencrawling.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A job run the way the job API runs it, with the runtime's active output connector, stores each chunk of a document
 * once. Every pgvector table is counted, since a second write path could store the document in another table.
 *
 * <p>Requires PostgreSQL, Kafka and Ollama from {@code docker-compose.yml}.
 */
@SpringBootTest(properties = {
    // Free ports: every IT's cached context keeps its gRPC push listener bound, so fixed ports collide.
    "opencrawling.connector.grpc-push.port=0",
    "spring.grpc.server.port=0"
})
@ActiveProfiles("test")
class SingleWritePathIT {

    /** A fresh consumer group, starting at the end of the topics: see {@link KafkaTestGroup}. */
    @DynamicPropertySource
    static void kafkaGroup(DynamicPropertyRegistry registry) {
        KafkaTestGroup.register(registry);
    }

    private static final List<String> TABLES = List.of("vector_store", "vector_store_384", "vector_store_768", "vector_store_1024");
    private static final long STABLE_MS = 8_000;
    private static final long TIMEOUT_MS = 90_000;

    @Autowired
    private JobOrchestrator jobOrchestrator;

    @Autowired
    private RepositoryConnector repositoryConnector;

    /** The runtime's active output: what the job API passes when a job names no output connector. */
    @Autowired
    private OutputConnector activeOutputConnector;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    @Test
    void aJobRunStoresEachChunkOnce() throws Exception {
        Path file = tempDir.resolve("single-write-" + UUID.randomUUID() + ".txt");
        Files.writeString(file, "A short document, small enough to be a single chunk.");
        String documentId = file.toAbsolutePath().toString();

        // The id the Kafka pipeline gives the document's first chunk (IngestionConsumer).
        String pipelineChunkId = UUID.nameUUIDFromBytes((documentId + "#0").getBytes(StandardCharsets.UTF_8)).toString();

        assertThat(jobOrchestrator.runJob(repositoryConnector, activeOutputConnector, tempDir.toString())).isTrue();

        // Wait for the pipeline's chunk, then long enough for any other write of the document to land too.
        awaitRow(pipelineChunkId);
        Thread.sleep(STABLE_MS);
        Map<String, Integer> rows = rowsPerTable(documentId);
        assertThat(rows.values().stream().mapToInt(Integer::intValue).sum())
                .as("rows of the one-chunk document per table: %s", rows)
                .isEqualTo(1);
    }

    private void awaitRow(String chunkId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (String table : TABLES) {
                Integer found = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id::text = ?", Integer.class, chunkId);
                if (found != null && found > 0) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        fail("The Kafka pipeline did not store chunk %s within %d ms", chunkId, TIMEOUT_MS);
    }

    private Map<String, Integer> rowsPerTable(String documentId) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE metadata->>'documentId' = ?", Integer.class, documentId));
        }
        return rows;
    }
}
