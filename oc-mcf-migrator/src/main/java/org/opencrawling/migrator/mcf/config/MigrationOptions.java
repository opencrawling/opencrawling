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
package org.opencrawling.migrator.mcf.config;

import java.util.List;
import java.util.Map;

/**
 * Resolved CLI options for one {@code migrate} run, built once from parsed flags/environment and
 * threaded through the engine and every {@code ConnectorMapper}.
 */
public record MigrationOptions(
    String manifoldCfUrl,
    String manifoldCfUsername,
    String manifoldCfPassword,
    String openCrawlingUrl,
    String openCrawlingApiKey,
    boolean apply,
    String reportFile,
    int defaultEmbeddingDimensions,
    List<String> onlyConnections,
    List<String> onlyJobs,
    boolean failOnSkip,
    int timeoutSeconds,
    Map<String, String> connectorOverrides
) {
    public MigrationOptions {
        onlyConnections = onlyConnections == null ? List.of() : List.copyOf(onlyConnections);
        onlyJobs = onlyJobs == null ? List.of() : List.copyOf(onlyJobs);
        connectorOverrides = connectorOverrides == null ? Map.of() : Map.copyOf(connectorOverrides);
    }

    public boolean isNameSelected(List<String> filter, String name) {
        return filter.isEmpty() || (name != null && filter.stream().anyMatch(name::equalsIgnoreCase));
    }
}
