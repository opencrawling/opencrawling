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
package org.opencrawling.core.messaging;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePartitionKeyTest {

    @Test
    void chunkMessagesAreKeyedByTheirDocument() {
        DocumentChunkMessage upsert = new DocumentChunkMessage("doc-1", "chunk-7", "text", Map.of(), null, null, Map.of());
        DocumentChunkMessage delete = new DocumentChunkMessage("doc-1", "doc-1", "", Map.of(), null, null, Map.of(),
                DocumentAction.DELETE);

        assertThat(upsert.partitionKey()).isEqualTo("doc-1");
        assertThat(delete.partitionKey()).isEqualTo(upsert.partitionKey());
    }

    @Test
    void embeddedMessagesAreKeyedByTheirDocument() {
        DocumentEmbeddedMessage upsert = new DocumentEmbeddedMessage("doc-1", "chunk-7", "text", Map.of(), new float[] {1f});
        DocumentEmbeddedMessage delete = new DocumentEmbeddedMessage("doc-1", "doc-1", "", Map.of(), new float[0],
                DocumentAction.DELETE);

        assertThat(upsert.partitionKey()).isEqualTo("doc-1");
        assertThat(delete.partitionKey()).isEqualTo(upsert.partitionKey());
    }

    @Test
    void aMessageWithoutADocumentIdFallsBackToItsChunkId() {
        assertThat(new DocumentChunkMessage(null, "chunk-7", "t", Map.of(), null, null, Map.of()).partitionKey())
                .isEqualTo("chunk-7");
        assertThat(new DocumentEmbeddedMessage(" ", "chunk-7", "t", Map.of(), new float[0]).partitionKey())
                .isEqualTo("chunk-7");
    }
}
