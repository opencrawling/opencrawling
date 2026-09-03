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
package org.opencrawling.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.DocumentAction;
import org.opencrawling.sdk.models.DocumentPayload;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit test suite for Open Ingestion Standard (OIS) Document Lifecycle Actions
 * and Tombstone Deletions in the Java Client SDK.
 */
class DocumentPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Unit Test: Standard UPSERT Document Payload Jackson Serialization")
    void testUpsertDocumentPayloadSerialization() throws Exception {
        DocumentPayload payload = new DocumentPayload(
            "doc-upsert-101",
            Map.of("type", "filesystem", "instance", "local"),
            Map.of("text", "Sample document content"),
            Map.of("name", "architecture.pdf", "mimeType", "application/pdf"),
            Map.of("inheritanceEnabled", true)
        );

        assertEquals("doc-upsert-101", payload.id());
        assertEquals(DocumentAction.UPSERT, payload.action());
        assertNotNull(payload.content());
        assertNotNull(payload.metadata());
        assertNotNull(payload.security());

        String json = mapper.writeValueAsString(payload);
        assertTrue(json.contains("\"action\":\"UPSERT\""));
        assertTrue(json.contains("\"id\":\"doc-upsert-101\""));
        assertTrue(json.contains("\"content\""));
        assertTrue(json.contains("\"metadata\""));
    }

    @Test
    @DisplayName("Unit Test: Tombstone DELETE Payload Factory & Jackson Serialization")
    void testDeleteTombstonePayloadSerialization() throws Exception {
        DocumentPayload tombstone = DocumentPayload.createDeleteTombstone(
            "doc-delete-202",
            Map.of("type", "kafka", "instance", "decoupled-cluster")
        );

        assertEquals("doc-delete-202", tombstone.id());
        assertEquals(DocumentAction.DELETE, tombstone.action());
        assertNotNull(tombstone.source());
        assertNull(tombstone.content());
        assertNull(tombstone.metadata());
        assertNull(tombstone.security());

        String json = mapper.writeValueAsString(tombstone);
        assertTrue(json.contains("\"action\":\"DELETE\""));
        assertTrue(json.contains("\"id\":\"doc-delete-202\""));
        assertFalse(json.contains("\"content\""), "Tombstone DELETE JSON must omit null content payload");
        assertFalse(json.contains("\"metadata\""), "Tombstone DELETE JSON must omit null metadata payload");
        assertFalse(json.contains("\"security\""), "Tombstone DELETE JSON must omit null security payload");
    }

    @Test
    @DisplayName("Unit Test: Tombstone DELETE Payload JSON Deserialization")
    void testDeleteTombstonePayloadDeserialization() throws Exception {
        String tombstoneJson = """
            {
              "id": "doc-tombstone-303",
              "action": "DELETE",
              "source": {
                "type": "sharepoint",
                "site": "engineering"
              }
            }
            """;

        DocumentPayload deserialized = mapper.readValue(tombstoneJson, DocumentPayload.class);
        assertEquals("doc-tombstone-303", deserialized.id());
        assertEquals(DocumentAction.DELETE, deserialized.action());
        assertEquals("sharepoint", deserialized.source().get("type"));
        assertNull(deserialized.content());
        assertNull(deserialized.metadata());
    }

    @Test
    @DisplayName("Unit Test: DocumentAction Enum Value Integrity")
    void testDocumentActionEnumValues() {
        assertEquals(2, DocumentAction.values().length);
        assertEquals(DocumentAction.UPSERT, DocumentAction.valueOf("UPSERT"));
        assertEquals(DocumentAction.DELETE, DocumentAction.valueOf("DELETE"));
    }
}
