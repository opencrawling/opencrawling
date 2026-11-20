/*
 * Copyright © 2026 Rafa Haro (rafaharo@users.noreply.github.com)
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
package org.opencrawling.runtime.claimcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * The claim-check URIs that the documents topic's dead letters ({@code opencrawling-documents.DLT}) still reference.
 * A dead-lettered document message carries only the URI of its content, so a replay reads that claim again: the
 * claim garbage collector keeps these claims, whatever their age, for as long as their dead letters exist.
 *
 * <p>The topic is read from its start to its current end on every call, with no consumer group (nothing is
 * committed). Dead letters whose payload is not a JSON message with a {@code uri} (for example a malformed payload
 * kept as raw bytes) reference no claim.
 */
@Component
public class DeadLetteredClaims {

    /** The dead-letter topic of the documents topic, whose messages reference claims. */
    static final String TOPIC = KafkaConfig.TOPIC_NAME + KafkaConfig.DLT_SUFFIX;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String bootstrapServers;
    private final Duration timeout;

    public DeadLetteredClaims(
            @Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}") String bootstrapServers,
            @Value("${spring.opencrawling.claim-check.lifecycle.dead-letter-read-timeout-ms:30000}") long timeoutMs) {
        this.bootstrapServers = bootstrapServers;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * Reads the dead-letter topic to its current end.
     *
     * @return the normalized URIs of the claims the dead letters reference (see {@link #retaining})
     * @throws Exception if the topic cannot be read to its end within the timeout; the caller must then delete
     *                   nothing, since any claim could still be referenced
     */
    public Set<String> referencedClaims() throws Exception {
        // One budget for the whole read: every blocking call below gets only what is left of it, close() included.
        long deadline = System.nanoTime() + timeout.toNanos();
        KafkaConsumer<byte[], byte[]> consumer =
                new KafkaConsumer<>(consumerConfig(), new ByteArrayDeserializer(), new ByteArrayDeserializer());
        try {
            List<PartitionInfo> infos = consumer.partitionsFor(TOPIC, remaining(deadline));
            if (infos == null || infos.isEmpty()) {
                return Set.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, remaining(deadline));

            Set<String> claims = new HashSet<>();
            while (!reachedEnd(consumer, end, deadline)) {
                Duration poll = remaining(deadline);
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(poll.compareTo(POLL) < 0 ? poll : POLL)) {
                    String uri = claimUri(record.value());
                    if (uri != null) {
                        claims.add(normalize(uri));
                    }
                }
            }
            return claims;
        } catch (org.apache.kafka.common.errors.TimeoutException e) {
            throw new TimeoutException("Reading " + TOPIC + " did not complete within " + timeout + ": " + e.getMessage());
        } finally {
            consumer.close(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        }
    }

    private static final Duration POLL = Duration.ofMillis(500);

    /**
     * The reader's settings. {@code auto.offset.reset=none}: the read starts with an explicit seek to the beginning,
     * and an offset that becomes invalid during it (retention deleting the segment being read) must fail the read,
     * so the sweep is skipped, rather than jump to the end and return an incomplete list of claims.
     */
    Map<String, Object> consumerConfig() {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.CLIENT_ID_CONFIG, "opencrawling-claim-gc",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none",
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    }

    /** What is left of the read's budget; a {@link TimeoutException} once it is spent. */
    private Duration remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException("Reading " + TOPIC + " did not complete within " + timeout);
        }
        return Duration.ofNanos(left);
    }

    /** Keeps the claims whose URI is one of {@code referenced} (as {@link #referencedClaims} returns them). */
    public static Predicate<URI> retaining(Set<String> referenced) {
        return uri -> uri != null && referenced.contains(normalize(uri.toString()));
    }

    private boolean reachedEnd(KafkaConsumer<?, ?> consumer, Map<TopicPartition, Long> end, long deadline)
            throws TimeoutException {
        for (Map.Entry<TopicPartition, Long> partition : end.entrySet()) {
            if (consumer.position(partition.getKey(), remaining(deadline)) < partition.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** The {@code uri} of a dead-lettered document message, or {@code null} if it has none. */
    static String claimUri(byte[] value) {
        if (value == null || value.length == 0) {
            return null;
        }
        try {
            JsonNode uri = JSON.readTree(value).path("uri");
            return uri.isTextual() && !uri.asText().isBlank() ? uri.asText() : null;
        } catch (Exception e) {
            return null; // not a JSON message, such as a malformed payload kept as raw bytes
        }
    }

    /**
     * One form for a claim URI, whoever wrote it: a {@code file} URI becomes its absolute path, because
     * {@code File.toURI()} ({@code file:/x}) and {@code Path.toUri()} ({@code file:///x}) spell the same file
     * differently.
     */
    static String normalize(String uri) {
        try {
            URI parsed = URI.create(uri);
            if ("file".equalsIgnoreCase(parsed.getScheme())) {
                return Paths.get(parsed).toAbsolutePath().normalize().toString();
            }
            return parsed.normalize().toString();
        } catch (Exception e) {
            return uri;
        }
    }
}
