/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.runtime.connector;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.vector.VectorOutputConnector;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two runs writing to two instances of the same output at the same time (#319): two pgvector databases, each run
 * with its own output built by its factory, on PostgreSQL in the image the Compose stacks use.
 */
@Testcontainers
class PerRunOutputPostgresIT {

    private static final int DIMENSIONS = 8;
    private static final int DOCUMENTS_PER_RUN = 10;

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"))
            .withEnv("POSTGRES_DB", "opencrawling")
            .withEnv("POSTGRES_USER", "opencrawling")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    @BeforeAll
    static void twoInstances() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url("opencrawling"), "opencrawling", "test-only"));
        admin.execute("CREATE DATABASE store_a");
        admin.execute("CREATE DATABASE store_b");
    }

    @Test
    void twoRunsWriteToTwoInstancesOfTheSameOutputAtTheSameTime() throws Exception {
        OutputConnector runtimeOutput = mock(OutputConnector.class);
        ConnectorFactory factory = new ConnectorFactory(mock(FileSystemRepositoryConnector.class), runtimeOutput,
                provider(new PipelineProperties()), provider(embeddings()), provider(extraction()),
                provider(mock(KafkaTemplate.class)));

        OutputConnector a = factory.output(pgvector("store_a"), PipelineMode.RAG);
        OutputConnector b = factory.output(pgvector("store_b"), PipelineMode.RAG);

        assertThat(a).isInstanceOf(VectorOutputConnector.class).isNotSameAs(b).isNotSameAs(runtimeOutput);
        assertThat(a.writtenByPipeline(PipelineMode.RAG))
                .as("the runtime's pipeline never reaches this database, so the run writes it directly").isFalse();
        assertThat(b.writtenByPipeline(PipelineMode.RAG)).isFalse();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = new ArrayList<>();
            for (int i = 0; i < DOCUMENTS_PER_RUN; i++) {
                int n = i;
                sent.add(runs.submit(() -> send(start, a, "a-" + n)));
                sent.add(runs.submit(() -> send(start, b, "b-" + n)));
            }
            start.countDown();
            for (Future<?> future : sent) {
                future.get();
            }
        }

        assertThat(documentIds("store_a")).hasSize(DOCUMENTS_PER_RUN).allMatch(id -> id.startsWith("a-"));
        assertThat(documentIds("store_b")).hasSize(DOCUMENTS_PER_RUN).allMatch(id -> id.startsWith("b-"));

        a.send(tombstone("a-0")).block();
        assertThat(documentIds("store_a")).as("a run's tombstone purges its own instance")
                .hasSize(DOCUMENTS_PER_RUN - 1).doesNotContain("a-0");
        assertThat(documentIds("store_b")).hasSize(DOCUMENTS_PER_RUN);

        factory.release(a);
        factory.release(b);
    }

    private static Object send(CountDownLatch start, OutputConnector output, String id) throws InterruptedException {
        start.await();
        output.send(new RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new ByteArrayInputStream(("Text of " + id).getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain")), "", SecurityConfig.createPublic(), Instant.now())).block();
        return null;
    }

    private static RepositoryDocument tombstone(String id) {
        return new RepositoryDocument(id, "file://tmp/" + id + ".txt", null, Map.of(), "", SecurityConfig.createPublic(),
                Instant.now(), DocumentAction.DELETE);
    }

    private static List<String> documentIds(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), "opencrawling", "test-only"))
                .queryForList("SELECT DISTINCT metadata->>'documentId' FROM vector_store", String.class);
    }

    private static ConnectorDTO pgvector(String database) {
        return new ConnectorDTO("pgvector-" + database, "pgvector " + database, "output",
                "org.opencrawling.vector.VectorOutputConnector", 10, Map.of(
                        "pgVectorUrl", url(database),
                        "pgVectorUsername", "opencrawling",
                        "pgVectorPassword", "test-only",
                        "pgVectorTableName", "vector_store",
                        "pgVectorDimensions", String.valueOf(DIMENSIONS)));
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static EmbeddingModel embeddings() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = new float[DIMENSIONS];
        vector[0] = 1f;
        when(model.embed(anyString())).thenReturn(vector);
        when(model.embed(any(Document.class))).thenReturn(vector);
        return model;
    }

    private static TextExtractionService extraction() {
        TextExtractionService extraction = mock(TextExtractionService.class);
        when(extraction.extractText(any(byte[].class), anyMap()))
                .thenAnswer(call -> TextExtractionResult.success(new String((byte[]) call.getArgument(0),
                        StandardCharsets.UTF_8), Map.of(), false));
        return extraction;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
