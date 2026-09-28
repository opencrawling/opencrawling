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
package org.opencrawling.seatunnel.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.seatunnel.client.SeaTunnelRestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeaTunnelStoreWriterConsumerTest {

    private SeaTunnelRestClient restClient;
    private SeaTunnelStoreWriterConsumer consumer;

    @BeforeEach
    void setUp() {
        restClient = Mockito.mock(SeaTunnelRestClient.class);
        consumer = new SeaTunnelStoreWriterConsumer(restClient);
    }

    @Test
    void testConsume_upsertChunk() {
        DocumentEmbeddedMessage message = new DocumentEmbeddedMessage(
                "doc-1",
                "chunk-1",
                "Text content for SeaTunnel",
                Map.of("uri", "file:///test.txt"),
                new float[]{0.1f, 0.2f, 0.3f},
                DocumentAction.UPSERT
        );

        consumer.consume(message);

        assertThat(consumer.getUpsertCount()).isEqualTo(1);
        assertThat(consumer.getDeleteCount()).isEqualTo(0);
    }

    @Test
    void testConsume_deleteTombstone() {
        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-1",
                "doc-1",
                "",
                Map.of("action", "DELETE"),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        assertThat(consumer.getUpsertCount()).isEqualTo(0);
        assertThat(consumer.getDeleteCount()).isEqualTo(1);
    }

    @Test
    void testConsume_nullMessage() {
        consumer.consume(null);

        assertThat(consumer.getUpsertCount()).isEqualTo(0);
        assertThat(consumer.getDeleteCount()).isEqualTo(0);
    }
}
