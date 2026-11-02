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
package org.opencrawling.authorization.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ApiToken;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.Credentials;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Wires the OpenFGA provider, the tuple writer and the sync consumer when
 * {@code opencrawling.security.authorization.mode} is {@code shadow}, {@code intersection} or {@code openfga}.
 * In {@code metadata} mode (the default) nothing here is created and OpenFGA is never contacted.
 */
@Configuration
@ConditionalOnExpression("{'shadow','intersection','openfga'}"
        + ".contains('${opencrawling.security.authorization.mode:metadata}'.trim().toLowerCase())")
@EnableConfigurationProperties(OpenFgaProperties.class)
public class OpenFgaAuthorizationConfiguration {

    @Bean
    public OpenFgaClient openFgaClient(OpenFgaProperties properties) throws Exception {
        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(properties.apiUrl())
                .readTimeout(properties.timeout())
                .connectTimeout(properties.timeout());
        if (!OpenFgaProperties.blank(properties.apiToken())) {
            configuration.credentials(new Credentials(new ApiToken(properties.apiToken())));
        }
        OpenFgaClient client = new OpenFgaClient(configuration);
        OpenFgaBootstrap.configure(client, properties);
        return client;
    }

    @Bean
    public OpenFgaAuthorizationProvider openFgaAuthorizationProvider(OpenFgaClient client, OpenFgaProperties properties) {
        return new OpenFgaAuthorizationProvider(client, properties);
    }

    @Bean
    public OpenFgaTupleWriter openFgaTupleWriter(OpenFgaClient client, OpenFgaProperties properties) {
        return new OpenFgaTupleWriter(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "opencrawling.security.authorization.openfga.sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public AuthorizationSyncConsumer authorizationSyncConsumer(OpenFgaTupleWriter writer) {
        return new AuthorizationSyncConsumer(writer);
    }

    /**
     * The sync listener's container: retries a failed sync with exponential backoff (0.5 s doubling to 10 s,
     * 6 attempts in all), then publishes the message to the dead-letter topic and moves on.
     */
    @Bean(name = AuthorizationSyncConsumer.CONTAINER_FACTORY)
    @ConditionalOnProperty(name = "opencrawling.security.authorization.openfga.sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public ConcurrentKafkaListenerContainerFactory<String, Object> authzSyncContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, KafkaTemplate<String, Object> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(AuthorizationSyncConsumer.DEAD_LETTER_TOPIC, -1));
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "opencrawling.security.authorization.openfga.sync.enabled",
            havingValue = "true", matchIfMissing = true)
    public NewTopic authzSyncDeadLetterTopic() {
        return TopicBuilder.name(AuthorizationSyncConsumer.DEAD_LETTER_TOPIC).partitions(1).replicas(1).build();
    }
}
