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
package org.opencrawling.runtime.mcp.backend;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.qdrant.QdrantFields;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.opencrawling.runtime.authorization.AuthorizationConfiguration;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.mcp.McpAclEvaluator;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every MCP backend must hand its candidates to the {@link DocumentAccessGate} rather than deciding access
 * on its own, so that a provider swap (for example to OpenFGA) covers all stores at once.
 */
class GatedMcpSearchBackendTest {

    @Test
    void everyBackendGoesThroughTheGate() {
        List<Class<? extends McpVectorSearchBackend>> backends = List.of(
                LuxirMcpSearchBackend.class, MilvusMcpSearchBackend.class, OpenArchMcpSearchBackend.class,
                OpenSearch2McpSearchBackend.class, OpenSearch3McpSearchBackend.class,
                PgVectorMcpSearchBackend.class, QdrantMcpSearchBackend.class, SolrMcpSearchBackend.class,
                VespaMcpSearchBackend.class);
        assertThat(backends).allSatisfy(type ->
                assertThat(GatedMcpSearchBackend.class).isAssignableFrom(type));
    }

    @Test
    void injectedProviderDecidesInsteadOfTheStoredAcl() {
        QdrantClient client = mock(QdrantClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("q")).thenReturn(new float[] {0.1f});
        QdrantMcpSearchBackend backend = new QdrantMcpSearchBackend(client, new QdrantOutputProperties(
                "localhost", 6334, "", "kb", 1024, QdrantOutputProperties.Distance.COSINE,
                QdrantOutputProperties.Quantization.NONE, false, 500), embeddingModel, null);

        // Both chunks are public by their stored ACL, but the provider only allows doc-2.
        when(client.searchAsync(any(Points.SearchPoints.class))).thenReturn(Futures.immediateFuture(List.of(
                point("doc-1", "chunk-1"), point("doc-2", "chunk-2"))));
        List<List<ResourceRef>> batches = new ArrayList<>();
        backend.setAccessGate(new DocumentAccessGate(new AuthorizationProvider() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                batches.add(candidates);
                assertThat(caller.principal()).isEqualTo("bob");
                assertThat(caller.authenticated()).isFalse();
                boolean[] decisions = new boolean[candidates.size()];
                for (int i = 0; i < decisions.length; i++) {
                    decisions[i] = "doc-2".equals(candidates.get(i).documentId());
                }
                return decisions;
            }
        }));

        List<McpVectorServer.DocumentSearchResult> results =
                backend.secureVectorSearch("q", "bob", List.of(), 5, 0.0, null);

        assertThat(results).extracting(McpVectorServer.DocumentSearchResult::id).containsExactly("chunk-2");
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).extracting(ResourceRef::documentId).containsExactly("doc-1", "doc-2");
    }

    @Test
    void springInjectsTheConfiguredGateIntoBackends() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.opencrawling.output.type=qdrant")
                .withUserConfiguration(AuthorizationConfiguration.class)
                .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
                .withBean(QdrantOutputProperties.class, () -> new QdrantOutputProperties(
                        "localhost", 6334, "", "kb", 1024, QdrantOutputProperties.Distance.COSINE,
                        QdrantOutputProperties.Quantization.NONE, false, 500))
                .withBean(QdrantMcpSearchBackend.class)
                .run(context -> {
                    DocumentAccessGate gate = context.getBean(DocumentAccessGate.class);
                    assertThat(gate).isNotSameAs(DocumentAccessGate.metadataDefault());
                    assertThat(context.getBean(QdrantMcpSearchBackend.class).accessGate()).isSameAs(gate);
                });
    }

    @Test
    void vespaHitsDecideExactlyAsTheFieldWiseEvaluator() {
        Random random = new Random(45);
        String[] identities = {"public", "alice", "bob", "finance", "hr", "group:finance", "user:alice", ""};
        for (int run = 0; run < 5000; run++) {
            String acl = String.join(",", pick(random, identities));
            List<String> allowed = pick(random, identities);
            List<String> denied = pick(random, identities);
            String principal = random.nextBoolean() ? identities[random.nextInt(identities.length)] : null;
            // Roles never contain blanks: McpAclEvaluator.parseRoles drops them before any backend is called.
            List<String> roles = pick(random, Arrays.copyOf(identities, identities.length - 1));
            VespaMcpSearchBackend.RawHit hit = new VespaMcpSearchBackend.RawHit(
                    "c" + run, "u", "t", acl, allowed, denied, java.util.Map.of(), 1.0);

            boolean expected = McpAclEvaluator.isAccessible(acl, allowed, denied, principal, roles);
            boolean actual = DocumentAccessGate.metadataDefault()
                    .canRead(Caller.asserted(principal, roles), VespaMcpSearchBackend.toResourceRef(hit));
            assertThat(actual).as("acl=%s allowed=%s denied=%s principal=%s roles=%s",
                    acl, allowed, denied, principal, roles).isEqualTo(expected);
        }
    }

    private static List<String> pick(Random random, String[] values) {
        List<String> picked = new ArrayList<>();
        int n = random.nextInt(3);
        for (int i = 0; i < n; i++) {
            picked.add(values[random.nextInt(values.length)]);
        }
        return picked;
    }

    private static Points.ScoredPoint point(String documentId, String chunkId) {
        return Points.ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.9f)
                .putPayload(QdrantFields.FIELD_DOCUMENT_ID, ValueFactory.value(documentId))
                .putPayload(QdrantFields.FIELD_CHUNK_ID, ValueFactory.value(chunkId))
                .putPayload(QdrantFields.FIELD_ACL, ValueFactory.value("public"))
                .build();
    }
}
