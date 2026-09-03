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
package org.opencrawling.qdrant.messaging;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.qdrant.QdrantPointMapper;
import org.opencrawling.qdrant.config.QdrantOutputProperties;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class QdrantStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromQdrant() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        QdrantOutputProperties properties = new QdrantOutputProperties("localhost", 6334, null, "enterprise_kb", 384, QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 64);
        QdrantPointMapper mapper = new QdrantPointMapper();

        when(client.deleteAsync(eq("enterprise_kb"), any(List.class))).thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

        QdrantStoreWriterConsumer consumer = new QdrantStoreWriterConsumer(client, properties, mapper);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-qdrant-1",
                "chunk-qdrant-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(client).deleteAsync(eq("enterprise_kb"), any(List.class));
    }
}
