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
package org.opencrawling.doxis.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ConflictResolution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DoxisOutputConnectorTest {

    private static final String DATASET_ID = "ds-1";

    private DoxisClient client;
    private DoxisDatasetManager datasetManager;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(DoxisClient.class);
        datasetManager = mock(DoxisDatasetManager.class);
        when(datasetManager.datasetId()).thenReturn(DATASET_ID);
    }

    private DoxisOutputConnector connector(ConflictResolution resolution) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "key", DATASET_ID, null, true, true, true, true,
                resolution, 0, 10);
        return new DoxisOutputConnector(client, props, datasetManager, new DoxisRowMapper(props, new ObjectMapper()));
    }

    private RepositoryDocument document(String content) {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("elena.weber", "user", "Elena Weber", "write"),
                new PermissionRule("group:legal-counsel", "group", "Legal", "read"),
                new PermissionRule("contractors", "group", "Contractors", "deny")));
        return new RepositoryDocument("doc-1", "file:///data/contracts/msa.pdf",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                Map.of("name", List.of("msa.pdf"), "title", List.of("Master Services Agreement"),
                        "mimeType", List.of("application/pdf"), "department", List.of("Legal")),
                "elena.weber,group:legal-counsel", security, Instant.parse("2026-09-30T08:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertInsertsDocumentRowWithContentDescriptorsAndAcls() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-1")).thenReturn(List.of());
        when(client.insertRows(eq(DATASET_ID), anyList())).thenReturn(List.of("row-new"));

        connector(ConflictResolution.REPLACE).send(document("%PDF-1.7 contract")).block();

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).insertRows(eq(DATASET_ID), captor.capture());
        Map<String, Object> row = captor.getValue().getFirst();
        assertEquals("doc-1", row.get("external_id"));
        assertEquals("doc-1", row.get("document_id"));
        assertEquals("document", row.get("record_type"));
        assertEquals("Master Services Agreement", row.get("title"));
        assertEquals("file", row.get("source_system"));
        assertEquals("application/pdf", row.get("mime_type"));
        assertEquals(17, row.get("content_length"));
        assertEquals("2026-09-30T08:00:00Z", row.get("last_modified"));
        assertEquals("elena.weber,group:legal-counsel", row.get("acl"));
        assertEquals("elena.weber,group:legal-counsel", row.get("security_allowed_read"));
        assertEquals("contractors", row.get("security_denied_read"));
        assertEquals(true, row.get("security_inheritance"));
        assertTrue(((String) row.get("security_json")).contains("\"access\":\"deny\""));
        assertEquals("{\"department\":\"Legal\"}", row.get("metadata_json"));

        Map<String, Object> documentCell = (Map<String, Object>) row.get("document");
        assertEquals("msa.pdf", documentCell.get("filename"));
        assertEquals("application/pdf", documentCell.get("content_type"));
        assertEquals("%PDF-1.7 contract", new String(Base64.getDecoder().decode((String) documentCell.get("data")), StandardCharsets.UTF_8));
        verify(client, never()).deleteRow(any(), any());
    }

    @Test
    void replaceInsertsNewRowBeforeDeletingPreviousRows() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-1")).thenReturn(List.of("row-old-1", "row-old-2"));
        when(client.insertRows(eq(DATASET_ID), anyList())).thenReturn(List.of("row-new"));

        connector(ConflictResolution.REPLACE).send(document("v2")).block();

        InOrder order = inOrder(client);
        order.verify(client).insertRows(eq(DATASET_ID), anyList());
        order.verify(client).deleteRow(DATASET_ID, "row-old-1");
        order.verify(client).deleteRow(DATASET_ID, "row-old-2");
    }

    @Test
    void failedInsertKeepsPreviousRows() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-1")).thenReturn(List.of("row-old"));
        when(client.insertRows(eq(DATASET_ID), anyList())).thenThrow(new IOException("HTTP 413"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> connector(ConflictResolution.REPLACE).send(document("too big")).block());

        assertTrue(e.getMessage().contains("doc-1"));
        verify(client, never()).deleteRow(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateMetadataPatchesExistingRowsWithoutTouchingContent() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-1")).thenReturn(List.of("row-1"));

        connector(ConflictResolution.UPDATE_METADATA).send(document("same")).block();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).patchRow(eq(DATASET_ID), eq("row-1"), captor.capture());
        Map<String, Object> cells = captor.getValue();
        assertEquals("Master Services Agreement", cells.get("title"));
        assertFalse(cells.containsKey("document"));
        assertFalse(cells.containsKey("external_id"));
        verify(client, never()).insertRows(any(), any());
    }

    @Test
    void updateMetadataInsertsWhenDocumentIsNew() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-1")).thenReturn(List.of());
        when(client.insertRows(eq(DATASET_ID), anyList())).thenReturn(List.of("row-new"));

        connector(ConflictResolution.UPDATE_METADATA).send(document("new")).block();

        verify(client).insertRows(eq(DATASET_ID), anyList());
        verify(client, never()).patchRow(any(), any(), any());
    }

    @Test
    void deleteTombstoneRemovesEveryRowOfTheDocument() throws Exception {
        when(client.findRowIds(DATASET_ID, "document_id", "doc-1")).thenReturn(List.of("row-doc", "row-chunk-1"));

        connector(ConflictResolution.REPLACE).send(RepositoryDocument.createTombstone("doc-1", "file:///data/msa.pdf")).block();

        verify(client).deleteRow(DATASET_ID, "row-doc");
        verify(client).deleteRow(DATASET_ID, "row-chunk-1");
        verify(client, never()).insertRows(any(), any());
    }

    @Test
    void spiInstanceRefusesToSend() {
        DoxisOutputConnector spi = new DoxisOutputConnector();
        assertEquals("DoxisOutputConnector", spi.getName());
        assertThrows(IllegalStateException.class, () -> spi.send(document("x")).block());
    }
}
