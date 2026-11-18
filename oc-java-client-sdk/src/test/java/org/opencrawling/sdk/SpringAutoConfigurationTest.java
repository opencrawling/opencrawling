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
package org.opencrawling.sdk;

import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.spring.OpenCrawlingAutoConfiguration;
import org.opencrawling.sdk.spring.OpenCrawlingClientProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenCrawlingAutoConfiguration.class));

    @Test
    void testAutoConfigurationCreatesBean() {
        contextRunner.withPropertyValues(
                "opencrawling.client.base-url=http://custom-host:8080",
                "opencrawling.client.api-key=test-key"
        ).run(context -> {
            assertThat(context).hasSingleBean(OpenCrawlingClient.class);
            assertThat(context).hasSingleBean(OpenCrawlingClientProperties.class);

            OpenCrawlingClientProperties props = context.getBean(OpenCrawlingClientProperties.class);
            assertThat(props.getBaseUrl()).isEqualTo("http://custom-host:8080");
            assertThat(props.getApiKey()).isEqualTo("test-key");
        });
    }
}
