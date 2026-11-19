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
package org.opencrawling.vespa.config;

import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.FeedClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(VespaOutputProperties.class)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "vespa")
public class VespaClientConfig {

    private static final Logger log = LoggerFactory.getLogger(VespaClientConfig.class);

    @Bean
    public FeedClient vespaFeedClient(VespaOutputProperties properties) {
        log.info("Initializing Vespa FeedClient. Endpoint: {}, Namespace: {}, Document Type: {}",
                properties.endpoint(), properties.namespace(), properties.documentType());

        FeedClientBuilder builder = FeedClientBuilder.create(URI.create(properties.endpoint()));
        if (properties.tlsEnabled() && properties.tlsCertificate() != null && !properties.tlsCertificate().isBlank()
                && properties.tlsPrivateKey() != null && !properties.tlsPrivateKey().isBlank()) {
            builder.setCertificate(Path.of(properties.tlsCertificate()), Path.of(properties.tlsPrivateKey()));
            if (properties.tlsCaCertificates() != null && !properties.tlsCaCertificates().isBlank()) {
                builder.setCaCertificatesFile(Path.of(properties.tlsCaCertificates()));
            }
        }
        return builder.build();
    }
}
