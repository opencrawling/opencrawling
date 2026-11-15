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
package org.opencrawling.runtime.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaConfigTest {

    private KafkaOperations<Object, Object> objectTemplate;
    private KafkaOperations<Object, Object> bytesTemplate;
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectTemplate = mock(KafkaOperations.class);
        bytesTemplate = mock(KafkaOperations.class);
        for (KafkaOperations<Object, Object> template : java.util.List.of(objectTemplate, bytesTemplate)) {
            when(template.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        }
    }

    @Test
    void deadLettersGoToTheTopicsDltWithNoPartitionSoKafkaPicksOne() {
        TopicPartition destination = KafkaConfig.deadLetterPartition(
                new ConsumerRecord<>("opencrawling-embedded", 2, 42L, "doc-1", "value"), new RuntimeException());

        assertThat(destination.topic()).isEqualTo("opencrawling-embedded.DLT");
        assertThat(destination.partition()).isNegative();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailingRecordIsRetriedBeforeItIsDeadLettered() {
        DefaultErrorHandler handler = KafkaConfig.errorHandler(objectTemplate, bytesTemplate, new FixedBackOff(0, 2));
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("opencrawling-embedded", 2, 42L, "doc-1", "chunk");
        Exception failure = new ListenerExecutionFailedException("write failed", new RuntimeException("store down"));

        assertThat(handler.handleOne(failure, record, consumer, container)).as("first retry").isFalse();
        assertThat(handler.handleOne(failure, record, consumer, container)).as("second retry").isFalse();
        verify(objectTemplate, never()).send(any(ProducerRecord.class));

        assertThat(handler.handleOne(failure, record, consumer, container)).as("dead-lettered").isTrue();

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(objectTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("opencrawling-embedded.DLT");
        assertThat(sent.getValue().partition()).isNull();
        assertThat(sent.getValue().key()).isEqualTo("doc-1");
        assertThat(sent.getValue().value()).isEqualTo("chunk");
        verify(bytesTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecordThatCannotBeDeserializedIsDeadLetteredAtOnceWithItsOriginalBytes() {
        DefaultErrorHandler handler = KafkaConfig.errorHandler(objectTemplate, bytesTemplate, new FixedBackOff(0, 5));
        byte[] raw = "{not json".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("opencrawling-chunks", 0, 7L, "doc-1", raw);
        Exception failure = new ListenerExecutionFailedException("cannot deserialize",
                new DeserializationException("bad payload", raw, false, new RuntimeException("parse error")));

        assertThat(handler.handleOne(failure, record, consumer, container)).as("no retries").isTrue();

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(bytesTemplate).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("opencrawling-chunks.DLT");
        assertThat((byte[]) sent.getValue().value()).isEqualTo(raw);
        verify(objectTemplate, never()).send(any(ProducerRecord.class));
    }
}
