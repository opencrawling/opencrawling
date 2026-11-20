/*
 * Copyright © 2026 Rafa Haro (piergiorgio@apache.org)
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

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.opencrawling.runtime.config.KafkaConfig;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The consumer group of one IT context ({@code spring.kafka.consumer.group-id}), created with its offsets already
 * committed at the current end of the pipeline topics.
 *
 * <p>A new group would otherwise start at the beginning ({@code KafkaConfig} forces {@code auto.offset.reset=earliest}),
 * and the listeners on that group, such as {@link TestEmbeddingConsumer}, would replay every message earlier runs
 * left on a long-lived local broker before reaching the test's own. The offsets are committed before the context
 * starts, so no message published by the test can be skipped, as it could with {@code auto.offset.reset=latest}.
 *
 * <p>Use it from a {@code @DynamicPropertySource} method: {@code KafkaTestGroup.register(registry)}.
 */
final class KafkaTestGroup {

    /** The topics whose history a new group would replay. */
    static final List<String> TOPICS = List.of(
            KafkaConfig.TOPIC_NAME, KafkaConfig.CHUNKS_TOPIC_NAME, KafkaConfig.EMBEDDED_TOPIC_NAME);

    private static final long TIMEOUT_SECONDS = 30;

    private KafkaTestGroup() {
    }

    /** Registers a new group, positioned at the end of {@link #TOPICS}, as the context's consumer group. */
    static void register(DynamicPropertyRegistry registry) {
        String group = atEndOfTopics(bootstrapServers());
        registry.add("spring.kafka.consumer.group-id", () -> group);
    }

    /**
     * Creates a group whose committed offsets are the current end of each existing topic in {@link #TOPICS}. A topic
     * that does not exist yet has no history to skip.
     *
     * @return the group id
     */
    static String atEndOfTopics(String bootstrapServers) {
        String group = "test-group-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            Set<String> existing = admin.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> topics = TOPICS.stream().filter(existing::contains).toList();
            if (topics.isEmpty()) {
                return group;
            }
            Map<String, TopicDescription> descriptions =
                    admin.describeTopics(topics).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            descriptions.values().forEach(topic -> topic.partitions()
                    .forEach(partition -> latest.put(new TopicPartition(topic.name(), partition.partition()), OffsetSpec.latest())));
            Map<TopicPartition, OffsetAndMetadata> end = admin.listOffsets(latest).all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue().offset())));
            admin.alterConsumerGroupOffsets(group, end).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return group;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the IT consumer group on " + bootstrapServers
                    + " (is Kafka from docker-compose.yml running?)", e);
        }
    }

    /** The broker the context will use: {@code spring.kafka.bootstrap-servers}, as application.yml defaults it. */
    private static String bootstrapServers() {
        String fromProperty = System.getProperty("spring.kafka.bootstrap-servers");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnvironment = System.getenv("SPRING_KAFKA_BOOTSTRAP_SERVERS");
        return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment : "127.0.0.1:9092";
    }
}
