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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.pipeline.PipelineMode;

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
            "org.opencrawling.luxir.LuxirOutputConnector",
            "org.opencrawling.seatunnel.SeaTunnelOutputConnector"
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

    /** An instance whose methods are the real ones, created without running a constructor (no clients, no I/O). */
    private static OutputConnector instance(String className) throws ClassNotFoundException {
        Class<?> type = Class.forName(className);
        return (OutputConnector) mock(type, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }
}
