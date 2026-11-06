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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/** A job's own chunking, carried on {@link IngestionMessage#chunking()}. */
class IngestionConsumerChunkingTest {

    private static final String TEXT = IntStream.range(0, 3000)
            .mapToObj(i -> "word" + i + ".")
            .collect(Collectors.joining(" "));

    private KafkaTemplate<String, Object> kafkaTemplate;
    private IngestionConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(anyString(), anyString(), any());
        ClaimCheckStore claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.get(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));
        TextExtractionService textExtractionService = mock(TextExtractionService.class);
        when(textExtractionService.extractText(any(byte[].class), any()))
                .thenReturn(TextExtractionResult.success(TEXT, Map.of(), false));
        consumer = new IngestionConsumer(kafkaTemplate, claimCheckStore, new ClaimCheckProperties(), null, textExtractionService);
    }

    @Test
    void aJobsChunkSizeIsApplied() {
        List<DocumentChunkMessage> defaults = chunks(null);
        List<DocumentChunkMessage> smaller = chunks(new ChunkingSpec(200, null, null, null));

        assertThat(defaults.size()).isGreaterThan(1);
        assertThat(smaller.size()).isGreaterThan(defaults.size() * 3);
        // Chunk ids still come from the document and the position, so a re-chunked document rewrites its rows,
        // and total_chunks lets the writer drop the rows past the new length.
        assertThat(smaller.subList(0, defaults.size())).extracting(DocumentChunkMessage::chunkId)
                .containsExactlyElementsOf(defaults.stream().map(DocumentChunkMessage::chunkId).toList());
        assertThat(defaults.get(0).metadata().get("total_chunks")).isEqualTo(defaults.size());
        assertThat(smaller.get(0).metadata().get("total_chunks")).isEqualTo(smaller.size());
    }

    @Test
    void theMinimumsAndSeparatorSettingsAreApplied() {
        String lines = IntStream.range(0, 400).mapToObj(i -> "line" + i).collect(Collectors.joining("\n"));
        assertThat(split(lines, new ChunkingSpec(100, null, null, null))).allSatisfy(chunk -> assertThat(chunk).contains("\n"));
        assertThat(split(lines, new ChunkingSpec(100, null, null, false))).allSatisfy(chunk -> assertThat(chunk).doesNotContain("\n"));
        // A chunk shorter than minChunkLengthToEmbed is dropped: here, every chunk.
        assertThat(split(lines, new ChunkingSpec(100, null, 100_000, null))).isEmpty();
        // The splitter cuts a chunk at its last sentence end past minChunkSizeChars.
        String sentences = IntStream.range(0, 400).mapToObj(i -> "Sentence number " + i + " ends here.").collect(Collectors.joining(" "));
        assertThat(split(sentences, new ChunkingSpec(100, 1, null, null)).getFirst())
                .isNotEqualTo(split(sentences, new ChunkingSpec(100, 100_000, null, null)).getFirst());
    }

    private List<String> split(String text, ChunkingSpec chunking) {
        return consumer.splitterFor(message(chunking)).apply(List.of(new Document("d", text, Map.of())))
                .stream().map(Document::getText).toList();
    }

    @Test
    void theDefaultsAreTokenTextSplittersOwn() {
        // The defaults ChunkingSpec documents must be the splitter's, or "unset" would change the chunks.
        ChunkingSpec explicitDefaults = new ChunkingSpec(ChunkingSpec.DEFAULT_CHUNK_SIZE,
                ChunkingSpec.DEFAULT_MIN_CHUNK_SIZE_CHARS, ChunkingSpec.DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED, true);
        List<String> splitterDefault = TokenTextSplitter.builder().build()
                .apply(List.of(new Document("d", TEXT, Map.of()))).stream().map(Document::getText).toList();

        assertThat(chunks(explicitDefaults)).extracting(DocumentChunkMessage::text)
                .containsExactlyElementsOf(splitterDefault);
        assertThat(chunks(null)).extracting(DocumentChunkMessage::text).containsExactlyElementsOf(splitterDefault);
    }

    @Test
    void chunkingOutsideTheLimitsFallsBackToTheDefault() {
        IngestionMessage beyond = message(new ChunkingSpec(100_000, null, null, null));
        IngestionMessage tooMany = message(new ChunkingSpec(10, -5, null, null));

        assertThat(consumer.splitterFor(beyond)).isSameAs(consumer.splitterFor(message(null)));
        assertThat(consumer.splitterFor(tooMany)).isSameAs(consumer.splitterFor(message(null)));
    }

    @Test
    void splittersAreReusedForTheSameChunking() {
        TokenTextSplitter first = consumer.splitterFor(message(new ChunkingSpec(300, null, null, null)));
        TokenTextSplitter again = consumer.splitterFor(message(new ChunkingSpec(300, null, null, null)));
        TokenTextSplitter other = consumer.splitterFor(message(new ChunkingSpec(301, null, null, null)));

        assertThat(again).isSameAs(first);
        assertThat(other).isNotSameAs(first);
    }

    @Test
    void messagesWithAndWithoutChunkingCrossTheTopic() {
        JacksonJsonSerializer<Object> serializer = new JacksonJsonSerializer<>();
        JacksonJsonDeserializer<Object> deserializer = new JacksonJsonDeserializer<>(Object.class);
        deserializer.addTrustedPackages("org.opencrawling.core.messaging");
        org.apache.kafka.common.header.internals.RecordHeaders sent = new org.apache.kafka.common.header.internals.RecordHeaders();

        IngestionMessage withChunking = message(new ChunkingSpec(300, null, 20, false));
        byte[] bytes = serializer.serialize("t", sent, withChunking);
        // The deserializer consumes the type headers, so each read gets its own copy.
        java.util.function.Supplier<org.apache.kafka.common.header.Headers> headers =
                () -> new org.apache.kafka.common.header.internals.RecordHeaders(sent.toArray());
        assertThat(deserializer.deserialize("t", headers.get(), bytes)).isEqualTo(withChunking);

        // A message from a producer that predates the field: no chunking, so the default.
        String json = new String(bytes, StandardCharsets.UTF_8);
        String older = json.replaceAll(",?\"chunking\":\\{[^}]*\\}", "");
        assertThat(older).doesNotContain("chunking");
        IngestionMessage read = (IngestionMessage) deserializer.deserialize("t", headers.get(), older.getBytes(StandardCharsets.UTF_8));
        assertThat(read.chunking()).isNull();
        assertThat(read.documentId()).isEqualTo("doc");

        // A consumer that predates the field ignores it, as it ignores any property it does not know.
        String newer = json.replace("\"chunking\":", "\"someLaterField\":1,\"chunking\":");
        assertThat(deserializer.deserialize("t", headers.get(), newer.getBytes(StandardCharsets.UTF_8))).isEqualTo(withChunking);
    }

    private static IngestionMessage message(ChunkingSpec chunking) {
        return new IngestionMessage("doc", "file:///claims/doc", Map.of(), "public", null, "2026-01-01T00:00:00Z",
                null, null, Map.of(), null, null, chunking);
    }

    private List<DocumentChunkMessage> chunks(ChunkingSpec chunking) {
        clearInvocations(kafkaTemplate);
        consumer.consume(message(chunking));
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
        return sent.getAllValues().stream().map(DocumentChunkMessage.class::cast).toList();
    }
}
