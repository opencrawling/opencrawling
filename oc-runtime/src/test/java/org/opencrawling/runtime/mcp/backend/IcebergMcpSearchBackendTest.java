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

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.mcp.McpVectorServer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link IcebergMcpSearchBackend} behavior: fail-fast ANN search guidance, Zero-Trust
 * {@link DocumentAccessGate} enforcement (metadata and external relationship modes), tombstone hiding,
 * and caller-safe metadata exposure.
 */
class IcebergMcpSearchBackendTest {

    private InMemoryCatalog catalog;
    private IcebergOutputProperties properties;
    private IcebergOutputConnector connector;
    private IcebergMcpSearchBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new InMemoryCatalog();
        catalog.initialize("backend_test", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://backend-test"));

        properties = new IcebergOutputProperties();
        properties.setCatalogType("in-memory");
        properties.setNamespace("enterprise_data");
        properties.setTableName("documents");
        properties.setAutoCreateTable(true);
        properties.setAutoSchemaEvolution(true);
        properties.setPartitionBy(IcebergOutputProperties.PartitionStrategy.YEAR_MONTH);

        connector = new IcebergOutputConnector(properties, catalog, null);
        connector.connect();

        backend = new IcebergMcpSearchBackend(catalog, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.disconnect();
    }

    @Test
    void secureVectorSearchFailsFastWithDiagnosticGuidance() {
        assertThat(backend.backendType()).isEqualTo("iceberg");
        assertThatThrownBy(() -> backend.secureVectorSearch("query", "alice", List.of("finance"), 5, 0.5, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("listIcebergSnapshots")
                .hasMessageContaining("readOisSidecars");
    }

    @Test
    void getDocumentContentAndListAccessibleSourcesEnforceExplicitDenyTombstonesAndNoAclLeak() {
        connector.send(doc("doc-1", "alfresco://contracts/q4.pdf", "Q4 Budget",
                "Q4 budget numbers for finance team.",
                Map.of("title", List.of("Q4 Budget"), "source_type", List.of("alfresco"), "entity.vendor", List.of("Acme")),
                List.of(
                        new PermissionRule("finance", "group", "Finance", "read"),
                        new PermissionRule("mallory", "user", "Mallory", "deny")
                ))).block();

        connector.send(doc("doc-2", "alfresco://contracts/deleted.pdf", "Old Draft",
                "Draft to delete.",
                Map.of("title", List.of("Old Draft"), "source_type", List.of("alfresco")),
                List.of(new PermissionRule("finance", "group", "Finance", "read")))).block();
        connector.send(RepositoryDocument.createTombstone("doc-2", "alfresco://contracts/deleted.pdf")).block();

        // Finance user Alice reads doc-1 safely and raw ACL lists are not leaked into public metadata
        McpVectorServer.DocumentDetailsResult details =
                backend.getDocumentContent("alfresco://contracts/q4.pdf", "alice", List.of("finance"), null);
        assertThat(details.content()).contains("Q4 budget numbers");
        assertThat(details.metadata()).doesNotContainKeys("allowed_principals", "denied_principals");
        assertThat(details.metadata()).containsEntry("title", "Q4 Budget");

        // Lookup by canonical documentId works too
        assertThat(backend.getDocumentContent("doc-1", "alice", List.of("finance"), null).uri())
                .isEqualTo("alfresco://contracts/q4.pdf");

        // Explicitly denied user Mallory (even though in finance group) is denied
        assertThatThrownBy(() -> backend.getDocumentContent("doc-1", "mallory", List.of("finance"), null))
                .isInstanceOf(NoSuchElementException.class);

        // Deleted tombstone doc-2 is hidden even from Alice
        assertThatThrownBy(() -> backend.getDocumentContent("doc-2", "alice", List.of("finance"), null))
                .isInstanceOf(NoSuchElementException.class);

        // listAccessibleSources returns only doc-1 for Alice and empty for Mallory
        assertThat(backend.listAccessibleSources("alice", List.of("finance"), null))
                .extracting(m -> String.valueOf(m.get("documentId")))
                .containsExactly("doc-1");
        assertThat(backend.listAccessibleSources("mallory", List.of("finance"), null)).isEmpty();
    }

    @Test
    void injectedAuthorizationProviderGovernsDecisionsInsteadOfMetadata() {
        connector.send(doc("doc-pub", "alfresco://pub", "Public Doc", "Public content",
                Map.of("title", List.of("Public Doc"), "source_type", List.of("alfresco")),
                List.of(new PermissionRule("public", "group", "public", "read")))).block();
        connector.send(doc("doc-openfga", "alfresco://openfga", "FGA Doc", "Authorized by OpenFGA",
                Map.of("title", List.of("FGA Doc"), "source_type", List.of("alfresco")),
                List.of(new PermissionRule("other-group", "group", "Other", "read")))).block();

        backend.setAccessGate(new DocumentAccessGate(new AuthorizationProvider() {
            @Override
            public String name() {
                return "openfga-stub";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                boolean[] out = new boolean[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) {
                    out[i] = "bob".equals(caller.principal()) && "doc-openfga".equals(candidates.get(i).documentId());
                }
                return out;
            }
        }));

        assertThatThrownBy(() -> backend.getDocumentContent("doc-pub", "bob", List.of(), null))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(backend.getDocumentContent("doc-openfga", "bob", List.of(), null).content())
                .isEqualTo("Authorized by OpenFGA");
        assertThat(backend.listAccessibleSources("bob", List.of(), null))
                .extracting(m -> String.valueOf(m.get("documentId")))
                .containsExactly("doc-openfga");
    }

    private static RepositoryDocument doc(
            String id,
            String uri,
            String title,
            String text,
            Map<String, List<String>> metadata,
            List<PermissionRule> rules
    ) {
        Map<String, List<String>> enriched = new java.util.HashMap<>(metadata);
        enriched.putIfAbsent("extracted_text", List.of(text));
        return new RepositoryDocument(
                id,
                uri,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                enriched,
                null,
                new SecurityConfig(true, rules),
                Instant.parse("2026-11-13T10:00:00Z"),
                DocumentAction.UPSERT
        );
    }
}
