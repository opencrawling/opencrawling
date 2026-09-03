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
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.DocumentAction;
import org.opencrawling.sdk.models.DocumentPayload;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testUpsertDocumentPayloadSerialization() throws Exception {
        DocumentPayload payload = new DocumentPayload(
            "doc-123",
            Map.of("type", "filesystem", "instance", "local"),
            Map.of("text", "Sample text"),
            Map.of("name", "test.txt"),
            Map.of("inheritanceEnabled", true)
        );

        assertEquals("doc-123", payload.id());
        assertEquals(DocumentAction.UPSERT, payload.action());

        String json = mapper.writeValueAsString(payload);
        assertTrue(json.contains("\"action\":\"UPSERT\""));
        assertTrue(json.contains("\"id\":\"doc-123\""));
    }

    @Test
    void testDeleteTombstonePayloadSerialization() throws Exception {
        DocumentPayload tombstone = DocumentPayload.createDeleteTombstone(
            "doc-123",
            Map.of("type", "filesystem", "instance", "local")
        );

        assertEquals("doc-123", tombstone.id());
        assertEquals(DocumentAction.DELETE, tombstone.action());
        assertNull(tombstone.content());
        assertNull(tombstone.metadata());
        assertNull(tombstone.security());

        String json = mapper.writeValueAsString(tombstone);
        assertTrue(json.contains("\"action\":\"DELETE\""));
        assertTrue(json.contains("\"id\":\"doc-123\""));
        assertFalse(json.contains("\"content\""));
    }
}
