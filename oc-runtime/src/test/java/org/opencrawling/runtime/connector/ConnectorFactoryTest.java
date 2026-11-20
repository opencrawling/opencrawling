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
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.opencrawling.vespa.VespaOutputConnector;
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
    void outputsThatAreNotBuiltPerJobUseTheActiveOutput() {
        assertThat(factory.output(null, PipelineMode.RAG)).isSameAs(activeOutput);
        assertThat(factory.output(connector("PGVector_Output", "org.opencrawling.vector.VectorOutputConnector", Map.of()),
                PipelineMode.RAG)).isSameAs(activeOutput);
        assertThat(factory.output(connector("Milvus_Output", "org.opencrawling.milvus.MilvusOutputConnector", Map.of()),
                PipelineMode.RAG)).isSameAs(activeOutput);
        assertThat(factory.output(connector("custom", "com.acme.MyOutputConnector", Map.of()),
                PipelineMode.RAG)).isSameAs(activeOutput);
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
        assertThat(factory.pipelineWritesQdrant(qdrant("qdrant.test", 6334, "job_kb"))).isFalse();
    }

    @Test
    void aPerJobQdrantIsLeftToThePipelineOnlyWhenTheRuntimeWritesThatSameCollection() {
        QdrantOutputConnector active = mock(QdrantOutputConnector.class);
        when(active.getProperties()).thenReturn(qdrant("qdrant.test", 6334, "job_kb"));
        ConnectorFactory qdrantRuntime = new ConnectorFactory(fileSystem, active, pipelineProperties());

        assertThat(qdrantRuntime.pipelineWritesQdrant(qdrant("qdrant.test", 6334, "job_kb"))).isTrue();
        assertThat(qdrantRuntime.pipelineWritesQdrant(qdrant("qdrant.test", 6334, "other_kb"))).isFalse();
        assertThat(qdrantRuntime.pipelineWritesQdrant(qdrant("qdrant.internal", 6334, "job_kb"))).isFalse();
        assertThat(qdrantRuntime.pipelineWritesQdrant(qdrant("qdrant.test", 6335, "job_kb"))).isFalse();
    }

    @Test
    void aPerJobVespaIsLeftToThePipelineOnlyWhenTheRuntimeFeedsThatSameDocumentType() {
        assertThat(factory.pipelineWritesVespa(vespa("http://vespa.test:8080", "opencrawling_chunk"))).isFalse();

        VespaOutputConnector active = mock(VespaOutputConnector.class);
        when(active.getProperties()).thenReturn(vespa("http://vespa.test:8080/", "opencrawling_chunk"));
        ConnectorFactory vespaRuntime = new ConnectorFactory(fileSystem, active, pipelineProperties());

        // The same endpoint, written without the trailing slash.
        assertThat(vespaRuntime.pipelineWritesVespa(vespa("http://vespa.test:8080", "opencrawling_chunk"))).isTrue();
        // Where a chunk lands depends on its embedding's length, unknown here: only an identical configuration is the
        // same destination (another type never loses documents; it may store them twice).
        assertThat(vespaRuntime.pipelineWritesVespa(vespa("http://vespa.test:8080", "other_chunk"))).isFalse();
        assertThat(vespaRuntime.pipelineWritesVespa(VespaOutputProperties.builder()
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
