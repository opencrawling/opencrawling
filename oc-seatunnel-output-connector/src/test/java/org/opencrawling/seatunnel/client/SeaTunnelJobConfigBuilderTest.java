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
package org.opencrawling.seatunnel.client;

import org.junit.jupiter.api.Test;
import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SeaTunnelJobConfigBuilderTest {

    @Test
    void buildHoconConfig_defaultConsole() {
        SeaTunnelOutputProperties props = new SeaTunnelOutputProperties(
                "http://localhost:8080", "test_job", "STREAMING", 5000, 4,
                "localhost:9092", "opencrawling-embedded", "seatunnel-consumer",
                "console", 1024, true, 30
        );

        SeaTunnelJobConfigBuilder builder = new SeaTunnelJobConfigBuilder(props);
        String config = builder.buildHoconConfig();

        assertThat(config).contains("job.mode = \"STREAMING\"");
        assertThat(config).contains("execution.parallelism = 4");
        assertThat(config).contains("checkpoint.interval = 5000");
        assertThat(config).contains("Kafka {");
        assertThat(config).contains("bootstrap.servers = \"localhost:9092\"");
        assertThat(config).contains("topic = \"opencrawling-embedded\"");
        assertThat(config).contains("vector = \"array<float>\"");
        assertThat(config).contains("security_allowed_read = \"array<string>\"");
        assertThat(config).contains("Console {");
    }

    @Test
    void buildHoconConfig_multipleTargetSinks() {
        SeaTunnelOutputProperties props = new SeaTunnelOutputProperties(
                "http://localhost:8080", "fanout_job", "BATCH", 10000, 8,
                "kafka:9092", "my-topic", "my-group",
                "clickhouse,milvus,qdrant,iceberg", 768, true, 30
        );

        SeaTunnelJobConfigBuilder builder = new SeaTunnelJobConfigBuilder(props);
        String config = builder.buildHoconConfig();

        assertThat(config).contains("job.mode = \"BATCH\"");
        assertThat(config).contains("execution.parallelism = 8");
        assertThat(config).contains("Clickhouse {");
        assertThat(config).contains("Milvus {");
        assertThat(config).contains("Qdrant {");
        assertThat(config).contains("Iceberg {");
    }
}
