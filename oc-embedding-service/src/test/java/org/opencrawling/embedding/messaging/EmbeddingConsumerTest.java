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
package org.opencrawling.embedding.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.embedding.EmbeddingModelFactory;
import org.opencrawling.embedding.config.KafkaConfig;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbeddingConsumerTest {

    private final EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private EmbeddingConsumer consumer;

    @BeforeEach
    void setUp() {
        when(modelFactory.getModel(any(), any())).thenReturn(model);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(anyString(), anyString(), any());
        consumer = new EmbeddingConsumer(modelFactory, kafkaTemplate);
    }

    @Test
    void anEmbeddedChunkIsPublishedUnderItsDocumentId() {
        when(model.embed(anyString())).thenReturn(new float[] {0.5f, 0.5f});

        consumer.consume(chunk());

        verify(kafkaTemplate).send(eq(KafkaConfig.EMBEDDED_TOPIC_NAME), eq("doc-1"), any(DocumentEmbeddedMessage.class));
    }

    @Test
    void aFailedEmbeddingIsRethrownSoKafkaRetriesIt() {
        // For example while the model server is down: the chunk must be retried, not lost.
        when(model.embed(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> consumer.consume(chunk()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-1")
                .hasRootCauseMessage("connection refused");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aFailedPublishIsRethrownSoKafkaRetriesIt() {
        when(model.embed(anyString())).thenReturn(new float[] {0.5f, 0.5f});
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")))
                .when(kafkaTemplate).send(anyString(), anyString(), any());

        assertThatThrownBy(() -> consumer.consume(chunk()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-1");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static DocumentChunkMessage chunk() {
        return new DocumentChunkMessage("doc-1", "chunk-1", "some text", Map.of(), null, "ollama", Map.of());
    }
}
