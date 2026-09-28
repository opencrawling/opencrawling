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
package org.opencrawling.seatunnel.config;

import org.opencrawling.seatunnel.SeaTunnelConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.opencrawling.output.seatunnel")
public record SeaTunnelOutputProperties(
        @DefaultValue(SeaTunnelConstants.DEFAULT_REST_URL) String restUrl,
        @DefaultValue(SeaTunnelConstants.DEFAULT_JOB_NAME) String jobName,
        @DefaultValue(SeaTunnelConstants.DEFAULT_JOB_MODE) String jobMode,
        @DefaultValue("5000") int checkpointIntervalMs,
        @DefaultValue("4") int parallelism,
        @DefaultValue(SeaTunnelConstants.DEFAULT_KAFKA_BOOTSTRAP_SERVERS) String kafkaBootstrapServers,
        @DefaultValue(SeaTunnelConstants.DEFAULT_KAFKA_TOPIC) String kafkaTopic,
        @DefaultValue(SeaTunnelConstants.DEFAULT_KAFKA_GROUP_ID) String kafkaGroupId,
        @DefaultValue(SeaTunnelConstants.DEFAULT_TARGET_SINKS) String targetSinks,
        @DefaultValue("1024") int dimensions,
        @DefaultValue("true") boolean autoSubmitJob,
        @DefaultValue("30") int timeoutSeconds
) {
    public SeaTunnelOutputProperties {
        if (restUrl == null || restUrl.isBlank()) {
            restUrl = SeaTunnelConstants.DEFAULT_REST_URL;
        }
        if (jobName == null || jobName.isBlank()) {
            jobName = SeaTunnelConstants.DEFAULT_JOB_NAME;
        }
        if (jobMode == null || jobMode.isBlank()) {
            jobMode = SeaTunnelConstants.DEFAULT_JOB_MODE;
        }
        if (checkpointIntervalMs <= 0) {
            checkpointIntervalMs = SeaTunnelConstants.DEFAULT_CHECKPOINT_INTERVAL_MS;
        }
        if (parallelism <= 0) {
            parallelism = SeaTunnelConstants.DEFAULT_PARALLELISM;
        }
        if (kafkaBootstrapServers == null || kafkaBootstrapServers.isBlank()) {
            kafkaBootstrapServers = SeaTunnelConstants.DEFAULT_KAFKA_BOOTSTRAP_SERVERS;
        }
        if (kafkaTopic == null || kafkaTopic.isBlank()) {
            kafkaTopic = SeaTunnelConstants.DEFAULT_KAFKA_TOPIC;
        }
        if (kafkaGroupId == null || kafkaGroupId.isBlank()) {
            kafkaGroupId = SeaTunnelConstants.DEFAULT_KAFKA_GROUP_ID;
        }
        if (targetSinks == null || targetSinks.isBlank()) {
            targetSinks = SeaTunnelConstants.DEFAULT_TARGET_SINKS;
        }
        if (dimensions <= 0) {
            dimensions = SeaTunnelConstants.DEFAULT_DIMENSIONS;
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = SeaTunnelConstants.DEFAULT_TIMEOUT_SECONDS;
        }
    }
}
