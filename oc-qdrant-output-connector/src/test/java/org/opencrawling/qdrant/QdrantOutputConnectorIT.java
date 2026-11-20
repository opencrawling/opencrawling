/*
 * Copyright © 2026 Michael Cizmar (michael@michaelcizmar.com)
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
package org.opencrawling.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class QdrantOutputConnectorIT {

    private static final String COLLECTION = "it_kb";
    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f, 0.4f};

    @Container
    private static final QdrantContainer qdrant = new QdrantContainer("qdrant/qdrant:v1.12.4");

    private static QdrantClient client;
    private static QdrantOutputConnector connector;

    @BeforeAll
    static void setUpAll() throws Exception {
        client = new QdrantClient(QdrantGrpcClient.newBuilder(qdrant.getHost(), qdrant.getGrpcPort(), false).build());

        client.createCollectionAsync(CreateCollection.newBuilder()
                .setCollectionName(COLLECTION)
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParams(VectorParams.newBuilder().setSize(4).setDistance(Distance.Cosine).build())
                        .build())
                .build()).get();
        client.createPayloadIndexAsync(COLLECTION, QdrantFields.FIELD_SECURITY_ALLOWED_READ, PayloadSchemaType.Keyword, null, true, null, null).get();

        EmbeddingModel dummyModel = new EmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return QUERY_VECTOR;
            }

            @Override
            public float[] embed(String text) {
                return QUERY_VECTOR;
            }

            @Override
            public int dimensions() {
                return 4;
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = request.getInstructions().stream()
                        .map(text -> new Embedding(QUERY_VECTOR, 0))
                        .toList();
                return new EmbeddingResponse(embeddings);
            }
        };

        QdrantOutputProperties properties = new QdrantOutputProperties(
                qdrant.getHost(), qdrant.getGrpcPort(), "", COLLECTION, 4,
                QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 500);

        connector = new QdrantOutputConnector(client, properties, new QdrantPointMapper(), dummyModel);
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

        List<ScoredPoint> publicResults = searchWithAclFilter("public");
        assertThat(publicResults).isNotEmpty();
        assertThat(publicResults.get(0).getPayloadMap().get(QdrantFields.FIELD_CHUNK_ID).getStringValue()).contains("doc-public");

        List<ScoredPoint> hrResults = searchWithAclFilter("hr-group");
        assertThat(hrResults).isNotEmpty();
        assertThat(hrResults.get(0).getPayloadMap().get(QdrantFields.FIELD_CHUNK_ID).getStringValue()).contains("doc-restricted");
    }

    private static List<ScoredPoint> searchWithAclFilter(String identity) throws Exception {
        SearchPoints request = SearchPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .addAllVector(floatList(QUERY_VECTOR))
                .setFilter(Filter.newBuilder().addMust(matchKeyword(QdrantFields.FIELD_SECURITY_ALLOWED_READ, identity)).build())
                .setLimit(10)
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                .build();
        return client.searchAsync(request).get();
    }

    private static List<Float> floatList(float[] values) {
        List<Float> list = new java.util.ArrayList<>(values.length);
        for (float v : values) {
            list.add(v);
        }
        return list;
    }
}
