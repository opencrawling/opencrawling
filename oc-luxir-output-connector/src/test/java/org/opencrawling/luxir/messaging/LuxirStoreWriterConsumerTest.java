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
}
