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
package org.opencrawling.hyland.cic.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.hyland.cic.client.HylandCicClient;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.opencrawling.hyland.cic.model.HylandCicDocument;
import org.opencrawling.hyland.cic.model.HylandCicFolder;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HylandCicOutputConnectorTest {

    private HylandCicProperties properties;
    private HylandCicClient client;
    private HylandCicOutputConnector connector;

    @BeforeEach
    void setUp() {
        properties = new HylandCicProperties();
        properties.setTargetFolderId("target-folder-123");
        properties.setDefaultContentType("ArchivedDocument");

        client = mock(HylandCicClient.class);
        connector = new HylandCicOutputConnector(properties, client);
    }

    @Test
    void metadataAndContract() {
        assertThat(connector.getName()).isEqualTo("HylandCicOutputConnector");
        assertThat(connector.getProperties()).isSameAs(properties);
        assertThat(connector.getClient()).isSameAs(client);
    }

    @Test
    void sendUpsertUploadsDocumentToHxp() {
        HylandCicDocument uploadedDoc = new HylandCicDocument(
                "uploaded-uuid-1", "Invoice.pdf", "/Target/Invoice.pdf", "target-folder-123",
                "ArchivedDocument", 100L, "application/pdf", "hash", "1.0",
                Instant.now(), Instant.now(), Map.of(), List.of()
        );
        when(client.createDocument(eq("target-folder-123"), eq("Invoice.pdf"), eq("ArchivedDocument"), any(), eq("application/pdf"), anyMap()))
                .thenReturn(uploadedDoc);

        RepositoryDocument document = new RepositoryDocument(
                "hxp://documents/orig-1",
                "https://source.test/Invoice.pdf",
                new ByteArrayInputStream("TEST_BINARY".getBytes(StandardCharsets.UTF_8)),
                Map.of(
                        "name", List.of("Invoice.pdf"),
                        "mimeType", List.of("application/pdf"),
                        "contentType", List.of("ArchivedDocument"),
                        "Vendor", List.of("Acme Corp")
                ),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        StepVerifier.create(connector.send(document))
                .verifyComplete();

        verify(client, times(1)).createDocument(
                eq("target-folder-123"),
                eq("Invoice.pdf"),
                eq("ArchivedDocument"),
                any(),
                eq("application/pdf"),
                argThat(m -> "Acme Corp".equals(m.get("Vendor")))
        );
    }

    @Test
    void sendDeleteDeletesDocumentInHxp() {
        when(client.deleteDocument("del-123")).thenReturn(true);

        RepositoryDocument tombstone = RepositoryDocument.createTombstone(
                "hxp://documents/del-123",
                "https://source.test/del-123"
        );

        StepVerifier.create(connector.send(tombstone))
                .verifyComplete();

        verify(client, times(1)).deleteDocument("del-123");
    }

    @Test
    void connectPreResolvesTargetFolderPath() throws Exception {
        properties.setTargetFolderId(""); // clear ID so path resolution is triggered
        properties.setTargetFolderPath("/Archive/2026");

        HylandCicFolder folder = new HylandCicFolder(
                "resolved-f-id", "2026", "/Archive/2026", "parent-id",
                Instant.now(), Instant.now(), List.of()
        );
        when(client.checkHealth()).thenReturn(true);
        when(client.findFolder("/Archive/2026")).thenReturn(Optional.of(folder));

        connector.connect();

        verify(client, times(1)).checkHealth();
        verify(client, times(1)).findFolder("/Archive/2026");
    }
}
