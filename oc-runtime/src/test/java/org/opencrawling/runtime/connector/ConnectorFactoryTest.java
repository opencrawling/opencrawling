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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencrawling.cmis.CmisRepositoryConnector;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.jdbc.JdbcRepositoryConnector;
import org.opencrawling.ozone.OzoneOutputConnector;
import org.opencrawling.push.grpc.GrpcPushRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.s3.S3OutputConnector;
import org.opencrawling.seatunnel.SeaTunnelOutputConnector;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opencrawling.qdrant.QdrantOutputConnector;
import org.opencrawling.qdrant.QdrantOutputConnectorFactory;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.opencrawling.vespa.VespaOutputConnector;
import org.opencrawling.vespa.VespaOutputConnectorFactory;
import org.opencrawling.vespa.config.VespaOutputProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorFactoryTest {

    private final FileSystemRepositoryConnector fileSystem = mock(FileSystemRepositoryConnector.class);
    private final OutputConnector activeOutput = mock(OutputConnector.class);
    private final ConnectorFactory factory = new ConnectorFactory(fileSystem, activeOutput, pipelineProperties());

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PipelineProperties> pipelineProperties() {
        ObjectProvider<PipelineProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new PipelineProperties());
        return provider;
    }

    private static ConnectorDTO connector(String name, String className, Map<String, String> configuration) {
        return new ConnectorDTO(name, name, "repository", className, 10, configuration);
    }

    // --- repository: unchanged behaviour ---

    @Test
    void aJobWithoutASavedConnectorStillUsesTheFileSystem() {
        assertThat(factory.repository(null)).isSameAs(fileSystem);
    }

    @Test
    void theBuiltInFileSystemConnectorUsesTheFileSystemBean() {
        assertThat(factory.repository(connector("FileSystem_Local",
                "org.opencrawling.crawler.connectors.filesystem.FileConnector", Map.of()))).isSameAs(fileSystem);
        assertThat(factory.repository(connector("fs",
                "org.opencrawling.filesystem.FileSystemRepositoryConnector", null))).isSameAs(fileSystem);
    }

    @Test
    void configurableConnectorsAreBuiltFromTheirSettings() {
        RepositoryConnector alfresco = factory.repository(connector("alf",
                "org.opencrawling.alfresco.AlfrescoRepositoryConnector", Map.of(
                        "url", "http://acs.test/api",
                        "crawlMode", "search_delta",
                        "authType", "bearer",
                        "oauth2AccessToken", "tok",
                        "versionMode", "all",
                        "preferRendition", "pdf")));
        RepositoryConnector cmis = factory.repository(connector("cmis",
                "org.opencrawling.cmis.CmisRepositoryConnector", Map.of("endpointUrl", "http://cmis.test/browser")));
        RepositoryConnector iceberg = factory.repository(connector("ice",
                "org.opencrawling.iceberg.IcebergRepositoryConnector", Map.of()));
        RepositoryConnector jdbc = factory.repository(connector("db",
                "org.opencrawling.jdbc.JdbcRepositoryConnector", Map.of("tableName", "docs", "batchSize", "not-a-number")));
        RepositoryConnector push = factory.repository(connector("push",
                "org.opencrawling.push.grpc.GrpcPushRepositoryConnector", Map.of()));
        RepositoryConnector cic = factory.repository(connector("cic",
                "org.opencrawling.hyland.cic.repository.HylandCicRepositoryConnector", Map.of("baseUrl", "https://hxp.test")));
        RepositoryConnector zammad = factory.repository(connector("zammad",
                "org.opencrawling.zammad.ZammadRepositoryConnector", Map.of("baseUrl", "https://support.acme.com", "accessToken", "t")));

        assertThat(alfresco).isInstanceOf(org.opencrawling.alfresco.AlfrescoRepositoryConnector.class);
        assertThat(cmis).isInstanceOf(CmisRepositoryConnector.class);
        assertThat(iceberg).isInstanceOf(org.opencrawling.iceberg.IcebergRepositoryConnector.class);
        assertThat(jdbc).isInstanceOf(JdbcRepositoryConnector.class);
        assertThat(push).isInstanceOf(GrpcPushRepositoryConnector.class);
        assertThat(cic).isInstanceOf(org.opencrawling.hyland.cic.repository.HylandCicRepositoryConnector.class);
        assertThat(zammad).isInstanceOf(org.opencrawling.zammad.ZammadRepositoryConnector.class);
    }

    // --- repository: no more silent fallback to the file system ---

    @Test
    void aConnectorThatOnlyRunsInItsCrawlerServiceIsRejected() {
        assertThatThrownBy(() -> factory.repository(connector("Camunda_8_REST_Engine",
                "org.opencrawling.camunda.CamundaRepositoryConnector", Map.of())))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("Camunda_8_REST_Engine")
                .hasMessageContaining("crawler service");
    }

    @Test
    void anUnknownClassIsRejected() {
        assertThatThrownBy(() -> factory.repository(connector("mine", "com.acme.MyRepositoryConnector", Map.of())))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("com.acme.MyRepositoryConnector");
        assertThatThrownBy(() -> factory.repository(connector("blank", null, Map.of())))
                .isInstanceOf(ConnectorResolutionException.class);
    }

    @Test
    void aConfigurationTheConnectorRejectsStopsTheJobInsteadOfCrawlingTheFileSystem() {
        // M-Files class ids must be numeric; the old code caught the error and crawled the local disk instead.
        Map<String, String> config = new HashMap<>(Map.of("classIds", "invoices"));
        assertThatThrownBy(() -> factory.repository(connector("mfiles",
                "org.opencrawling.mfiles.MFilesRepositoryConnector", config)))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("'mfiles' could not be built")
                .hasMessageContaining("not a numeric M-Files id")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // --- output ---

    @Test
    void aRunThatNamesNoOutputUsesTheRuntimesOutput() {
        assertThat(factory.output(null, PipelineMode.RAG)).isSameAs(activeOutput);
    }

    @Test
    void anOutputNoFactoryBuildsStopsTheRunInsteadOfWritingToTheRuntimesOutput() {
        assertThatThrownBy(() -> factory.output(connector("custom", "com.acme.MyOutputConnector", Map.of()),
                PipelineMode.RAG))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("custom")
                .hasMessageContaining("com.acme.MyOutputConnector");
        assertThatThrownBy(() -> factory.output(connector("blank", null, Map.of()), PipelineMode.RAG))
                .isInstanceOf(ConnectorResolutionException.class);
    }

    /** #319: every output the runtime ships can be built for one run, with its own configuration. */
    @ParameterizedTest
    @ValueSource(strings = {
            "org.opencrawling.vector.VectorOutputConnector",
            "org.opencrawling.qdrant.QdrantOutputConnector",
            "org.opencrawling.milvus.MilvusOutputConnector",
            "org.opencrawling.vespa.VespaOutputConnector",
            "org.opencrawling.solr.SolrOutputConnector",
            "org.opencrawling.opensearch2.OpenSearch2OutputConnector",
            "org.opencrawling.opensearch3.OpenSearch3OutputConnector",
            "org.opencrawling.elasticsearch.ElasticsearchOutputConnector",
            "org.opencrawling.luxir.LuxirOutputConnector",
            "org.opencrawling.seatunnel.SeaTunnelOutputConnector",
            "org.opencrawling.hyland.cic.output.HylandCicOutputConnector",
            "org.opencrawling.openarch.output.OpenArchOutputConnector",
            "org.opencrawling.alfresco.output.AlfrescoOutputConnector",
            "org.opencrawling.doxis.output.DoxisOutputConnector",
            "org.opencrawling.iceberg.output.IcebergOutputConnector",
            "org.opencrawling.ozone.OzoneOutputConnector",
            "org.opencrawling.s3.S3OutputConnector"
    })
    void everyShippedOutputHasAFactory(String className) {
        assertThat(factory.outputFactory(className)).as(className).hasValueSatisfying(found ->
                assertThat(found.className()).isEqualTo(className));
    }

    @Test
    void anOutputIsFoundByItsSimpleNameToo() {
        assertThat(factory.outputFactory("S3OutputConnector")).hasValueSatisfying(found ->
                assertThat(found.className()).isEqualTo("org.opencrawling.s3.S3OutputConnector"));
        assertThat(factory.outputFactory("com.acme.S3OutputConnector"))
                .as("a class of another package with a shipped output's simple name").isPresent();
        assertThat(factory.outputFactory("S3")).isEmpty();
    }

    @Test
    void theFactoryGetsTheRunsModeTheRuntimesOutputAndItsServices() {
        org.springframework.ai.embedding.EmbeddingModel model = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        ConnectorFactory wired = new ConnectorFactory(fileSystem, activeOutput, pipelineProperties(), provider(model),
                provider(null), provider(null));
        List<org.opencrawling.core.connector.OutputContext> seen = new java.util.ArrayList<>();
        Map<String, String> config = Map.of("key", "value");
        List<Map<String, String>> configs = new java.util.ArrayList<>();
        OutputConnector built = mock(OutputConnector.class);
        wired.useOutputFactories(List.of(new org.opencrawling.core.connector.OutputConnectorFactory() {
            @Override
            public String className() {
                return "com.acme.AcmeOutputConnector";
            }

            @Override
            public OutputConnector create(Map<String, String> configuration,
                                          org.opencrawling.core.connector.OutputContext context) {
                seen.add(context);
                configs.add(configuration);
                return built;
            }
        }));

        assertThat(wired.output(connector("acme", "com.acme.AcmeOutputConnector", config), PipelineMode.MIGRATION))
                .isSameAs(built);
        org.opencrawling.core.connector.OutputContext context = seen.getFirst();
        assertThat(configs.getFirst()).isEqualTo(config);
        assertThat(context.mode()).isEqualTo(PipelineMode.MIGRATION);
        assertThat(context.pipelineOutput()).isSameAs(activeOutput);
        assertThat(context.service(org.springframework.ai.embedding.EmbeddingModel.class)).isSameAs(model);
        assertThat(context.service(PipelineProperties.class)).isNotNull();
        assertThat(context.service(org.opencrawling.core.text.TextExtractionService.class)).isNull();
        assertThat(context.service(String.class)).isNull();
    }

    @Test
    void aFactoryThatRejectsItsConfigurationStopsTheRun() {
        factory.useOutputFactories(List.of(new org.opencrawling.core.connector.OutputConnectorFactory() {
            @Override
            public String className() {
                return "com.acme.AcmeOutputConnector";
            }

            @Override
            public OutputConnector create(Map<String, String> configuration,
                                          org.opencrawling.core.connector.OutputContext context) {
                throw new IllegalArgumentException("endpoint is required");
            }
        }));

        assertThatThrownBy(() -> factory.output(connector("acme", "com.acme.AcmeOutputConnector", Map.of()),
                PipelineMode.RAG))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("could not be built: endpoint is required")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoRunsGetTwoInstancesOfTheSameOutputEachWithItsOwnConfiguration() {
        OutputConnector first = factory.output(connector("alfresco-a", "org.opencrawling.alfresco.output.AlfrescoOutputConnector",
                Map.of("url", "http://alfresco-a.test/alfresco", "targetPathPrefix", "A", "aspects", "cm:titled, cm:dublincore")),
                PipelineMode.MIGRATION);
        OutputConnector second = factory.output(connector("alfresco-b", "org.opencrawling.alfresco.output.AlfrescoOutputConnector",
                Map.of("url", "http://alfresco-b.test/alfresco", "targetPathPrefix", "B")), PipelineMode.MIGRATION);

        assertThat(first).isNotSameAs(second).isNotSameAs(activeOutput);
        var a = ((org.opencrawling.alfresco.output.AlfrescoOutputConnector) first).getProperties();
        var b = ((org.opencrawling.alfresco.output.AlfrescoOutputConnector) second).getProperties();
        assertThat(a.getUrl()).isEqualTo("http://alfresco-a.test/alfresco");
        assertThat(a.getTargetPathPrefix()).isEqualTo("A");
        assertThat(a.getAspects()).containsExactly("cm:titled", "cm:dublincore");
        assertThat(b.getUrl()).isEqualTo("http://alfresco-b.test/alfresco");
        assertThat(b.getTargetPathPrefix()).isEqualTo("B");
        assertThat(b.getAspects()).as("unset keys keep their defaults").contains("cm:versionable");
        assertThat(((org.opencrawling.alfresco.output.AlfrescoOutputConnector) first).getPipelineMode())
                .isEqualTo(PipelineMode.MIGRATION);
    }

    @Test
    void anOpenArchOutputIsBuiltPerRunWithItsOwnConfiguration() {
        assertThat(factory.output(connector("openarch", "org.opencrawling.openarch.output.OpenArchOutputConnector",
                Map.of("endpointUrl", "http://openarch.test:8080", "repositoryId", "archive")), PipelineMode.RAG))
                .isInstanceOf(org.opencrawling.openarch.output.OpenArchOutputConnector.class)
                .isNotSameAs(activeOutput);
    }

    @Test
    void migrationOutputsAreBuiltWithTheJobsPipelineMode() {
        OutputConnector ozone = factory.output(connector("Ozone_Migration_Output",
                "org.opencrawling.ozone.OzoneOutputConnector", Map.of("bucket", "b1")), PipelineMode.MIGRATION);
        OutputConnector s3 = factory.output(connector("S3_Migration_Output",
                "org.opencrawling.s3.S3OutputConnector", Map.of("bucket", "lake", "region", "eu-west-1")), PipelineMode.RAG);

        assertThat(ozone).isInstanceOf(OzoneOutputConnector.class);
        assertThat(((OzoneOutputConnector) ozone).getPipelineMode()).isEqualTo(PipelineMode.MIGRATION);
        assertThat(s3).isInstanceOf(S3OutputConnector.class);
        assertThat(((S3OutputConnector) s3).getPipelineMode()).isEqualTo(PipelineMode.RAG);
        assertThat(((S3OutputConnector) s3).getProperties().getBucket()).isEqualTo("lake");
        assertThat(((S3OutputConnector) s3).getProperties().getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void s3CustomHeadersAreReadFromJsonAndPrefixedKeys() {
        OutputConnector s3 = factory.output(connector("S3", "org.opencrawling.s3.S3OutputConnector", Map.of(
                "customHeaders", "{\"x-amz-meta-a\":\"1\"}",
                "customHeader.x-amz-meta-b", " 2 ")), PipelineMode.MIGRATION);

        assertThat(((S3OutputConnector) s3).getProperties().getCustomHeaders())
                .containsEntry("x-amz-meta-a", "1")
                .containsEntry("x-amz-meta-b", "2");
    }

    @Test
    void aPerRunSeaTunnelReadsTheKeysTheAdminUiSaves() {
        OutputConnector output = factory.output(connector("SeaTunnel_Output",
                "org.opencrawling.seatunnel.SeaTunnelOutputConnector", Map.of(
                        "seaTunnelRestUrl", "http://seatunnel.test:5801",
                        "seaTunnelKafkaTopic", "analytics-chunks",
                        "seaTunnelTargetSinks", "clickhouse")), PipelineMode.RAG);

        assertThat(output.writtenByPipeline(PipelineMode.RAG))
                .as("its own topic, which the pipeline does not feed: the run sends its documents directly").isFalse();
    }

    @Test
    void seaTunnelIsBuiltPerJob() {
        assertThat(factory.output(connector("SeaTunnel_Output", "org.opencrawling.seatunnel.SeaTunnelOutputConnector",
                Map.of("seatunnelRestUrl", "http://seatunnel.test:5801")), PipelineMode.RAG))
                .isInstanceOf(SeaTunnelOutputConnector.class);
    }

    /**
     * #333: per-job vector outputs get the runtime's embedding model and text extraction, and SeaTunnel its Kafka
     * template; built with none, they stored placeholder vectors. Qdrant and Vespa get them the same way, but their
     * clients need a live server to be built, so SeaTunnel shows the wiring here.
     */
    @Test
    @SuppressWarnings("unchecked")
    void perJobVectorOutputsUseTheRuntimesEmbeddingModelTextExtractionAndKafka() {
        org.springframework.ai.embedding.EmbeddingModel model = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        when(model.embed(org.mockito.ArgumentMatchers.any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[] {0.1f, 0.2f});
        org.opencrawling.core.text.TextExtractionService extraction = mock(org.opencrawling.core.text.TextExtractionService.class);
        when(extraction.extractText(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(org.opencrawling.core.text.TextExtractionResult.success("Extracted by the runtime.", Map.of(), false));
        org.springframework.kafka.core.KafkaTemplate<String, Object> kafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        ConnectorFactory wired = new ConnectorFactory(fileSystem, activeOutput, pipelineProperties(),
                provider(model), provider(extraction), provider(kafka));
        ConnectorDTO seaTunnel = connector("SeaTunnel_Output", "org.opencrawling.seatunnel.SeaTunnelOutputConnector",
                Map.of("seatunnelRestUrl", "http://seatunnel.test:5801"));

        wired.output(seaTunnel, PipelineMode.RAG).send(textDocument("doc-wired")).block();

        verify(extraction).extractText(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyMap());
        verify(model).embed(org.mockito.ArgumentMatchers.any(org.springframework.ai.document.Document.class));
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("opencrawling-embedded"), org.mockito.ArgumentMatchers.eq("doc-wired"),
                org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.output(seaTunnel, PipelineMode.RAG)
                        .send(textDocument("doc-unwired")).block())
                .as("without the runtime's model a document fails instead of getting a placeholder vector")
                .hasStackTraceContaining("No embedding model is configured");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private static org.opencrawling.core.document.RepositoryDocument textDocument(String id) {
        return new org.opencrawling.core.document.RepositoryDocument(id, "file://tmp/" + id + ".txt",
                new java.io.ByteArrayInputStream("Text for the stream.".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Map.of("mimeType", List.of("text/plain")), "", org.opencrawling.core.security.SecurityConfig.createPublic(),
                java.time.Instant.now());
    }

    @Test
    void hylandCicOutputIsBuiltPerJob() {
        assertThat(factory.output(connector("Hyland_CIC_Output", "org.opencrawling.hyland.cic.output.HylandCicOutputConnector",
                Map.of("baseUrl", "https://hxp.test")), PipelineMode.RAG))
                .isInstanceOf(org.opencrawling.hyland.cic.output.HylandCicOutputConnector.class);
    }

    @Test
    void doxisOutputIsBuiltPerJob() {
        assertThat(factory.output(connector("Doxis_Output", "org.opencrawling.doxis.output.DoxisOutputConnector",
                Map.of("doxisBaseUrl", "http://csb.test:8080/restws/publicws/rest/api/v1", "doxisCustomerName", "DX4",
                        "doxisUsername", "crawler", "doxisPassword", "secret", "doxisRepository", "Repo")), PipelineMode.RAG))
                .isInstanceOf(org.opencrawling.doxis.output.DoxisOutputConnector.class);
    }

    @Test
    void releaseDisconnectsPerJobOutputsButNeverTheActiveOutput() throws Exception {
        OutputConnector perJob = mock(OutputConnector.class);
        factory.release(perJob);
        verify(perJob).disconnect();

        factory.release(activeOutput);
        verify(activeOutput, never()).disconnect();

        factory.release(null);
    }

    @Test
    void aFailingDisconnectIsLoggedNotThrown() throws Exception {
        OutputConnector perJob = mock(OutputConnector.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("logout failed")).when(perJob).disconnect();
        factory.release(perJob);
        verify(perJob).disconnect();
    }

    // --- per-job vector outputs: the pipeline only writes the runtime's own destination ---

    // Building a per-job Qdrant or Vespa output connects to it, so these tests ask the factory's decision directly:
    // its builders build the output outside the pipeline when the decision is no (see OutputWritePathContractTest).

    @Test
    void aPerJobQdrantOnARuntimeWritingAnotherStoreIsNotLeftToThePipeline() {
        // The runtime's pipeline writes pgvector (or anything but Qdrant): it would never reach the job's Qdrant.
        assertThat(QdrantOutputConnectorFactory.pipelineWrites(context(activeOutput), qdrant("qdrant.test", 6334, "job_kb")))
                .isFalse();
    }

    @Test
    void aPerJobQdrantIsLeftToThePipelineOnlyWhenTheRuntimeWritesThatSameCollection() {
        QdrantOutputConnector active = mock(QdrantOutputConnector.class);
        when(active.getProperties()).thenReturn(qdrant("qdrant.test", 6334, "job_kb"));

        assertThat(QdrantOutputConnectorFactory.pipelineWrites(context(active), qdrant("qdrant.test", 6334, "job_kb"))).isTrue();
        assertThat(QdrantOutputConnectorFactory.pipelineWrites(context(active), qdrant("qdrant.test", 6334, "other_kb"))).isFalse();
        assertThat(QdrantOutputConnectorFactory.pipelineWrites(context(active), qdrant("qdrant.internal", 6334, "job_kb"))).isFalse();
        assertThat(QdrantOutputConnectorFactory.pipelineWrites(context(active), qdrant("qdrant.test", 6335, "job_kb"))).isFalse();
    }

    @Test
    void aPerJobVespaIsLeftToThePipelineOnlyWhenTheRuntimeFeedsThatSameDocumentType() {
        assertThat(VespaOutputConnectorFactory.pipelineWrites(context(activeOutput),
                vespa("http://vespa.test:8080", "opencrawling_chunk"))).isFalse();

        VespaOutputConnector active = mock(VespaOutputConnector.class);
        when(active.getProperties()).thenReturn(vespa("http://vespa.test:8080/", "opencrawling_chunk"));

        // The same endpoint, written without the trailing slash.
        assertThat(VespaOutputConnectorFactory.pipelineWrites(context(active), vespa("http://vespa.test:8080", "opencrawling_chunk"))).isTrue();
        // Where a chunk lands depends on its embedding's length, unknown here: only an identical configuration is the
        // same destination (another type never loses documents; it may store them twice).
        assertThat(VespaOutputConnectorFactory.pipelineWrites(context(active), vespa("http://vespa.test:8080", "other_chunk"))).isFalse();
        assertThat(VespaOutputConnectorFactory.pipelineWrites(context(active), VespaOutputProperties.builder()
                .endpoint("http://vespa.test:8080")
                .namespace("another_namespace")
                .build())).isFalse();
    }

    @Test
    void aPerJobSeaTunnelReadsThePipelinesTopicSoThePipelineFeedsIt() {
        OutputConnector output = factory.output(connector("job-seatunnel",
                "org.opencrawling.seatunnel.SeaTunnelOutputConnector", Map.of()), PipelineMode.RAG);

        assertThat(output.writtenByPipeline(PipelineMode.RAG)).isTrue();
    }

    @Test
    void theOtherVectorStoresAreLeftToThePipelineOnlyWhenTheRuntimeWritesThatSameDestination() {
        org.opencrawling.milvus.MilvusOutputConnector milvus = mock(org.opencrawling.milvus.MilvusOutputConnector.class);
        when(milvus.uri()).thenReturn("http://milvus.test:19530/");
        when(milvus.collectionName()).thenReturn("kb");
        assertThat(org.opencrawling.milvus.MilvusOutputConnectorFactory.pipelineWrites(context(milvus),
                "http://milvus.test:19530", "kb")).isTrue();
        assertThat(org.opencrawling.milvus.MilvusOutputConnectorFactory.pipelineWrites(context(milvus),
                "http://milvus.test:19530", "other")).isFalse();
        assertThat(org.opencrawling.milvus.MilvusOutputConnectorFactory.pipelineWrites(context(activeOutput),
                "http://milvus.test:19530", "kb")).as("the runtime writes another store").isFalse();

        org.opencrawling.opensearch2.OpenSearch2OutputConnector os2 = mock(org.opencrawling.opensearch2.OpenSearch2OutputConnector.class);
        when(os2.uris()).thenReturn("http://os.test:9200");
        when(os2.indexName()).thenReturn("kb");
        assertThat(org.opencrawling.opensearch2.OpenSearch2OutputConnectorFactory.pipelineWrites(context(os2),
                "http://os.test:9200", "kb")).isTrue();
        assertThat(org.opencrawling.opensearch2.OpenSearch2OutputConnectorFactory.pipelineWrites(context(os2),
                "http://os-b.test:9200", "kb")).isFalse();
        assertThat(org.opencrawling.opensearch3.OpenSearch3OutputConnectorFactory.pipelineWrites(context(os2),
                "http://os.test:9200", "kb")).as("an OpenSearch 2 writer is not an OpenSearch 3 one").isFalse();

        org.opencrawling.luxir.LuxirOutputConnector luxir = mock(org.opencrawling.luxir.LuxirOutputConnector.class);
        when(luxir.getProperties()).thenReturn(new org.opencrawling.luxir.config.LuxirOutputProperties(
                "http://luxir.test:9400", "kb", null, 0, null, true, 0, 0));
        assertThat(org.opencrawling.luxir.LuxirOutputConnectorFactory.pipelineWrites(context(luxir),
                new org.opencrawling.luxir.config.LuxirOutputProperties("http://luxir.test:9400/", "kb", null, 0, null, true, 0, 0)))
                .isTrue();
        assertThat(org.opencrawling.luxir.LuxirOutputConnectorFactory.pipelineWrites(context(luxir),
                new org.opencrawling.luxir.config.LuxirOutputProperties("http://luxir.test:9400", "other", null, 0, null, true, 0, 0)))
                .isFalse();

        org.opencrawling.solr.SolrOutputConnector solr = mock(org.opencrawling.solr.SolrOutputConnector.class);
        when(solr.getProperties()).thenReturn(solr("standalone", "http://solr.test:8983/solr", "kb"));
        assertThat(org.opencrawling.solr.SolrOutputConnectorFactory.pipelineWrites(context(solr),
                solr("standalone", "http://solr.test:8983/solr/", "kb"))).isTrue();
        assertThat(org.opencrawling.solr.SolrOutputConnectorFactory.pipelineWrites(context(solr),
                solr("standalone", "http://solr.test:8983/solr", "other"))).isFalse();
        assertThat(org.opencrawling.solr.SolrOutputConnectorFactory.pipelineWrites(context(solr),
                solr("cloud", "http://solr.test:8983/solr", "kb"))).as("cloud mode names ZooKeeper, not the URL").isFalse();

        org.opencrawling.elasticsearch.ElasticsearchOutputConnector elastic =
                mock(org.opencrawling.elasticsearch.ElasticsearchOutputConnector.class);
        when(elastic.getProperties()).thenReturn(elastic("http://es.test:9200", "kb"));
        assertThat(org.opencrawling.elasticsearch.ElasticsearchOutputConnectorFactory.pipelineWrites(context(elastic),
                elastic("http://es.test:9200", "kb"))).isTrue();
        assertThat(org.opencrawling.elasticsearch.ElasticsearchOutputConnectorFactory.pipelineWrites(context(elastic),
                elastic("http://es.test:9200", "other"))).isFalse();

        org.opencrawling.vector.VectorOutputConnector pgvector = mock(org.opencrawling.vector.VectorOutputConnector.class);
        when(pgvector.url()).thenReturn("jdbc:postgresql://pg.test:5432/opencrawling");
        when(pgvector.getVectorTableName()).thenReturn("vector_store");
        assertThat(org.opencrawling.vector.VectorOutputConnectorFactory.pipelineWrites(context(pgvector),
                "jdbc:postgresql://pg.test:5432/opencrawling", "vector_store")).isTrue();
        assertThat(org.opencrawling.vector.VectorOutputConnectorFactory.pipelineWrites(context(pgvector),
                "jdbc:postgresql://pg.test:5432/opencrawling", "run_vectors")).isFalse();
        assertThat(org.opencrawling.vector.VectorOutputConnectorFactory.pipelineWrites(context(pgvector),
                "jdbc:postgresql://pg-b.test:5432/opencrawling", "vector_store")).isFalse();
    }

    @Test
    void aPgvectorOutputRejectsATableNameThatIsNotAnIdentifier() {
        assertThatThrownBy(() -> factory.output(connector("pg", "org.opencrawling.vector.VectorOutputConnector", Map.of(
                "pgVectorUrl", "jdbc:postgresql://pg.test:5432/opencrawling",
                "pgVectorTableName", "vector_store; DROP TABLE x")), PipelineMode.RAG))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("not a valid table name");
        assertThatThrownBy(() -> factory.output(connector("pg", "org.opencrawling.vector.VectorOutputConnector", Map.of()),
                PipelineMode.RAG))
                .isInstanceOf(ConnectorResolutionException.class)
                .hasMessageContaining("pgVectorUrl is required");
    }

    private static org.opencrawling.core.connector.OutputContext context(OutputConnector pipelineOutput) {
        return new org.opencrawling.core.connector.OutputContext(PipelineMode.RAG, pipelineOutput, null);
    }

    private static org.opencrawling.solr.config.SolrOutputProperties solr(String mode, String url, String collection) {
        return new org.opencrawling.solr.config.SolrOutputProperties(mode, url, "zk.test:2181", collection, 0, null, null,
                null, 0, 0, 0, 0, null, null);
    }

    private static org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties elastic(String uris, String index) {
        return new org.opencrawling.elasticsearch.config.ElasticsearchOutputProperties(uris, "elastic", "", "", index,
                1024, null, null, 500, true, 8, null);
    }

    private static QdrantOutputProperties qdrant(String host, int port, String collection) {
        return new QdrantOutputProperties(host, port, "", collection, 1024, QdrantOutputProperties.Distance.COSINE,
                QdrantOutputProperties.Quantization.NONE, false, 500);
    }

    private static VespaOutputProperties vespa(String endpoint, String documentType) {
        return VespaOutputProperties.builder()
                .endpoint(endpoint)
                .documentType(documentType)
                .build();
    }
}
