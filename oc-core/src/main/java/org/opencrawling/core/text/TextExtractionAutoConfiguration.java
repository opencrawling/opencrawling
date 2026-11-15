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
package org.opencrawling.core.text;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Apache Tika text extraction services.
 */
@AutoConfiguration
@EnableConfigurationProperties(TextExtractionProperties.class)
public class TextExtractionAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TextExtractionService.class)
    @ConditionalOnProperty(prefix = "opencrawling.tika", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TextExtractionService textExtractionService(TextExtractionProperties properties) {
        return new PipesForkTextExtractor(properties);
    }

    @Bean
    @ConditionalOnMissingBean(TextExtractionService.class)
    @ConditionalOnProperty(prefix = "opencrawling.tika", name = "enabled", havingValue = "false")
    public TextExtractionService noOpTextExtractionService() {
        return TextExtractionService.noop();
    }
}
