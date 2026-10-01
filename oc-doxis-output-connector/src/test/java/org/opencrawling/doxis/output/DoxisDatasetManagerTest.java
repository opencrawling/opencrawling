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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DoxisDatasetManagerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DoxisClient client;

    @BeforeEach
    void setUp() {
        client = mock(DoxisClient.class);
    }

    private DoxisOutputProperties props(String datasetId, boolean autoCreate) {
        return new DoxisOutputProperties(null, "key", datasetId, "OpenCrawling Ingestion", autoCreate, true, true, true,
                null, 0, 10);
    }

    private JsonNode dataset(String id, String... columnSlugs) throws Exception {
        StringBuilder columns = new StringBuilder();
        for (String slug : columnSlugs) {
            if (!columns.isEmpty()) columns.append(',');
            columns.append("{\"slug\":\"").append(slug).append("\"}");
        }
        return objectMapper.readTree("{\"id\":\"" + id + "\",\"name\":\"OpenCrawling Ingestion\",\"columns\":[" + columns + "]}");
    }

    @Test
    void configuredDatasetIdAddsOnlyMissingColumns() throws Exception {
        when(client.getDataset("ds-1")).thenReturn(dataset("ds-1", "external_id", "document_id"));

        DoxisDatasetManager manager = new DoxisDatasetManager(client, props("ds-1", true));

        assertEquals("ds-1", manager.datasetId());
        verify(client, never()).addColumn(eq("ds-1"), eq("external_id"), any());
        verify(client, never()).addColumn(eq("ds-1"), eq("document_id"), any());
        verify(client).addColumn("ds-1", "document", "document");
        verify(client).addColumn("ds-1", "content_length", "int");
        verify(client, times(DoxisConstants.COLUMNS.size() - 2)).addColumn(eq("ds-1"), any(), any());
        verify(client, never()).listDatasets();
    }

    @Test
    void resolvesExistingDatasetByName() throws Exception {
        String[] allColumns = DoxisConstants.COLUMNS.keySet().toArray(String[]::new);
        when(client.listDatasets()).thenReturn(List.of(
                objectMapper.readTree("{\"id\":\"ds-other\",\"name\":\"Invoices\"}"),
                objectMapper.readTree("{\"id\":\"ds-2\",\"name\":\"OpenCrawling Ingestion\"}")));
        when(client.getDataset("ds-2")).thenReturn(dataset("ds-2", allColumns));

        DoxisDatasetManager manager = new DoxisDatasetManager(client, props(null, true));

        assertEquals("ds-2", manager.datasetId());
        verify(client, never()).createDataset(any(), any());
        verify(client, never()).addColumn(any(), any(), any());
    }

    @Test
    void createsDatasetWhenMissingAndCachesTheId() throws Exception {
        when(client.listDatasets()).thenReturn(List.of());
        when(client.createDataset("OpenCrawling Ingestion", DoxisConstants.COLUMNS)).thenReturn("ds-new");

        DoxisDatasetManager manager = new DoxisDatasetManager(client, props(null, true));

        assertEquals("ds-new", manager.datasetId());
        assertEquals("ds-new", manager.datasetId());
        verify(client, times(1)).listDatasets();
        verify(client, times(1)).createDataset(any(), any());
    }

    @Test
    void failsWhenMissingAndAutoCreateDisabled() throws Exception {
        when(client.listDatasets()).thenReturn(List.of());

        DoxisDatasetManager manager = new DoxisDatasetManager(client, props(null, false));

        IOException e = assertThrows(IOException.class, manager::datasetId);
        assertTrue(e.getMessage().contains("auto-create-dataset"));
        verify(client, never()).createDataset(any(), any());
    }
}
