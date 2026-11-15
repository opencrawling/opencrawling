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
package org.opencrawling.iceberg.output.messaging;

import org.junit.jupiter.api.Test;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies conditional registration of {@link IcebergStoreWriterConsumer}.
 */
class IcebergStoreWriterConsumerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(IcebergOutputConnector.class, () -> mock(IcebergOutputConnector.class))
            .withUserConfiguration(IcebergStoreWriterConsumer.class);

    @Test
    void registersByDefaultWhenIcebergOutputSelected() {
        contextRunner
                .withPropertyValues("spring.opencrawling.output.type=iceberg")
                .run(context -> assertThat(context).hasSingleBean(IcebergStoreWriterConsumer.class));
    }

    @Test
    void registersWhenWriterExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.opencrawling.output.type=iceberg",
                        "opencrawling.consumer.writer.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IcebergStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenWriterDisabled() {
        contextRunner
                .withPropertyValues(
                        "spring.opencrawling.output.type=iceberg",
                        "opencrawling.consumer.writer.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IcebergStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenOutputIsPgvector() {
        contextRunner
                .withPropertyValues("spring.opencrawling.output.type=pgvector")
                .run(context -> assertThat(context).doesNotHaveBean(IcebergStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenOutputTypeUnset() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(IcebergStoreWriterConsumer.class));
    }
}
