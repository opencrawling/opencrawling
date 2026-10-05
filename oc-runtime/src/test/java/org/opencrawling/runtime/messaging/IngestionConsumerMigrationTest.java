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
package org.opencrawling.runtime.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionConsumerMigrationTest {

    private KafkaTemplate<String, Object> kafkaTemplate;
    private ClaimCheckStore claimCheckStore;
    private ClaimCheckProperties claimCheckProperties;
    private TelemetryTraceStore traceStore;
    private TextExtractionService textExtractionService;
    private IngestionConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        claimCheckProperties = new ClaimCheckProperties();
        traceStore = mock(TelemetryTraceStore.class);
        textExtractionService = mock(TextExtractionService.class);

        consumer = new IngestionConsumer(
                kafkaTemplate,
                claimCheckStore,
                claimCheckProperties,
                traceStore,
                textExtractionService
        );
    }

    @Test
    void testMigrationModeSkipsExtractionAndChunkPublishing() {
        IngestionMessage message = new IngestionMessage(
                "doc-mig-test",
                "file:///tmp/doc.pdf",
                Map.of("title", List.of("Title")),
                "read:all",
                SecurityConfig.createPublic(),
                "2026-10-05T20:00:00Z",
                null,
                "ollama",
                Map.of(),
                DocumentAction.UPSERT,
                PipelineMode.MIGRATION
        );

        consumer.consume(message);

        // Verify that no chunk messages were published to Kafka
        verify(kafkaTemplate, never()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), any());
        // Verify text extraction was never called
        verify(textExtractionService, never()).extractText(any(byte[].class), any());
        // Verify claim check store was not read by IngestionConsumer
        verifyNoInteractions(claimCheckStore);
    }
}
