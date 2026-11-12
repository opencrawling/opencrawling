/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.discovery.*;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.hyland.cic.client.HylandCicClient;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicDocument;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import org.opencrawling.hyland.cic.model.HylandCicItem;
import org.opencrawling.hyland.cic.model.HylandCicPagedResult;
import org.opencrawling.hyland.cic.model.HylandCicPermission;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HylandCicRepositoryConnectorTest {

    private HylandCicProperties properties;
    private HylandCicClient client;
    private HylandCicRepositoryConnector connector;

    @BeforeEach
    void setUp() {
        properties = new HylandCicProperties();
        properties.setRootFolderPath("/Root");
        properties.setRootFolderId("root-uuid");
        properties.setBatchSize(50);
        properties.setIncludeSubfolders(true);
        properties.setIncludeAcls(true);
        properties.setIncludeContentStream(true);

        client = mock(HylandCicClient.class);
        connector = new HylandCicRepositoryConnector(properties, client);
    }

    @Test
    void metadataAndSchemaContract() {
        assertThat(connector.getName()).isEqualTo("HylandCicRepositoryConnector");
        assertThat(connector.supportsConcurrentProcessing()).isTrue();

        ConnectorSchema schema = connector.getSchema(null);
        assertThat(schema.fields()).extracting(ConnectorSchema.SchemaField::name)
                .contains("id", "name", "path", "folderId", "contentType", "mimeType", "fileSize", "version", "modified");
    }

    @Test
    void crawlStartReturnsRootFolderNode() {
        HylandCicFolder root = new HylandCicFolder(
                "root-uuid", "Root", "/Root", null,
                Instant.now(), Instant.now(), List.of()
        );
        when(client.findFolder("root-uuid")).thenReturn(Optional.of(root));

        DiscoveryNode node = connector.crawlStart("root-uuid", VisibilityFilter.ALL);
        assertThat(node).isNotNull();
        assertThat(node.sourceId()).isEqualTo("root-uuid");
        assertThat(node.name()).isEqualTo("Root");
        assertThat(node.type()).isEqualTo(DiscoveryNode.NodeType.FOLDER);
    }

    @Test
    void crawlStartRejectsQueryCrawlers() {
        properties.setQuery("contentType eq 'Report'");
        assertThatThrownBy(() -> connector.crawlStart("root-uuid", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crawls a query");
    }

    @Test
    void crawlStartAntiProbingHidesMissingOrHiddenFolders() {
        when(client.findFolder("secret-folder")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector.crawlStart("secret-folder", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No HXP folder 'secret-folder' in this connector's scope.");
    }

    @Test
    void exploreReturnsHierarchicalTreeRespectingDepth() {
        HylandCicFolder root = new HylandCicFolder(
                "root-uuid", "Root", "/Root", null,
                Instant.now(), Instant.now(), List.of()
        );
        when(client.findFolder("root-uuid")).thenReturn(Optional.of(root));

        HylandCicItem childFolder = new HylandCicItem(
                "sub-1", "Sub1", "/Root/Sub1", "root-uuid", "folder",
                null, null, null, Instant.now(), List.of()
        );
        HylandCicItem childDoc = new HylandCicItem(
                "doc-1", "Doc1.pdf", "/Root/Doc1.pdf", "root-uuid", "document",
                "PdfDoc", 1024L, "application/pdf", Instant.now(), List.of()
        );
        when(client.getFolderItems(eq("root-uuid"), eq(0), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(childFolder, childDoc), 2L, false, null));

        HylandCicItem deepDoc = new HylandCicItem(
                "doc-deep", "Deep.pdf", "/Root/Sub1/Deep.pdf", "sub-1", "document",
                "PdfDoc", 2048L, "application/pdf", Instant.now(), List.of()
        );
        when(client.getFolderItems(eq("sub-1"), eq(0), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(deepDoc), 1L, false, null));

        ExploreRequest req = new ExploreRequest("root-uuid", 2, 100, true, Duration.ofSeconds(10));
        DiscoveryResult result = connector.explore(req, VisibilityFilter.ALL);

        assertThat(result.nodes()).hasSize(3);
        assertThat(result.nodes()).extracting(DiscoveryNode::sourceId)
                .containsExactly("sub-1", "doc-1", "doc-deep");
        assertThat(result.truncationReason()).isNull();
    }

    @Test
    void exploreAppliesVisibilityFilter() {
        HylandCicFolder root = new HylandCicFolder("root-uuid", "Root", "/Root", null, Instant.now(), Instant.now(), List.of());
        when(client.findFolder("root-uuid")).thenReturn(Optional.of(root));

        HylandCicItem visibleItem = new HylandCicItem("v-1", "Visible", "/Root/Visible", "root-uuid", "folder", null, null, null, Instant.now(), List.of());
        HylandCicItem hiddenItem = new HylandCicItem("h-1", "Hidden", "/Root/Hidden", "root-uuid", "folder", null, null, null, Instant.now(), List.of());

        when(client.getFolderItems(eq("root-uuid"), eq(0), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(visibleItem, hiddenItem), 2L, false, null));
        when(client.getFolderItems(eq("v-1"), anyInt(), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(), 0L, false, null));

        VisibilityFilter customFilter = nodes -> {
            boolean[] res = new boolean[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                res[i] = !nodes.get(i).sourceId().equals("h-1");
            }
            return res;
        };

        ExploreRequest req = new ExploreRequest("root-uuid", 1, 10, false, Duration.ofSeconds(5));
        DiscoveryResult result = connector.explore(req, customFilter);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).sourceId()).isEqualTo("v-1");
        assertThat(result.hiddenCount()).isEqualTo(1);
    }

    @Test
    void dryRunFolderCountsDocuments() {
        HylandCicFolder root = new HylandCicFolder("root-uuid", "Root", "/Root", null, Instant.now(), Instant.now(), List.of());
        when(client.findFolder("root-uuid")).thenReturn(Optional.of(root));

        HylandCicItem doc1 = new HylandCicItem("d-1", "Doc1", "/Root/Doc1", "root-uuid", "document", "Doc", 100L, "text/plain", Instant.now(), List.of());
        HylandCicItem doc2 = new HylandCicItem("d-2", "Doc2", "/Root/Doc2", "root-uuid", "document", "Doc", 200L, "text/plain", Instant.now(), List.of());
        when(client.getFolderItems(eq("root-uuid"), eq(0), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(doc1, doc2), 2L, false, null));

        DryRunRequest req = new DryRunRequest("root-uuid", 5, 100, Duration.ofSeconds(10));
        DryRunResult result = connector.dryRun(req, VisibilityFilter.ALL);

        assertThat(result.matchCount()).isEqualTo(2);
        assertThat(result.countAccuracy()).isEqualTo(CountAccuracy.EXACT);
        assertThat(result.samples()).hasSize(2);
    }

    @Test
    void fetchDocumentReturnsFoundWhenValid() {
        HylandCicDocument doc = new HylandCicDocument(
                "doc-valid-1", "Agreement.pdf", "/Root/Agreement.pdf", "root-uuid",
                "Legal", 1024L, "application/pdf", "hash123", "1.0",
                Instant.now(), Instant.now(), Map.of("Author", "Alice"),
                List.of(new HylandCicPermission("alice", "USER", "READ", true))
        );
        when(client.findDocument("doc-valid-1")).thenReturn(Optional.of(doc));
        when(client.downloadContent("doc-valid-1")).thenReturn(new ByteArrayInputStream("CONTENT".getBytes(StandardCharsets.UTF_8)));

        FetchedDocument fetched = connector.fetchDocument("doc-valid-1", VisibilityFilter.ALL);
        assertThat(fetched).isInstanceOf(FetchedDocument.Found.class);

        FetchedDocument.Found found = (FetchedDocument.Found) fetched;
        assertThat(found.node().sourceId()).isEqualTo("doc-valid-1");
        assertThat(found.document().id()).isEqualTo("hxp://documents/doc-valid-1");
        assertThat(found.document().metadata()).containsKey("custom.Author");
    }

    @Test
    void fetchDocumentReturnsGoneOnNotFound() {
        when(client.findDocument("doc-missing")).thenReturn(Optional.empty());

        FetchedDocument fetched = connector.fetchDocument("doc-missing", VisibilityFilter.ALL);
        assertThat(fetched).isInstanceOf(FetchedDocument.Gone.class);
        assertThat(((FetchedDocument.Gone) fetched).indexedId()).isEqualTo("hxp://documents/doc-missing");
    }

    @Test
    void fetchDocumentAntiProbingHidesOutOfScopeOrExcludedDocs() {
        properties.setExcludedFolderPaths(Set.of("/Root/Excluded"));
        HylandCicDocument doc = new HylandCicDocument(
                "doc-excluded", "Doc.pdf", "/Root/Excluded/Doc.pdf", "f-ex",
                "Legal", 100L, "text/plain", "hash", "1",
                Instant.now(), Instant.now(), Map.of(), List.of()
        );
        when(client.findDocument("doc-excluded")).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> connector.fetchDocument("doc-excluded", VisibilityFilter.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No HXP document 'doc-excluded' in this connector's scope.");
    }

    @Test
    void scanFolderEmitsFluxOfRepositoryDocuments() {
        HylandCicFolder root = new HylandCicFolder("root-uuid", "Root", "/Root", null, Instant.now(), Instant.now(), List.of());
        when(client.findFolder("root-uuid")).thenReturn(Optional.of(root));

        HylandCicItem item = new HylandCicItem("d-stream", "Doc.pdf", "/Root/Doc.pdf", "root-uuid", "document", "Legal", 100L, "text/plain", Instant.now(), List.of());
        when(client.getFolderItems(eq("root-uuid"), eq(0), anyInt()))
                .thenReturn(new HylandCicPagedResult(List.of(item), 1L, false, null));

        HylandCicDocument fullDoc = new HylandCicDocument(
                "d-stream", "Doc.pdf", "/Root/Doc.pdf", "root-uuid", "Legal",
                100L, "text/plain", "hash", "1", Instant.now(), Instant.now(), Map.of(), List.of()
        );
        when(client.findDocument("d-stream")).thenReturn(Optional.of(fullDoc));
        when(client.downloadContent("d-stream")).thenReturn(new ByteArrayInputStream("DATA".getBytes(StandardCharsets.UTF_8)));

        StepVerifier.create(connector.scan("root-uuid"))
                .assertNext(emitted -> {
                    assertThat(emitted.id()).isEqualTo("hxp://documents/d-stream");
                    assertThat(emitted.metadata()).containsEntry("contentType", List.of("Legal"));
                })
                .verifyComplete();
    }

    @Test
    void sourceInstanceIsTheConfiguredBaseUrl() {
        properties.setBaseUrl("https://api.content.example.com");
        assertThat(connector.sourceInstance()).isEqualTo("https://api.content.example.com");
        properties.setBaseUrl("");
        assertThat(connector.sourceInstance()).isNull();
    }
}
