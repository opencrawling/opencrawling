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

import org.opencrawling.seatunnel.config.SeaTunnelOutputProperties;

import java.util.Arrays;
import java.util.List;

public class SeaTunnelJobConfigBuilder {

    private final SeaTunnelOutputProperties properties;

    public SeaTunnelJobConfigBuilder(SeaTunnelOutputProperties properties) {
        this.properties = properties;
    }

    public String buildHoconConfig() {
        StringBuilder sb = new StringBuilder();

        // 1. Env block
        sb.append("env {\n");
        sb.append("  execution.parallelism = ").append(properties.parallelism()).append("\n");
        sb.append("  job.mode = \"").append(properties.jobMode()).append("\"\n");
        sb.append("  checkpoint.interval = ").append(properties.checkpointIntervalMs()).append("\n");
        sb.append("}\n\n");

        // 2. Source block: Kafka Source reading OIS embedded chunks
        sb.append("source {\n");
        sb.append("  Kafka {\n");
        sb.append("    bootstrap.servers = \"").append(properties.kafkaBootstrapServers()).append("\"\n");
        sb.append("    topic = \"").append(properties.kafkaTopic()).append("\"\n");
        sb.append("    consumer.group = \"").append(properties.kafkaGroupId()).append("\"\n");
        sb.append("    result_table_name = \"ois_embedded_chunks\"\n");
        sb.append("    format = \"json\"\n");
        sb.append("    schema = {\n");
        sb.append("      fields {\n");
        sb.append("        id = \"string\"\n");
        sb.append("        doc_id = \"string\"\n");
        sb.append("        action = \"string\"\n");
        sb.append("        uri = \"string\"\n");
        sb.append("        text = \"string\"\n");
        sb.append("        vector = \"array<float>\"\n");
        sb.append("        acl = \"array<string>\"\n");
        sb.append("        security_allowed_read = \"array<string>\"\n");
        sb.append("        security_denied_read = \"array<string>\"\n");
        sb.append("        security_inheritance = \"boolean\"\n");
        sb.append("        last_modified = \"string\"\n");
        sb.append("        metadata = \"string\"\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        // 3. Sinks block: Fan-out to specified target sinks
        sb.append("sink {\n");
        List<String> sinks = Arrays.stream(properties.targetSinks().split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toList();

        if (sinks.isEmpty()) {
            sinks = List.of("console");
        }

        for (String sink : sinks) {
            switch (sink) {
                case "clickhouse" -> {
                    sb.append("  Clickhouse {\n");
                    sb.append("    source_table_name = \"ois_embedded_chunks\"\n");
                    sb.append("    host = \"localhost:8123\"\n");
                    sb.append("    database = \"default\"\n");
                    sb.append("    table = \"document_chunks\"\n");
                    sb.append("  }\n");
                }
                case "milvus" -> {
                    sb.append("  Milvus {\n");
                    sb.append("    source_table_name = \"ois_embedded_chunks\"\n");
                    sb.append("    url = \"http://localhost:19530\"\n");
                    sb.append("    collection = \"enterprise_kb\"\n");
                    sb.append("  }\n");
                }
                case "qdrant" -> {
                    sb.append("  Qdrant {\n");
                    sb.append("    source_table_name = \"ois_embedded_chunks\"\n");
                    sb.append("    host = \"localhost\"\n");
                    sb.append("    port = 6334\n");
                    sb.append("    collection_name = \"enterprise_kb\"\n");
                    sb.append("  }\n");
                }
                case "iceberg" -> {
                    sb.append("  Iceberg {\n");
                    sb.append("    source_table_name = \"ois_embedded_chunks\"\n");
                    sb.append("    catalog_type = \"hadoop\"\n");
                    sb.append("    warehouse = \"/tmp/iceberg-warehouse\"\n");
                    sb.append("    table = \"default.ois_documents\"\n");
                    sb.append("  }\n");
                }
                default -> {
                    sb.append("  Console {\n");
                    sb.append("    source_table_name = \"ois_embedded_chunks\"\n");
                    sb.append("  }\n");
                }
            }
        }
        sb.append("}\n");

        return sb.toString();
    }
}
