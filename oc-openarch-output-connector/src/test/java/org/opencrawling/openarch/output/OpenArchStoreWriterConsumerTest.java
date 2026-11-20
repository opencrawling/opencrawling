/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.openarch.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import org.opencrawling.openarch.output.messaging.OpenArchStoreWriterConsumer;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpenArchStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneRecordsTombstone() {
        OpenArchClient client = mock(OpenArchClient.class);
        OpenArchOutputProperties properties = new OpenArchOutputProperties();
        OpenArchStoreWriterConsumer consumer = new OpenArchStoreWriterConsumer(client, properties);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-openarch-1",
                "chunk-openarch-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        assertDoesNotThrow(() -> consumer.consume(tombstone));
        verifyNoInteractions(client);
    }

    @Test
    void testConsumeEmbeddedMessageFlushesParquetBatch() throws Exception {
        OpenArchClient client = mock(OpenArchClient.class);
        when(client.uploadBlob(any(InputStream.class), anyString(), anyString()))
                .thenReturn(new ObjectMapper().readTree("{\"id\":\"blob-parquet-123\"}"));

        OpenArchOutputProperties properties = new OpenArchOutputProperties();
        properties.setParquetBatchThreshold(1); // Flush immediately on 1 chunk

        OpenArchStoreWriterConsumer consumer = new OpenArchStoreWriterConsumer(client, properties);

        DocumentEmbeddedMessage message = new DocumentEmbeddedMessage(
                "doc-openarch-2",
                "chunk-openarch-2",
                "OpenArch federated ingestion content chunk for verification",
                Map.of("title", "Test Title"),
                new float[]{0.1f, 0.2f, 0.3f},
                DocumentAction.UPSERT
        );

        consumer.consume(message);

        verify(client, times(1)).uploadBlob(any(InputStream.class), contains(".parquet"), eq("application/vnd.apache.parquet"));
    }

    /** #333: an upsert without its real vector is refused, and nothing is buffered or uploaded. */
    @Test
    void anUpsertWithoutAnEmbeddingIsRefused() {
        OpenArchClient client = mock(OpenArchClient.class);
        OpenArchOutputProperties properties = new OpenArchOutputProperties();
        properties.setParquetBatchThreshold(1);
        OpenArchStoreWriterConsumer consumer = new OpenArchStoreWriterConsumer(client, properties);

        for (float[] missing : new float[][] {null, new float[0]}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.consume(new DocumentEmbeddedMessage(
                            "doc-333", "chunk-333", "text", Map.of(), missing, DocumentAction.UPSERT)))
                    .hasMessage("chunk chunk-333 of document doc-333 has no embedding");
        }
        verifyNoInteractions(client);
    }

    /**
     * #333: a failed batch upload is rethrown so Kafka retries the record, and the batch is kept: the retry flushes
     * every chunk of it once, the retried record's chunk included.
     */
    @Test
    void aFailedBatchUploadIsRethrownAndTheBatchIsKeptForTheRetry() throws Exception {
        OpenArchClient client = mock(OpenArchClient.class);
        when(client.uploadBlob(any(InputStream.class), anyString(), anyString()))
                .thenThrow(new java.io.IOException("OpenArch unavailable"))
                .thenReturn(new ObjectMapper().readTree("{\"id\":\"blob-1\"}"));
        OpenArchOutputProperties properties = new OpenArchOutputProperties();
        properties.setParquetBatchThreshold(2);
        OpenArchStoreWriterConsumer consumer = new OpenArchStoreWriterConsumer(client, properties);
        DocumentEmbeddedMessage first = new DocumentEmbeddedMessage("doc-1", "chunk-1", "first", Map.of(),
                new float[] {0.1f, 0.2f}, DocumentAction.UPSERT);
        DocumentEmbeddedMessage second = new DocumentEmbeddedMessage("doc-1", "chunk-2", "second", Map.of(),
                new float[] {0.3f, 0.4f}, DocumentAction.UPSERT);

        consumer.consume(first);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.consume(second))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("OpenArch unavailable");
        org.assertj.core.api.Assertions.assertThat(buffered(consumer)).as("the first chunk is kept, the retried one is not doubled")
                .hasSize(1);
        consumer.consume(second); // Kafka's retry of the same record

        verify(client, times(2)).uploadBlob(any(InputStream.class), contains(".parquet"), eq("application/vnd.apache.parquet"));
        org.assertj.core.api.Assertions.assertThat(buffered(consumer)).isEmpty();
    }

    private static java.util.List<?> buffered(OpenArchStoreWriterConsumer consumer) throws Exception {
        java.lang.reflect.Field buffer = OpenArchStoreWriterConsumer.class.getDeclaredField("chunkBuffer");
        buffer.setAccessible(true);
        return (java.util.List<?>) buffer.get(consumer);
    }
}
