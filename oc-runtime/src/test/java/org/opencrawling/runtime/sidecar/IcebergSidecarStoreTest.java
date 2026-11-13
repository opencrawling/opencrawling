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
distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.sidecar;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.connector.ConnectorFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@link IcebergSidecarStore}, {@link SidecarStores} support for
 * {@link org.opencrawling.iceberg.output.IcebergOutputConnector}, and {@link SidecarService} pagination,
 * partition/prefix pruning, Zero-Trust ACL filtering, and tombstone omission without pulling binaries.
 */
class IcebergSidecarStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void connectorFactoryAndSidecarServiceReadPaginatePruneAndFilterIcebergSidecars() throws Exception {
        String warehouseUri = tempDir.resolve("iceberg-warehouse").toUri().toString();
        Map<String, String> connectorConfig = Map.ofEntries(
                Map.entry("catalogName", "lakehouse"),
                Map.entry("catalogType", "hadoop"),
                Map.entry("warehouse", warehouseUri),
                Map.entry("namespace", "enterprise_data"),
                Map.entry("tableName", "documents"),
                Map.entry("partitionBy", "YEAR_MONTH"),
                Map.entry("autoCreateTable", "true"),
                Map.entry("autoSchemaEvolution", "true"),
                Map.entry("s3Endpoint", "http://localhost:9000"),
                Map.entry("s3Region", "us-east-1"),
                Map.entry("s3AccessKeyId", "minio"),
                Map.entry("s3SecretAccessKey", "minio123"),
                Map.entry("s3PathStyleAccess", "true")
        );

        // Verify ConnectorFactory keeps REST URI default only for REST and maps S3/Iceberg settings
        IcebergOutputProperties mapped = ConnectorFactory.icebergProperties(connectorConfig);
        assertThat(mapped.getCatalogType()).isEqualTo("hadoop");
        assertThat(mapped.getCatalogUri()).isBlank();
        assertThat(mapped.getS3().getEndpoint()).isEqualTo("http://localhost:9000");
        assertThat(mapped.getS3().isPathStyleAccess()).isTrue();

        ConnectorDTO icebergConnectorDto = new ConnectorDTO(
                "Iceberg_Lakehouse",
                "Apache Iceberg Lakehouse",
                "output",
                "org.opencrawling.iceberg.output.IcebergOutputConnector",
                2,
                connectorConfig
        );
        assertThat(SidecarStores.supports(icebergConnectorDto)).isTrue();
        assertThat(SidecarStores.layout(icebergConnectorDto)).isEqualTo(new SidecarStores.Layout("", ".ois.json"));

        // Populate HadoopCatalog with multi-source, multi-month, ACL-differentiated documents + a tombstone
        try (HadoopCatalog hadoopCatalog = new HadoopCatalog(new Configuration(), warehouseUri)) {
            IcebergOutputConnector writer = new IcebergOutputConnector(mapped, hadoopCatalog, null);
            writer.connect();
            try {
                writer.send(doc("alf-nov-1", "alfresco://2026-11/contract1.pdf", "Contract 1",
                        "alfresco", "application/pdf", Instant.parse("2026-11-10T09:00:00Z"),
                        Map.of("entity.vendor", List.of("Acme")),
                        List.of(new PermissionRule("finance", "group", "Finance", "read")))).block();

                writer.send(doc("alf-nov-2", "alfresco://2026-11/contract2.pdf", "Contract 2",
                        "alfresco", "application/pdf", Instant.parse("2026-11-11T09:00:00Z"),
                        Map.of("entity.vendor", List.of("Globex")),
                        List.of(new PermissionRule("finance", "group", "Finance", "read")))).block();

                writer.send(doc("alf-oct-1", "alfresco://2026-10/old.pdf", "October Report",
                        "alfresco", "text/plain", Instant.parse("2026-10-15T09:00:00Z"),
                        Map.of(),
                        List.of(new PermissionRule("finance", "group", "Finance", "read")))).block();

                writer.send(doc("sp-nov-secret", "sp://2026-11/board.pdf", "Board Secret",
                        "sharepoint", "application/pdf", Instant.parse("2026-11-12T09:00:00Z"),
                        Map.of(),
                        List.of(new PermissionRule("executives", "group", "Exec", "read")))).block();

                writer.send(doc("alf-nov-deleted", "alfresco://2026-11/deleted.pdf", "Deleted",
                        "alfresco", "application/pdf", Instant.parse("2026-11-12T09:00:00Z"),
                        Map.of(),
                        List.of(new PermissionRule("finance", "group", "Finance", "read")))).block();
                writer.send(RepositoryDocument.createTombstone("alf-nov-deleted", "alfresco://2026-11/deleted.pdf")).block();
            } finally {
                writer.disconnect();
            }
        }

        SidecarProperties sidecarProps = new SidecarProperties();
        sidecarProps.setEnabled(true);
        sidecarProps.setConnectors(List.of("Iceberg_Lakehouse"));
        sidecarProps.setAllowAssertedIdentity(true);
        sidecarProps.setCursorSecret("0123456789abcdef0123456789abcdef");

        SidecarService service = new SidecarService(
                sidecarProps,
                () -> List.of(icebergConnectorDto),
                SidecarStores::open,
                DocumentAccessGate.metadataDefault(),
                Clock.systemUTC()
        );

        Caller financeAlice = Caller.asserted("alice", List.of("finance"));
        List<SidecarService.StoreInfo> stores = service.listStores(financeAlice);
        assertThat(stores).hasSize(1);
        assertThat(stores.getFirst().name()).isEqualTo("Iceberg_Lakehouse");
        assertThat(stores.getFirst().kind()).isEqualTo("iceberg");

        // Read with pagination across all accessible docs (3 total for financeAlice; secret + deleted omitted)
        SidecarService.SidecarPage page1 = service.read(financeAlice, "Iceberg_Lakehouse", "", 2, null);
        assertThat(page1.sidecars()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.aggregates().count()).isEqualTo(2);

        SidecarService.SidecarPage page2 = service.read(financeAlice, "Iceberg_Lakehouse", "", 2, page1.nextCursor());
        assertThat(page2.sidecars()).hasSize(1);
        assertThat(page2.nextCursor()).isNull();

        List<String> allIds = new java.util.ArrayList<>();
        page1.sidecars().forEach(s -> allIds.add(s.documentId()));
        page2.sidecars().forEach(s -> allIds.add(s.documentId()));
        assertThat(allIds).containsExactlyInAnyOrder("alf-oct-1", "alf-nov-1", "alf-nov-2");

        // Partition prefix pruning: 'alfresco/2026-11/' only returns Nov docs
        SidecarService.SidecarPage novOnly = service.read(financeAlice, "Iceberg_Lakehouse", "alfresco/2026-11/", 10, null);
        assertThat(novOnly.sidecars())
                .extracting(SidecarService.SidecarView::documentId)
                .containsExactlyInAnyOrder("alf-nov-1", "alf-nov-2");
        assertThat(novOnly.sidecars().getFirst().metadata()).containsKey("entity.vendor");
        assertThat(novOnly.aggregates().bySourceType()).containsExactlyInAnyOrderEntriesOf(Map.of("alfresco", 2L));

        // Direct store test: maxSidecarBytes & tombstone omission
        try (SidecarStore direct = SidecarStores.open(icebergConnectorDto)) {
            SidecarStore.Page keys = direct.list("alfresco/2026-11/", null, 20);
            assertThat(keys.keys()).hasSize(2);
            assertThat(keys.keys()).noneMatch(k -> k.contains("deleted"));
        }
    }

    private static RepositoryDocument doc(
            String id,
            String uri,
            String title,
            String sourceType,
            String mimeType,
            Instant crawledAt,
            Map<String, List<String>> extraMeta,
            List<PermissionRule> rules
    ) {
        Map<String, List<String>> meta = new java.util.HashMap<>(extraMeta);
        meta.put("title", List.of(title));
        meta.put("source_type", List.of(sourceType));
        meta.put("mime_type", List.of(mimeType));
        byte[] bytes = ("Body for " + title).getBytes(StandardCharsets.UTF_8);
        return new RepositoryDocument(
                id,
                uri,
                new ByteArrayInputStream(bytes),
                meta,
                null,
                new SecurityConfig(true, rules),
                crawledAt,
                DocumentAction.UPSERT
        );
    }
}
