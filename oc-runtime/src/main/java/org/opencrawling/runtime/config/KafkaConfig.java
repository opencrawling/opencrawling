package org.opencrawling.runtime.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opencrawling.runtime.messaging.IngestionMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

@Configuration
@EnableKafka
public class KafkaConfig {

    public static final String TOPIC_NAME = "opencrawling-documents";

    @Value("${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:opencrawling-vector-group}")
    private String groupId;

    @Bean
    public NewTopic opencrawlingDocumentsTopic() {
        return TopicBuilder.name(TOPIC_NAME)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public ProducerFactory<String, IngestionMessage> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, IngestionMessage> kafkaTemplate(ProducerFactory<String, IngestionMessage> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, IngestionMessage> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);

        JacksonJsonDeserializer<IngestionMessage> valueDeserializer = new JacksonJsonDeserializer<IngestionMessage>();
        valueDeserializer.addTrustedPackages("org.opencrawling.runtime.messaging");

        return new DefaultKafkaConsumerFactory<>(configProps, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, IngestionMessage> kafkaListenerContainerFactory(
            ConsumerFactory<String, IngestionMessage> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, IngestionMessage> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
