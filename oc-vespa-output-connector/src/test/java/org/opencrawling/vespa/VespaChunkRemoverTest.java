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

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A tombstone removes every chunk of its document this output stored, and nothing another output stored. */
class VespaChunkRemoverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the stand-in Vespa holds: document id → field values. */
    private final Map<String, Map<String, String>> stored = new ConcurrentHashMap<>();
    private final List<JsonNode> queries = new CopyOnWriteArrayList<>();
    private final FeedClient feedClient = mock(FeedClient.class);
    private HttpServer vespa;

    @AfterEach
    void stop() {
        if (vespa != null) {
            vespa.stop(0);
        }
    }

    @Test
    void theDocumentsChunksAreFoundByDocumentIdInThisOutputsNamespaceAndTypesOnly() throws Exception {
        store("id:opencrawling:opencrawling_chunk_1024::a", "doc-1", "https://claims.test/x");
        store("id:opencrawling:opencrawling_chunk::b", "doc-1", "https://claims.test/x");
        // Another output on the same endpoint: another namespace, or a document type this output does not write.
        store("id:other:opencrawling_chunk_1024::c", "doc-1", "https://claims.test/x");
        store("id:opencrawling:somebody_elses_type::d", "doc-1", "https://claims.test/x");
        store("id:opencrawling:opencrawling_chunk_1024::e", "doc-2", "https://claims.test/y");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(this::answer)));

        // The tombstone carries the source URI, not the claim-check URI the chunks were written with.
        assertThat(remover.removeDocument("doc-1", "https://repo.test/doc-1")).isEqualTo(2);

        assertThat(stored).containsOnlyKeys("id:other:opencrawling_chunk_1024::c", "id:opencrawling:somebody_elses_type::d",
                "id:opencrawling:opencrawling_chunk_1024::e");
        assertThat(queries.getFirst().path("yql").asText()).isEqualTo("select documentid from sources opencrawling_chunk, "
                + "opencrawling_chunk_384, opencrawling_chunk_768, opencrawling_chunk_1024 where document_id contains \"doc-1\"");
    }

    @Test
    void aFullPageOfAnotherOutputsChunksDoesNotEndTheLookup() throws Exception {
        // "aaa" sorts before "opencrawling": the stand-in returns the other output's chunks first, filling page one.
        for (int i = 0; i < VespaChunkRemover.PAGE; i++) {
            store("id:aaa:opencrawling_chunk_1024::other-" + i, "doc-3", "u");
        }
        store("id:opencrawling:opencrawling_chunk_1024::mine", "doc-3", "u");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(this::answer)));

        assertThat(remover.removeDocument("doc-3", null)).isEqualTo(1);

        assertThat(stored).doesNotContainKey("id:opencrawling:opencrawling_chunk_1024::mine").hasSize(VespaChunkRemover.PAGE);
        assertThat(queries).anyMatch(query -> query.path("offset").asInt() == VespaChunkRemover.PAGE);
    }

    @Test
    void chunksFedBeforeDocumentIdExistedAreFoundByUri() throws Exception {
        storeWithoutDocumentId("id:opencrawling:opencrawling_chunk_1024::old", "file:///data/doc-4.txt");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(this::answer)));

        assertThat(remover.removeDocument("/data/doc-4.txt", "file:///data/doc-4.txt")).isEqualTo(1);

        assertThat(stored).isEmpty();
    }

    @Test
    void anApplicationDeployedBeforeDocumentIdExistedIsAskedByUri() throws Exception {
        store("id:opencrawling:opencrawling_chunk_1024::old", null, "file:///data/doc-5.txt");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(query ->
                query.contains("document_id") ? "{\"root\":{\"errors\":[{\"code\":4,\"summary\":\"Invalid query parameter\","
                        + "\"message\":\"Could not create query from YQL: Field 'document_id' does not exist.\"}]}}"
                        : answer(query))));

        assertThat(remover.removeDocument("/data/doc-5.txt", "file:///data/doc-5.txt")).isEqualTo(1);

        assertThat(stored).isEmpty();
    }

    @Test
    void aLookupThatCannotReachVespaFails() {
        VespaChunkRemover remover = new VespaChunkRemover(feedClient, new VespaOutputProperties("http://127.0.0.1:59997",
                "opencrawling", "opencrawling_chunk", 1024, 2, false, null, null, null));

        assertThatThrownBy(() -> remover.removeDocument("doc-6", "u")).isNotInstanceOf(VespaChunkRemover.QueryRejected.class);
    }

    @Test
    void aVespaErrorOtherThanTheMissingFieldFailsTheDeletionInsteadOfFallingBack() throws Exception {
        // A timeout must not read as "no document_id field": the URI fallback could then find nothing and the
        // document be reported deleted while its chunks stay.
        store("id:opencrawling:opencrawling_chunk_1024::a", "doc-7", "https://claims.test/x");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(query ->
                "{\"root\":{\"errors\":[{\"code\":12,\"summary\":\"Timed out\",\"message\":\"Query timed out\"}]}}")));

        assertThatThrownBy(() -> remover.removeDocument("doc-7", "https://repo.test/doc-7"))
                .hasMessageContaining("Timed out")
                .isNotInstanceOf(VespaChunkRemover.QueryRejected.class);
        assertThat(stored).containsKey("id:opencrawling:opencrawling_chunk_1024::a");
    }

    @Test
    void moreOtherOutputsChunksThanVespaLetsUsReadFailsTheDeletion() throws Exception {
        // Pages start below MAX_OFFSET, so the last readable hit is MAX_OFFSET + PAGE - 1: this output's chunk lies past it.
        for (int i = 0; i < VespaChunkRemover.MAX_OFFSET + VespaChunkRemover.PAGE; i++) {
            store("id:aaa:opencrawling_chunk_1024::other-" + i, "doc-8", "u");
        }
        store("id:opencrawling:opencrawling_chunk_1024::mine", "doc-8", "u");
        VespaChunkRemover remover = new VespaChunkRemover(removingFeedClient(), properties(startVespa(this::answer)));

        assertThatThrownBy(() -> remover.removeDocument("doc-8", null)).hasMessageContaining("cannot be reached");
        assertThat(stored).containsKey("id:opencrawling:opencrawling_chunk_1024::mine");
    }

    @Test
    void unreadableTlsFilesFailTheDeletionNotTheConstruction() {
        // The connector holding the remover is built at startup (the runtime's own output) or per job.
        VespaChunkRemover remover = new VespaChunkRemover(feedClient, new VespaOutputProperties("https://vespa.test:8443",
                "opencrawling", "opencrawling_chunk", 1024, 2, true, "/absent/client.pem", "/absent/client-key.pem", null));

        assertThatThrownBy(() -> remover.removeDocument("doc-9", "u")).hasMessageContaining("https://vespa.test:8443");
    }

    private FeedClient removingFeedClient() {
        Result result = mock(Result.class);
        when(feedClient.remove(any(DocumentId.class), any(OperationParameters.class))).thenAnswer(call -> {
            stored.remove(call.getArgument(0).toString());
            return CompletableFuture.completedFuture(result);
        });
        return feedClient;
    }

    private void store(String id, String documentId, String uri) {
        Map<String, String> fields = new ConcurrentHashMap<>();
        if (documentId != null) {
            fields.put("document_id", documentId);
        }
        fields.put("uri", uri);
        stored.put(id, fields);
    }

    private void storeWithoutDocumentId(String id, String uri) {
        store(id, null, uri);
    }

    /** Answers a lookup like Vespa: the stored ids whose field matches, one page at the requested offset. */
    private String answer(String body) {
        try {
            JsonNode query = JSON.readTree(body);
            queries.add(query);
            String yql = query.path("yql").asText();
            String field = yql.contains("where document_id") ? "document_id" : "uri";
            String value = yql.substring(yql.indexOf("contains \"") + 10, yql.length() - 1);
            List<String> ids = new ArrayList<>(stored.keySet().stream().sorted().toList());
            ids.removeIf(id -> !value.equals(stored.get(id).get(field)));
            int offset = query.path("offset").asInt(0);
            List<String> page = ids.subList(Math.min(offset, ids.size()), Math.min(offset + query.path("hits").asInt(), ids.size()));
            StringBuilder children = new StringBuilder();
            for (String id : page) {
                children.append(children.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\"}");
            }
            return "{\"root\":{\"children\":[" + children + "]}}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpServer startVespa(Function<String, String> answers) throws Exception {
        vespa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vespa.createContext("/search/", exchange -> {
            byte[] answer = answers.apply(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        vespa.start();
        return vespa;
    }

    private static VespaOutputProperties properties(HttpServer server) {
        return new VespaOutputProperties("http://127.0.0.1:" + server.getAddress().getPort(), "opencrawling",
                "opencrawling_chunk", 1024, 5, false, null, null, null);
    }
}
