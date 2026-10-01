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
package org.opencrawling.doxis.output.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.doxis.output.DoxisDatasetManager;
import org.opencrawling.doxis.output.DoxisRowMapper;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DoxisStoreWriterConsumerTest {

    private static final String DATASET_ID = "ds-1";

    private DoxisClient client;
    private DoxisStoreWriterConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(DoxisClient.class);
        DoxisDatasetManager datasetManager = mock(DoxisDatasetManager.class);
        when(datasetManager.datasetId()).thenReturn(DATASET_ID);
        DoxisOutputProperties props = DoxisOutputProperties.defaults();
        consumer = new DoxisStoreWriterConsumer(client, datasetManager, new DoxisRowMapper(props, new ObjectMapper()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertStoresChunkRowWithDeserializedSecurityMap() throws Exception {
        Map<String, Object> security = new HashMap<>();
        security.put("inheritanceEnabled", false);
        security.put("permissions", List.of(
                Map.of("identity", "alice", "identityType", "user", "access", "read"),
                Map.of("identity", "interns", "identityType", "group", "access", "deny")));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("uri", "sharepoint://contoso/items/42");
        metadata.put("title", List.of("Quarterly Report"));
        metadata.put("acl", "alice");
        metadata.put("security", security);
        metadata.put("lastModified", "2026-09-30T08:00:00Z");
        metadata.put("department", List.of("Finance"));

        when(client.findRowIds(DATASET_ID, "external_id", "doc-42_0")).thenReturn(List.of());
        when(client.insertRows(eq(DATASET_ID), anyList())).thenReturn(List.of("row-1"));

        consumer.consume(new DocumentEmbeddedMessage("doc-42", "doc-42_0", "Revenue grew 12%.", metadata, new float[]{0.1f, 0.2f}));

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).insertRows(eq(DATASET_ID), captor.capture());
        Map<String, Object> row = captor.getValue().getFirst();
        assertEquals("doc-42_0", row.get("external_id"));
        assertEquals("doc-42", row.get("document_id"));
        assertEquals("chunk", row.get("record_type"));
        assertEquals("Quarterly Report", row.get("title"));
        assertEquals("sharepoint", row.get("source_system"));
        assertEquals("Revenue grew 12%.", row.get("chunk_text"));
        assertEquals("alice", row.get("security_allowed_read"));
        assertEquals("interns", row.get("security_denied_read"));
        assertEquals(false, row.get("security_inheritance"));
        assertTrue(((String) row.get("metadata_json")).contains("Finance"));
        assertFalse(row.containsKey("document"));
    }

    @Test
    void upsertReplacesPreviousChunkRow() throws Exception {
        when(client.findRowIds(DATASET_ID, "external_id", "doc-42_0")).thenReturn(List.of("row-old"));
        when(client.insertRows(eq(DATASET_ID), anyList())).thenReturn(List.of("row-new"));

        consumer.consume(new DocumentEmbeddedMessage("doc-42", "doc-42_0", "text", Map.of(), new float[0]));

        verify(client).deleteRow(DATASET_ID, "row-old");
    }

    @Test
    void deleteTombstoneRemovesAllRowsOfDocument() throws Exception {
        when(client.findRowIds(DATASET_ID, "document_id", "doc-42")).thenReturn(List.of("row-1", "row-2"));

        consumer.consume(new DocumentEmbeddedMessage("doc-42", "doc-42", "", Map.of(), new float[0], DocumentAction.DELETE));

        verify(client).deleteRow(DATASET_ID, "row-1");
        verify(client).deleteRow(DATASET_ID, "row-2");
        verify(client, never()).insertRows(any(), any());
    }

    @Test
    void clientFailuresAreLoggedNotPropagated() throws Exception {
        when(client.findRowIds(any(), any(), any())).thenThrow(new IOException("HTTP 500"));

        assertDoesNotThrow(() -> consumer.consume(
                new DocumentEmbeddedMessage("doc-42", "doc-42_0", "text", Map.of(), new float[0])));
    }
}
