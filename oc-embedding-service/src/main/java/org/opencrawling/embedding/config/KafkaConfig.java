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
package org.opencrawling.embedding.config;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    public static final String CHUNKS_TOPIC_NAME = "opencrawling-chunks";
    public static final String EMBEDDED_TOPIC_NAME = "opencrawling-embedded";

    /** Suffix of the dead-letter topic a record goes to once its retries are exhausted (Spring Kafka's default). */
    public static final String DLT_SUFFIX = ".DLT";

    @Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:opencrawling-embedding-group}")
    private String groupId;

    /**
     * Retries of a chunk whose embedding fails (for example while the model server is down), with exponential
     * back-off, before it goes to its dead-letter topic. Same settings and defaults as oc-runtime.
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
     * Creates the topics declared below, or grows them to the declared partition count, before the listener
     * starts. Startup fails while the broker is unreachable.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        admin.setFatalIfBrokerNotAvailable(true);
        return admin;
    }

    /** The topic this service consumes, with the partition count oc-runtime declares for it. */
    @Bean
    public NewTopic opencrawlingChunksTopic() {
        return TopicBuilder.name(CHUNKS_TOPIC_NAME)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** The topic this service produces, with the partition count oc-runtime declares for it. */
    @Bean
    public NewTopic opencrawlingEmbeddedTopic() {
        return TopicBuilder.name(EMBEDDED_TOPIC_NAME)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** The dead-letter topic of the topic this service consumes, as oc-runtime declares it. */
    @Bean
    public NewTopic opencrawlingChunksDeadLetterTopic() {
        return TopicBuilder.name(CHUNKS_TOPIC_NAME + DLT_SUFFIX).partitions(1).replicas(1).build();
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

    /** Publishes the dead letters of records that could not be deserialized: their original bytes, unchanged. */
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
     * with Spring Kafka's diagnostic headers. A record that can never succeed (it cannot be deserialized or
     * converted) goes there at once. If the dead letter cannot be published either, the record is retried again.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
                                                 KafkaTemplate<String, byte[]> deadLetterBytesTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retryMaxRetries);
        backOff.setInitialInterval(retryInitialIntervalMs);
        backOff.setMultiplier(retryMultiplier);
        backOff.setMaxInterval(retryMaxIntervalMs);
        // Ordered: a record that failed deserialization carries its raw bytes and must use the bytes template.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, deadLetterBytesTemplate);
        templates.put(Object.class, kafkaTemplate);
        // No partition: Kafka picks one from the key, so the dead-letter topic needs no more than one partition.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        return new DefaultErrorHandler(recoverer, backOff);
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
