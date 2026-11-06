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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
                "org.opencrawling.alfresco.AlfrescoRepositoryConnector", Map.of("url", "http://acs.test/api")));
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

        assertThat(alfresco).isInstanceOf(org.opencrawling.alfresco.AlfrescoRepositoryConnector.class);
        assertThat(cmis).isInstanceOf(CmisRepositoryConnector.class);
        assertThat(iceberg).isInstanceOf(org.opencrawling.iceberg.IcebergRepositoryConnector.class);
        assertThat(jdbc).isInstanceOf(JdbcRepositoryConnector.class);
        assertThat(push).isInstanceOf(GrpcPushRepositoryConnector.class);
        assertThat(cic).isInstanceOf(org.opencrawling.hyland.cic.repository.HylandCicRepositoryConnector.class);
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

    @Test
    void hylandCicOutputIsBuiltPerJob() {
        assertThat(factory.output(connector("Hyland_CIC_Output", "org.opencrawling.hyland.cic.output.HylandCicOutputConnector",
                Map.of("baseUrl", "https://hxp.test")), PipelineMode.RAG))
                .isInstanceOf(org.opencrawling.hyland.cic.output.HylandCicOutputConnector.class);
    }
}
