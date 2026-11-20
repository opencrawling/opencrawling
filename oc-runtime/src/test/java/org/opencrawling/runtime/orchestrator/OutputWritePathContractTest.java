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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.seatunnel.SeaTunnelOutputConnector;
import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;
import ai.vespa.feed.client.FeedClient;
import io.qdrant.client.QdrantClient;
import org.opencrawling.core.messaging.PipelineTopics;
import org.opencrawling.qdrant.QdrantOutputConnector;
import org.opencrawling.qdrant.QdrantPointMapper;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.opencrawling.vespa.VespaDocumentMapper;
import org.opencrawling.vespa.VespaOutputConnector;
import org.opencrawling.vespa.config.VespaOutputProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Which outputs a RAG run writes through the Kafka pipeline only. A vector store whose writer consumes
 * {@code opencrawling-embedded} must say so, or every job run stores its documents twice; an output no pipeline
 * writer stores must not, or its documents are never written.
 */
class OutputWritePathContractTest {

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
            "org.opencrawling.luxir.LuxirOutputConnector"
    })
    void vectorStoresAreWrittenByTheirPipelineWriterInRagRunsOnly(String className) throws Exception {
        OutputConnector output = instance(className);

        assertThat(output.writtenByPipeline(PipelineMode.RAG)).as("%s in RAG", className).isTrue();
        assertThat(output.writtenByPipeline(PipelineMode.MIGRATION)).as("%s in MIGRATION", className).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // No pipeline writer at all.
            "org.opencrawling.hyland.cic.output.HylandCicOutputConnector",
            // Its writer buffers chunks in memory after committing their offsets: kept on the direct write until fixed.
            "org.opencrawling.openarch.output.OpenArchOutputConnector",
            // Migration and document outputs, written from the documents topic: not changed here.
            "org.opencrawling.alfresco.output.AlfrescoOutputConnector",
            "org.opencrawling.doxis.output.DoxisOutputConnector",
            "org.opencrawling.iceberg.output.IcebergOutputConnector",
            "org.opencrawling.ozone.OzoneOutputConnector",
            "org.opencrawling.s3.S3OutputConnector"
    })
    void otherOutputsKeepTheirDirectWrite(String className) throws Exception {
        OutputConnector output = instance(className);

        assertThat(output.writtenByPipeline(PipelineMode.RAG)).as("%s in RAG", className).isFalse();
        assertThat(output.writtenByPipeline(PipelineMode.MIGRATION)).as("%s in MIGRATION", className).isFalse();
    }

    @Test
    void aPerJobVectorStoreThePipelineDoesNotWriteKeepsItsDirectWrite() {
        // ConnectorFactory builds a per-job Qdrant or Vespa outside the pipeline when its destination is not the
        // runtime's pipeline writer's; the flag is fixed at construction. The runtime's own beans are inside it.
        QdrantOutputProperties qdrant = new QdrantOutputProperties("qdrant.test", 6334, "", "job_kb", 1024,
                QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 500);
        VespaOutputProperties vespa = VespaOutputProperties.builder()
                .endpoint("http://vespa.test:8080")
                .build();

        assertThat(new QdrantOutputConnector(mock(QdrantClient.class), qdrant, new QdrantPointMapper(), null, null, true)
                .writtenByPipeline(PipelineMode.RAG)).isFalse();
        assertThat(new VespaOutputConnector(mock(FeedClient.class), vespa, new VespaDocumentMapper(), null, null, true)
                .writtenByPipeline(PipelineMode.RAG)).isFalse();
        assertThat(new QdrantOutputConnector(mock(QdrantClient.class), qdrant, new QdrantPointMapper(), null, null)
                .writtenByPipeline(PipelineMode.RAG)).isTrue();
        assertThat(new VespaOutputConnector(mock(FeedClient.class), vespa, new VespaDocumentMapper(), null, null)
                .writtenByPipeline(PipelineMode.RAG)).isTrue();
    }

    @Test
    void seaTunnelIsWrittenByThePipelineOnlyWhenItsJobReadsThePipelinesTopic() {
        // The embedding stage publishes to opencrawling-embedded; a job reading another topic gets only direct sends.
        SeaTunnelOutputConnector pipelineTopic = seaTunnel(PipelineTopics.EMBEDDED);
        SeaTunnelOutputConnector customTopic = seaTunnel("analytics-chunks");

        assertThat(pipelineTopic.writtenByPipeline(PipelineMode.RAG)).isTrue();
        assertThat(pipelineTopic.writtenByPipeline(PipelineMode.MIGRATION)).isFalse();
        assertThat(customTopic.writtenByPipeline(PipelineMode.RAG)).isFalse();
    }

    private static SeaTunnelOutputConnector seaTunnel(String kafkaTopic) {
        return new SeaTunnelOutputConnector(null, new SeaTunnelOutputProperties("http://seatunnel.test:8080",
                "opencrawling_ingestion_pipeline", "STREAMING", 5000, 4, "kafka.test:9092", kafkaTopic,
                "opencrawling-seatunnel-group", "console", 1024, false, 30), null, null);
    }

    /** An instance whose methods are the real ones, created without running a constructor (no clients, no I/O). */
    private static OutputConnector instance(String className) throws ClassNotFoundException {
        Class<?> type = Class.forName(className);
        return (OutputConnector) mock(type, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }
}
