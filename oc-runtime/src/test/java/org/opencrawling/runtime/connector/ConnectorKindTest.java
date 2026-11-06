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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the class-name matching the job API has always used, for every connector class the admin UI, the
 * built-in defaults and the scripts configure, plus the lower-case and ordering quirks of the old chain.
 */
class ConnectorKindTest {

    @ParameterizedTest
    @CsvSource({
            "org.opencrawling.alfresco.AlfrescoRepositoryConnector, ALFRESCO",
            "org.opencrawling.iceberg.IcebergRepositoryConnector, ICEBERG",
            "org.opencrawling.cmis.CmisRepositoryConnector, CMIS",
            "custom.cmisConnector, CMIS",
            "org.opencrawling.doxis.DoxisRepositoryConnector, DOXIS",
            "org.opencrawling.sharepoint.SharePointRepositoryConnector, SHAREPOINT",
            "org.opencrawling.mfiles.MFilesRepositoryConnector, MFILES",
            "org.opencrawling.filenet.FileNetRepositoryConnector, FILENET",
            "org.opencrawling.documentum.DocumentumRepositoryConnector, DOCUMENTUM",
            "org.opencrawling.hyland.cic.repository.HylandCicRepositoryConnector, HYLAND_CIC",
            "org.opencrawling.jdbc.JdbcRepositoryConnector, JDBC",
            "org.opencrawling.push.grpc.GrpcPushRepositoryConnector, GRPC_PUSH",
            "custom.grpcPushSource, GRPC_PUSH",
            // the built-in default still uses the legacy class name
            "org.opencrawling.crawler.connectors.filesystem.FileConnector, FILESYSTEM",
            "org.opencrawling.filesystem.FileSystemRepositoryConnector, FILESYSTEM",
            // order of the old chain: Alfresco is checked before Cmis
            "org.opencrawling.alfresco.AlfrescoCmisBridge, ALFRESCO",
            "org.opencrawling.camunda.CamundaRepositoryConnector, NOT_RUNNABLE",
            "org.opencrawling.flowable.FlowableRepositoryConnector, NOT_RUNNABLE",
            "org.opencrawling.aps.ApsRepositoryConnector, NOT_RUNNABLE",
            "org.opencrawling.stormcrawler.StormCrawlerRepositoryConnector, NOT_RUNNABLE",
            "org.opencrawling.openarch.repository.OpenArchRepositoryConnector, NOT_RUNNABLE",
            "com.acme.MyRepositoryConnector, UNKNOWN",
            "org.opencrawling.vector.VectorOutputConnector, UNKNOWN"
    })
    void repositoryClassNamesResolveAsTheJobApiAlwaysDid(String className, RepositoryKind expected) {
        assertThat(RepositoryKind.of(className)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void aMissingRepositoryClassIsUnknown(String className) {
        assertThat(RepositoryKind.of(className)).isEqualTo(RepositoryKind.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({
            "org.opencrawling.qdrant.QdrantOutputConnector, QDRANT",
            "org.opencrawling.vespa.VespaOutputConnector, VESPA",
            "org.opencrawling.seatunnel.SeaTunnelOutputConnector, SEATUNNEL",
            "custom.seatunnelSink, SEATUNNEL",
            "org.opencrawling.ozone.OzoneOutputConnector, OZONE",
            "custom.ozoneSink, OZONE",
            "org.opencrawling.s3.S3OutputConnector, S3",
            "custom.s3sink, S3",
            "org.opencrawling.hyland.cic.output.HylandCicOutputConnector, HYLAND_CIC",
            // the S3 rule never catches OpenSearch
            "org.opencrawling.opensearch3.OpenSearch3OutputConnector, ACTIVE",
            "custom.opensearch3s3, ACTIVE",
            "org.opencrawling.opensearch2.OpenSearch2OutputConnector, ACTIVE",
            "org.opencrawling.vector.VectorOutputConnector, ACTIVE",
            "org.opencrawling.milvus.MilvusOutputConnector, ACTIVE",
            "org.opencrawling.solr.SolrOutputConnector, ACTIVE",
            "org.opencrawling.luxir.LuxirOutputConnector, ACTIVE",
            "org.opencrawling.openarch.output.OpenArchOutputConnector, ACTIVE",
            "org.opencrawling.alfresco.output.AlfrescoOutputConnector, ACTIVE",
            "com.acme.MyOutputConnector, ACTIVE"
    })
    void outputClassNamesResolveAsTheJobApiAlwaysDid(String className, OutputKind expected) {
        assertThat(OutputKind.of(className)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingOutputClassUsesTheActiveOutput(String className) {
        assertThat(OutputKind.of(className)).isEqualTo(OutputKind.ACTIVE);
    }
}
