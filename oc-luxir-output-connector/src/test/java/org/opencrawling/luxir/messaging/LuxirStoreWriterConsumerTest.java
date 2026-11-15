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
package org.opencrawling.luxir.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.luxir.client.LuxirClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class LuxirStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromLuxir() throws Exception {
        LuxirClient luxirClient = mock(LuxirClient.class);
        LuxirStoreWriterConsumer consumer = new LuxirStoreWriterConsumer(luxirClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "opencrawling");
        ReflectionTestUtils.setField(consumer, "autoCommit", true);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-luxir-1",
                "chunk-luxir-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(luxirClient).deleteDocuments(eq("opencrawling"), eq(List.of("doc-luxir-1", "chunk-luxir-1")), eq(true));
    }

    @Test
    void aFailedDeleteIsRethrownSoKafkaRetriesIt() throws Exception {
        LuxirClient luxirClient = mock(LuxirClient.class);
        doThrow(new RuntimeException("luxir unavailable")).when(luxirClient).deleteDocuments(anyString(), anyList(), anyBoolean());
        LuxirStoreWriterConsumer consumer = new LuxirStoreWriterConsumer(luxirClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "opencrawling");
        ReflectionTestUtils.setField(consumer, "autoCommit", true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(
                new DocumentEmbeddedMessage("doc-luxir-1", "chunk-luxir-1", "", Map.of(), new float[0],
                        DocumentAction.DELETE)));
        assertTrue(thrown.getMessage().contains("chunk-luxir-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConsumeEmbeddedMessageIndexesDocumentWithAcls() throws Exception {
        LuxirClient luxirClient = mock(LuxirClient.class);
        LuxirStoreWriterConsumer consumer = new LuxirStoreWriterConsumer(luxirClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "opencrawling");
        ReflectionTestUtils.setField(consumer, "vectorFieldName", "embedding_v");
        ReflectionTestUtils.setField(consumer, "autoCommit", true);

        SecurityConfig sc = new SecurityConfig(
                true,
                List.of(
                        new PermissionRule("user:alice", "user", "Alice", "read"),
                        new PermissionRule("user:bob", "user", "Bob", "deny")
                )
        );

        DocumentEmbeddedMessage message = new DocumentEmbeddedMessage(
                "doc-luxir-2",
                "chunk-luxir-2",
                "Extracted text content for chunk 2",
                Map.of(
                        "uri", "file:///tmp/chunk2.txt",
                        "acl", "ROLE_ENGINEERING",
                        "title", "Sample Chunk Title",
                        "source", "sharepoint",
                        "security", sc
                ),
                new float[]{0.1f, 0.2f, 0.3f},
                DocumentAction.UPSERT
        );

        consumer.consume(message);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(luxirClient).updateDocuments(eq("opencrawling"), captor.capture(), eq(true));

        List<Map<String, Object>> captured = captor.getValue();
        assertEquals(1, captured.size());

        Map<String, Object> doc = captured.getFirst();
        assertEquals("chunk-luxir-2", doc.get("id"));
        assertEquals("doc-luxir-2", doc.get("doc_id_s"));
        assertEquals("Extracted text content for chunk 2", doc.get("text_t"));
        assertEquals("Sample Chunk Title", doc.get("title_t"));
        assertEquals("sharepoint", doc.get("source_s"));
        assertEquals("true", doc.get("security_inheritance_s"));

        List<String> allowed = (List<String>) doc.get("security_allowed_read_ss");
        assertTrue(allowed.contains("user:alice"));

        List<String> denied = (List<String>) doc.get("security_denied_read_ss");
        assertTrue(denied.contains("user:bob"));

        List<Float> embedding = (List<Float>) doc.get("embedding_v");
        assertEquals(3, embedding.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testConsumeEmbeddedMessageDoesNotCreateSingleValuedSecurityAllowedReadField() throws Exception {
        LuxirClient luxirClient = mock(LuxirClient.class);
        LuxirStoreWriterConsumer consumer = new LuxirStoreWriterConsumer(luxirClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "opencrawling");
        ReflectionTestUtils.setField(consumer, "vectorFieldName", "embedding_v");
        ReflectionTestUtils.setField(consumer, "autoCommit", true);

        DocumentEmbeddedMessage message = new DocumentEmbeddedMessage(
                "doc-luxir-3",
                "chunk-luxir-3",
                "Text content",
                Map.of(
                        "uri", "file:///data/luxir-test.txt",
                        "acl", "public",
                        // The writer derives the security fields from the security object (EffectiveAcl).
                        "security", Map.of("inheritanceEnabled", false, "permissions", List.of(
                                Map.of("identity", "public", "identityType", "public", "access", "read"),
                                Map.of("identity", "engineering", "identityType", "group", "access", "read"),
                                Map.of("identity", "contractor", "identityType", "group", "access", "deny"))),
                        "custom_tags", List.of("tag1", "tag2"),
                        "ois:provenance.jobId", List.of("job-7"),
                        "cmis:objectId", "abc"
                ),
                new float[]{0.5f},
                DocumentAction.UPSERT
        );

        consumer.consume(message);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(luxirClient).updateDocuments(eq("opencrawling"), captor.capture(), eq(true));

        Map<String, Object> doc = captor.getValue().getFirst();

        // Must NOT contain single-valued _s suffix for security fields
        org.junit.jupiter.api.Assertions.assertFalse(doc.containsKey("security_allowed_read_s"),
                "Field 'security_allowed_read_s' should not exist as it causes Luxir single-valued collision");
        org.junit.jupiter.api.Assertions.assertFalse(doc.containsKey("security_denied_read_s"));

        // Must map to multi-valued _ss in Luxir schema
        assertEquals(List.of("public", "group:engineering"), doc.get("security_allowed_read_ss"));
        assertEquals(List.of("group:contractor"), doc.get("security_denied_read_ss"));
        assertEquals("false", doc.get("security_inheritance_s"));

        // Custom multi-valued metadata should receive _ss suffix
        assertEquals(List.of("tag1", "tag2"), doc.get("custom_tags_ss"));

        // Luxir rejects ':' and '.' in field names, and with them the whole document
        assertEquals(List.of("job-7"), doc.get("ois_provenance_jobId_ss"));
        assertEquals("abc", doc.get("cmis_objectId_s"));
        org.junit.jupiter.api.Assertions.assertTrue(doc.keySet().stream().noneMatch(k -> k.contains(":") || k.contains(".")),
                "every field name must be valid in Luxir: " + doc.keySet());
    }
}
