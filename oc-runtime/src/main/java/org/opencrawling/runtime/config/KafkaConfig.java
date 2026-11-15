/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.BackOff;

@Configuration
@EnableKafka
public class KafkaConfig {

    public static final String TOPIC_NAME = "opencrawling-documents";
    public static final String CHUNKS_TOPIC_NAME = "opencrawling-chunks";
    public static final String EMBEDDED_TOPIC_NAME = "opencrawling-embedded";

    /** Suffix of the dead-letter topic a record goes to once its retries are exhausted (Spring Kafka's default). */
    public static final String DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:opencrawling-vector-group}")
    private String groupId;

    /**
     * Retries of a record whose listener fails, with exponential back-off, before it goes to its dead-letter topic.
     * The defaults retry for about a minute (1, 2, 4, 8, 16 and 30 seconds), well within the consumer's
     * {@code max.poll.interval.ms} (5 minutes), since every retry polls the record again.
     */
    @Value("${opencrawling.kafka.retry.max-retries:6}")
    private int retryMaxRetries = 6;

    @Value("${opencrawling.kafka.retry.initial-interval-ms:1000}")
    private long retryInitialIntervalMs = 1000;

    @Value("${opencrawling.kafka.retry.multiplier:2.0}")
    private double retryMultiplier = 2.0;

    @Value("${opencrawling.kafka.retry.max-interval-ms:30000}")
    private long retryMaxIntervalMs = 30000;

    /**
     * Partitions of the documents topic: the upper bound for parallel consumers per consumer group
     * (e.g. the Ozone migration writer). KafkaAdmin can increase, but never decrease, an existing topic.
     */
    @Value("${spring.opencrawling.kafka.topic.partitions:3}")
    private int documentsTopicPartitions = 3;

    /**
     * Declares the topics below on the broker. Spring Boot 4 only auto-configures {@link KafkaAdmin}
     * with the {@code spring-boot-kafka} module; without this bean the {@link NewTopic} declarations
     * are ignored and the broker auto-creates topics with a single partition.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        // Don't fail startup if the broker isn't reachable yet (the broker then auto-creates topics with its defaults).
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    @Bean
    public NewTopic opencrawlingDocumentsTopic() {
        return TopicBuilder.name(TOPIC_NAME)
                .partitions(documentsTopicPartitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic opencrawlingChunksTopic() {
        return TopicBuilder.name(CHUNKS_TOPIC_NAME)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic opencrawlingEmbeddedTopic() {
        return TopicBuilder.name(EMBEDDED_TOPIC_NAME)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * The dead-letter topics of the topics above. Dead letters are published with no partition, so Kafka picks one
     * from the key and a dead-letter topic never needs as many partitions as its source.
     */
    @Bean
    public NewTopic opencrawlingDocumentsDeadLetterTopic() {
        return TopicBuilder.name(TOPIC_NAME + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic opencrawlingChunksDeadLetterTopic() {
        return TopicBuilder.name(CHUNKS_TOPIC_NAME + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic opencrawlingEmbeddedDeadLetterTopic() {
        return TopicBuilder.name(EMBEDDED_TOPIC_NAME + DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Publishes the dead letters of records that could not be deserialized: their original bytes, unchanged. Typed
     * {@code <String, byte[]>}, so it is never injected where a {@code <String, Object>} template is expected.
     */
    @Bean
    public ProducerFactory<String, byte[]> deadLetterBytesProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, byte[]> deadLetterBytesTemplate(ProducerFactory<String, byte[]> deadLetterBytesProducerFactory) {
        return new KafkaTemplate<>(deadLetterBytesProducerFactory);
    }

    /**
     * Retries a record whose listener throws, with exponential back-off, then publishes it to {@code <topic>.DLT}
     * with Spring Kafka's diagnostic headers (original topic, partition, offset and exception). A record that can
     * never succeed (it cannot be deserialized or converted) goes there at once. A record is never dropped: if the
     * dead letter cannot be published either, the record is retried again.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
                                                 KafkaTemplate<String, byte[]> deadLetterBytesTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retryMaxRetries);
        backOff.setInitialInterval(retryInitialIntervalMs);
        backOff.setMultiplier(retryMultiplier);
        backOff.setMaxInterval(retryMaxIntervalMs);
        return errorHandler(kafkaTemplate, deadLetterBytesTemplate, backOff);
    }

    /** The error handler, with the templates and back-off given; separate so tests can drive it directly. */
    static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> objectTemplate, KafkaOperations<?, ?> bytesTemplate,
                                            BackOff backOff) {
        // Ordered: a record that failed deserialization carries its raw bytes and must use the bytes template.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, objectTemplate);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates, KafkaConfig::deadLetterPartition);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /** {@code <topic>.DLT}, with no partition, so Kafka picks one from the key. */
    static TopicPartition deadLetterPartition(ConsumerRecord<?, ?> record, Exception exception) {
        return new TopicPartition(record.topic() + DLT_SUFFIX, -1);
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);

        JacksonJsonDeserializer<Object> valueDeserializer = new JacksonJsonDeserializer<>(Object.class);
        valueDeserializer.addTrustedPackages("org.opencrawling.core.messaging");

        // A record that cannot be deserialized reaches the error handler, which dead-letters it, instead of failing
        // every poll of its partition.
        return new DefaultKafkaConsumerFactory<>(configProps, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
