/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.FeedClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There is no official Testcontainers module for Vespa, so this test drives a plain
 * {@link GenericContainer} directly: the config server (19071) accepts the application package
 * (schema + services.xml from {@code vespa-app/}, zipped in-process) via the deploy REST API, and
 * once activated the container/search endpoint (8080) starts serving.
 */
@Testcontainers(disabledWithoutDocker = true)
class VespaOutputConnectorIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 1024 must match opencrawling_chunk_1024.sd; 384 must match opencrawling_chunk_384.sd. The dummy
    // embedding model below returns one or the other depending on chunk content, so a single connector
    // instance exercises dynamic dimension routing without any redeploy between the two feeds.
    private static final float[] VECTOR_1024 = buildVector(1024);
    private static final float[] VECTOR_384 = buildVector(384);
    private static final String ALL_MINILM_MARKER = "ALL_MINILM_384";

    private static float[] buildVector(int size) {
        float[] vector = new float[size];
        vector[0] = 1.0f;
        return vector;
    }

    @Container
    private static final GenericContainer<?> vespa = new GenericContainer<>("vespaengine/vespa:8")
            .withExposedPorts(8080, 19071)
            .withStartupTimeout(Duration.ofMinutes(3))
            .waitingFor(Wait.forHttp("/ApplicationStatus").forPort(19071).forStatusCode(200));

    private static FeedClient client;
    private static VespaOutputConnector connector;
    private static String searchEndpoint;

    @BeforeAll
    static void setUpAll() throws Exception {
        String configServerUrl = "http://" + vespa.getHost() + ":" + vespa.getMappedPort(19071);
        deployApplicationPackage(configServerUrl);

        searchEndpoint = "http://" + vespa.getHost() + ":" + vespa.getMappedPort(8080);
        awaitHealthy(searchEndpoint);

        client = FeedClientBuilder.create(URI.create(searchEndpoint)).build();

        EmbeddingModel dummyModel = new EmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return embed(document.getText());
            }

            @Override
            public float[] embed(String text) {
                return text != null && text.contains(ALL_MINILM_MARKER) ? VECTOR_384 : VECTOR_1024;
            }

            @Override
            public int dimensions() {
                return VECTOR_1024.length;
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = request.getInstructions().stream()
                        .map(text -> new Embedding(embed(text), 0))
                        .toList();
                return new EmbeddingResponse(embeddings);
            }
        };

        VespaOutputProperties properties = VespaOutputProperties.builder()
                .endpoint(searchEndpoint)
                .dimensions(VECTOR_1024.length)
                .build();

        connector = new VespaOutputConnector(client, properties, new VespaDocumentMapper(), dummyModel);
    }

    @AfterAll
    static void tearDownAll() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void ingestsDocumentsAndEnforcesAclOnSearch() throws Exception {
        RepositoryDocument publicDoc = new RepositoryDocument(
                "doc-public",
                "file://tmp/doc-public.txt",
                new ByteArrayInputStream("Public information about RAG systems.".getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Public Doc")),
                "acl-public",
                new SecurityConfig(true, List.of(new PermissionRule("public", "public", "Everyone", "read"))),
                Instant.now()
        );
        connector.send(publicDoc).block();

        RepositoryDocument restrictedDoc = new RepositoryDocument(
                "doc-restricted",
                "file://tmp/doc-restricted.txt",
                new ByteArrayInputStream("Top secret restricted document containing payroll details.".getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain"), "title", List.of("Restricted Doc")),
                "acl-restricted",
                new SecurityConfig(true, List.of(
                        new PermissionRule("hr-group", "oauth-group", "HR Department", "read"),
                        new PermissionRule("external-user", "user", "External", "deny")
                )),
                Instant.now()
        );
        connector.send(restrictedDoc).block();

        // Both docs embed to VECTOR_1024, so VespaDocumentMapper.resolveDocumentType() routes them to
        // opencrawling_chunk_1024 automatically - not the generic opencrawling_chunk default.
        JsonNode publicResults = awaitResults(() -> searchWithAclFilter(
                VespaFields.DOCUMENT_TYPE_1024, VespaFields.QUERY_INPUT_1024, VECTOR_1024, "public"), 1);
        assertThat(publicResults.get("root").get("fields").get("totalCount").asInt()).isGreaterThan(0);

        JsonNode hrResults = awaitResults(() -> searchWithAclFilter(
                VespaFields.DOCUMENT_TYPE_1024, VespaFields.QUERY_INPUT_1024, VECTOR_1024, "hr-group"), 1);
        assertThat(hrResults.get("root").get("fields").get("totalCount").asInt()).isGreaterThan(0);
    }

    @Test
    void routesDifferentEmbeddingDimensionsToDedicatedDocumentTypesWithoutRedeploying() throws Exception {
        RepositoryDocument miniLmDoc = new RepositoryDocument(
                "doc-minilm",
                "file://tmp/doc-minilm.txt",
                new ByteArrayInputStream((ALL_MINILM_MARKER + " content embedded with a 384-dimension model.").getBytes(StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain")),
                "acl-minilm",
                Instant.now()
        );
        connector.send(miniLmDoc).block();

        JsonNode results = awaitResults(() -> searchAll(VespaFields.DOCUMENT_TYPE_384), 1);
        assertThat(results.get("root").get("fields").get("totalCount").asInt()).isEqualTo(1);
        assertThat(results.get("root").get("children").get(0).get("fields").get("chunk_id").asText())
                .contains("doc-minilm");
    }

    /**
     * Vespa's content cluster has a small, non-zero delay between a feed being acknowledged and the
     * document becoming visible to search (more so with several document types warming up at once), so
     * polls briefly instead of asserting immediately after the feed.
     */
    private static JsonNode awaitResults(SearchCall search, int minTotalCount) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        JsonNode last = search.run();
        while (last.path("root").path("fields").path("totalCount").asInt(0) < minTotalCount
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            last = search.run();
        }
        return last;
    }

    private interface SearchCall {
        JsonNode run() throws Exception;
    }

    private static JsonNode searchWithAclFilter(String documentType, String queryInputName, float[] queryVector,
                                                 String identity) throws Exception {
        String yql = "select * from " + documentType + " where "
                + "({targetHits:10}nearestNeighbor(embedding, " + queryInputName + ")) and security_allowed_read contains \""
                + identity + "\"";

        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.put("yql", yql);
        var vector = body.putArray("input.query(" + queryInputName + ")");
        for (float v : queryVector) {
            vector.add(v);
        }
        body.put("ranking", "semantic");
        body.put("hits", 10);

        return postSearch(body);
    }

    private static JsonNode searchAll(String documentType) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var body = mapper.createObjectNode();
        body.put("yql", "select chunk_id from " + documentType + " where true");
        body.put("hits", 10);

        return postSearch(body);
    }

    private static JsonNode postSearch(ObjectNode body) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(searchEndpoint + "/search/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private static void deployApplicationPackage(String configServerUrl) throws Exception {
        Path appDir = Path.of("vespa-app");
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            addZipEntry(zip, appDir.resolve("services.xml"), "services.xml");
            addZipEntry(zip, appDir.resolve("schemas/opencrawling_chunk.sd"), "schemas/opencrawling_chunk.sd");
            addZipEntry(zip, appDir.resolve("schemas/opencrawling_chunk_384.sd"), "schemas/opencrawling_chunk_384.sd");
            addZipEntry(zip, appDir.resolve("schemas/opencrawling_chunk_768.sd"), "schemas/opencrawling_chunk_768.sd");
            addZipEntry(zip, appDir.resolve("schemas/opencrawling_chunk_1024.sd"), "schemas/opencrawling_chunk_1024.sd");
        }

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(configServerUrl + "/application/v2/tenant/default/prepareandactivate"))
                .header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(zipBytes.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Vespa application package deploy failed: HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    private static void addZipEntry(ZipOutputStream zip, Path file, String entryName) throws Exception {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(Files.readAllBytes(file));
        zip.closeEntry();
    }

    private static void awaitHealthy(String endpoint) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/state/v1/health")).GET().build();
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"up\"")) {
                    return;
                }
            } catch (Exception ignored) {
                // Container/search endpoint not ready yet; keep polling until the deadline.
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Vespa did not become healthy at " + endpoint + " in time.");
    }
}
