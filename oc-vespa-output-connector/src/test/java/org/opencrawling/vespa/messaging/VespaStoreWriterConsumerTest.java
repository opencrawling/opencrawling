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
package org.opencrawling.vespa.messaging;

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.vespa.VespaDocumentMapper;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VespaStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromVespa() throws Exception {
        FeedClient client = mock(FeedClient.class);
        VespaOutputProperties properties = new VespaOutputProperties("http://localhost:8080", "opencrawling", "opencrawling_chunk", 384, 10, false, null, null, null);
        VespaDocumentMapper mapper = new VespaDocumentMapper();

        when(client.remove(any(DocumentId.class), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(Result.class)));

        VespaStoreWriterConsumer consumer = new VespaStoreWriterConsumer(client, properties, mapper);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-vespa-1",
                "chunk-vespa-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(client).remove(eq(DocumentId.of("opencrawling", "opencrawling_chunk", "chunk-vespa-1")), any(OperationParameters.class));
    }
}
