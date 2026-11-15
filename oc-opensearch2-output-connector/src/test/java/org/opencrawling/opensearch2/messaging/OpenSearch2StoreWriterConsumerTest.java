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
package org.opencrawling.opensearch2.messaging;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenSearch2StoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromOpenSearch2() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        OpenSearch2StoreWriterConsumer consumer = new OpenSearch2StoreWriterConsumer(client);
        ReflectionTestUtils.setField(consumer, "indexName", "enterprise_kb");

        when(client.delete(any(Function.class))).thenReturn(mock(DeleteResponse.class));

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-opensearch2-1",
                "chunk-opensearch2-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(client).delete(any(Function.class));
    }

    @Test
    void aFailedDeleteIsRethrownSoKafkaRetriesIt() throws Exception {
        // The document purge used to be swallowed at debug level, so a failed tombstone was lost without trace.
        OpenSearchClient client = mock(OpenSearchClient.class);
        when(client.deleteByQuery(any(Function.class))).thenThrow(new java.io.IOException("opensearch unavailable"));
        OpenSearch2StoreWriterConsumer consumer = new OpenSearch2StoreWriterConsumer(client);
        ReflectionTestUtils.setField(consumer, "indexName", "enterprise_kb");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> consumer.consume(
                new DocumentEmbeddedMessage("doc-opensearch2-1", "chunk-opensearch2-1", "", Map.of(), new float[0],
                        DocumentAction.DELETE)));
        assertTrue(thrown.getMessage().contains("chunk-opensearch2-1"));
    }

    @Test
    void aFailedWriteIsRethrownSoKafkaRetriesIt() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        when(client.index(any(Function.class))).thenThrow(new java.io.IOException("opensearch unavailable"));
        OpenSearch2StoreWriterConsumer consumer = new OpenSearch2StoreWriterConsumer(client);
        ReflectionTestUtils.setField(consumer, "indexName", "enterprise_kb");

        assertThrows(IllegalStateException.class, () -> consumer.consume(
                new DocumentEmbeddedMessage("doc-opensearch2-1", "chunk-opensearch2-1", "text", Map.of(),
                        new float[]{1f, 0f}, DocumentAction.UPSERT)));
    }
}
