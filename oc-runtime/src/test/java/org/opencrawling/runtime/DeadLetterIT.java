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
package org.opencrawling.runtime;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A record the pipeline cannot process ends in its dead-letter topic instead of being lost: a malformed payload goes
 * there at once with its original bytes, and a chunk the store rejects goes there once its retries are exhausted.
 *
 * <p>Requires PostgreSQL and Kafka from {@code docker-compose.yml}.
 */
@SpringBootTest(properties = {
    "spring.kafka.consumer.group-id=test-group-${random.uuid}",
    // Free ports: every IT's cached context keeps its gRPC push listener bound, so fixed ports collide.
    "opencrawling.connector.grpc-push.port=0",
    "spring.grpc.server.port=0",
    // Short back-off, so the retries before a dead letter take well under a second.
    "opencrawling.kafka.retry.max-retries=2",
    "opencrawling.kafka.retry.initial-interval-ms=50",
    "opencrawling.kafka.retry.max-interval-ms=100"
})
@ActiveProfiles("test")
class DeadLetterIT {

    private static final String DLT = KafkaConfig.EMBEDDED_TOPIC_NAME + KafkaConfig.DLT_SUFFIX;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaTemplate<String, byte[]> deadLetterBytesTemplate;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate jdbc;

    @Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
    private String bootstrapServers;

    @Test
    void aMalformedPayloadIsDeadLetteredWithItsOriginalBytes() throws Exception {
        String key = "dlt-it-malformed-" + UUID.randomUUID();
        byte[] payload = ("{\"documentId\": \"" + key + "\", not json").getBytes(StandardCharsets.UTF_8);

        deadLetterBytesTemplate.send(KafkaConfig.EMBEDDED_TOPIC_NAME, key, payload).get();

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(key);
        assertThat(deadLetter.value()).isEqualTo(payload);
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(KafkaConfig.EMBEDDED_TOPIC_NAME);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotBlank();
    }

    @Test
    void aChunkTheStoreRejectsIsDeadLetteredOnceItsRetriesAreExhausted() throws Exception {
        String documentId = "dlt-it-rejected-" + UUID.randomUUID();
        String chunkId = UUID.randomUUID().toString();
        // A 5-dimensional vector fits none of the pgvector tables, so every write attempt fails.
        DocumentEmbeddedMessage chunk = new DocumentEmbeddedMessage(documentId, chunkId, "rejected text",
                Map.of("documentId", documentId), new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f});

        kafkaTemplate.send(KafkaConfig.EMBEDDED_TOPIC_NAME, chunk.partitionKey(), chunk).get();

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(documentId);
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).contains(chunkId);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains(chunkId);
        Integer stored = jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE id::text = ?", Integer.class, chunkId);
        assertThat(stored).isZero();
    }

    /** Reads the dead-letter topic from the start until the record with {@code key} arrives. */
    private ConsumerRecord<String, byte[]> awaitDeadLetter(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            reader.subscribe(List.of(DLT));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : reader.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("No dead letter with key " + key + " on " + DLT + " within " + TIMEOUT);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
