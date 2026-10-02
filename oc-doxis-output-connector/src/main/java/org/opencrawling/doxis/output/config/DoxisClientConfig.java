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
package org.opencrawling.doxis.output.config;

import org.opencrawling.doxis.output.DoxisAclMapper;
import org.opencrawling.doxis.output.DoxisDocumentMapper;
import org.opencrawling.doxis.output.DoxisOutputConnector;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.content.ContentLinkResolver;
import org.opencrawling.doxis.output.content.ContentLinkWriter;
import org.opencrawling.doxis.output.content.ContentPlanner;
import org.opencrawling.doxis.output.content.LocatorResolver;
import org.opencrawling.doxis.output.content.PrefixLocatorResolver;
import org.opencrawling.doxis.output.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
@EnableConfigurationProperties(DoxisOutputProperties.class)
public class DoxisClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DoxisClientConfig.class);

    @Bean(destroyMethod = "close")
    public DoxisClient doxisClient(DoxisOutputProperties properties) {
        log.info("Initializing DoxisClient for {} (customer {}, user {}).", properties.baseUrl(), properties.customerName(),
                properties.username());
        return DoxisOutputConnector.newClient(properties);
    }

    @Bean
    public DoxisSchema doxisSchema(DoxisClient doxisClient) {
        return new DoxisSchema(doxisClient);
    }

    /**
     * Replace this bean to plug in a custom way of deriving {@code predefinedLocator} values (e.g. from a staging service that
     * places binaries into the Doxis data store).
     */
    @Bean
    @ConditionalOnMissingBean(LocatorResolver.class)
    public LocatorResolver doxisLocatorResolver(DoxisOutputProperties properties) {
        return new PrefixLocatorResolver(properties.locator());
    }

    /**
     * Optional zero-copy content-link writer, loaded from {@code content-link.client-lib-dir} (SER Doxis Java client + the
     * {@code oc-doxis-blueline-content-link} module) in an isolated class loader.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "spring.opencrawling.output.doxis.content-link.client-lib-dir")
    public ContentLinkWriter doxisContentLinkWriter(DoxisOutputProperties properties) {
        return DoxisOutputConnector.newContentLinkWriter(properties);
    }

    @Bean
    public ContentPlanner doxisContentPlanner(DoxisOutputProperties properties, LocatorResolver locatorResolver,
                                              ObjectProvider<ContentLinkWriter> contentLinkWriter) {
        return new ContentPlanner(properties.content(), locatorResolver, new ContentLinkResolver(properties.contentLink()),
                contentLinkWriter.getIfAvailable() != null);
    }

    @Bean
    public DoxisDocumentMapper doxisDocumentMapper(DoxisOutputProperties properties, DoxisSchema schema) {
        return new DoxisDocumentMapper(properties, schema);
    }

    @Bean
    public DoxisAclMapper doxisAclMapper(DoxisSchema schema) {
        return new DoxisAclMapper(schema);
    }
}
