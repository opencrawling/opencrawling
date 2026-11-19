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
package org.opencrawling.qdrant.messaging;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.opencrawling.qdrant.QdrantPointMapper;
import org.opencrawling.qdrant.config.QdrantOutputProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the activation gate of {@link QdrantStoreWriterConsumer}. The Kafka writer that persists
 * embedded chunks must register only when the Qdrant output is selected, be enabled by default (so a
 * single-process deployment writes with no extra config), and be able to opt out via
 * {@code opencrawling.consumer.writer.enabled=false} — mirroring the pgvector/Milvus writer consumers.
 */
class QdrantStoreWriterConsumerActivationTest {

    private static final QdrantOutputProperties PROPERTIES = new QdrantOutputProperties(
            "localhost", 6334, "", "enterprise_kb", 1024,
            QdrantOutputProperties.Distance.COSINE, QdrantOutputProperties.Quantization.NONE, false, 500);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(QdrantClient.class, () -> mock(QdrantClient.class))
            .withBean(QdrantOutputProperties.class, () -> PROPERTIES)
            .withBean(QdrantPointMapper.class, QdrantPointMapper::new)
            .withUserConfiguration(QdrantStoreWriterConsumer.class);

    @Test
    void registersByDefaultWhenQdrantOutputSelected() {
        contextRunner
                .withPropertyValues("spring.opencrawling.output.type=qdrant")
                .run(context -> assertThat(context).hasSingleBean(QdrantStoreWriterConsumer.class));
    }

    @Test
    void registersWhenWriterExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "spring.opencrawling.output.type=qdrant",
                        "opencrawling.consumer.writer.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(QdrantStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenWriterDisabled() {
        contextRunner
                .withPropertyValues(
                        "spring.opencrawling.output.type=qdrant",
                        "opencrawling.consumer.writer.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QdrantStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenOutputIsPgvector() {
        contextRunner
                .withPropertyValues("spring.opencrawling.output.type=pgvector")
                .run(context -> assertThat(context).doesNotHaveBean(QdrantStoreWriterConsumer.class));
    }

    @Test
    void doesNotRegisterWhenOutputTypeUnset() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(QdrantStoreWriterConsumer.class));
    }
}
