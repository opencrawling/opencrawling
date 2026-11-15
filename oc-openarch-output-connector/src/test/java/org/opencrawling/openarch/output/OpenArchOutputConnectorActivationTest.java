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
package org.opencrawling.openarch.output;

import org.junit.jupiter.api.Test;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchClientConfig;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import org.opencrawling.openarch.output.messaging.OpenArchStoreWriterConsumer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring wiring of the OpenArch output connector, i.e. that the
 * crawler / writer-consumer containers can start with spring.opencrawling.output.type=openarch.
 */
class OpenArchOutputConnectorActivationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenArchClientConfig.class, OpenArchOutputConnector.class, OpenArchStoreWriterConsumer.class);

    @Test
    void outputConnectorStartsWhenOpenArchOutputTypeSelected() {
        runner.withPropertyValues(
                        "spring.opencrawling.output.type=openarch",
                        "spring.opencrawling.output.openarch.endpoint-url=http://openarch:8080",
                        "spring.opencrawling.output.openarch.parquet-batch-threshold=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenArchOutputProperties.class);
                    assertThat(context).hasSingleBean(OpenArchClient.class);
                    assertThat(context).hasSingleBean(OpenArchOutputConnector.class);
                    // Writer consumer is only active when the writer role is enabled
                    assertThat(context).doesNotHaveBean(OpenArchStoreWriterConsumer.class);

                    OpenArchOutputProperties props = context.getBean(OpenArchOutputProperties.class);
                    assertThat(props.getEndpointUrl()).isEqualTo("http://openarch:8080");
                    assertThat(props.getParquetBatchThreshold()).isEqualTo(7);
                });
    }

    @Test
    void writerConsumerStartsWhenWriterRoleEnabled() {
        runner.withPropertyValues(
                        "spring.opencrawling.output.type=openarch",
                        "opencrawling.consumer.writer.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenArchStoreWriterConsumer.class);
                });
    }

    @Test
    void nothingRegisteredForOtherOutputTypes() {
        runner.withPropertyValues("spring.opencrawling.output.type=pgvector")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpenArchOutputProperties.class);
                    assertThat(context).doesNotHaveBean(OpenArchClient.class);
                    assertThat(context).doesNotHaveBean(OpenArchOutputConnector.class);
                });
    }
}
