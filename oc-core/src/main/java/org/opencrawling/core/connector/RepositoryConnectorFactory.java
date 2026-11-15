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
package org.opencrawling.core.connector;

import java.util.Map;

/**
 * Creates a configured {@link RepositoryConnector} for a job.
 * Implementations are discovered via {@link java.util.ServiceLoader} and matched by {@link #className()}
 * against the {@code className} of the connector configuration.
 */
public interface RepositoryConnectorFactory {

    /**
     * @return fully qualified class name of the connector this factory creates
     */
    String className();

    /**
     * Creates a connector instance. Must not perform network I/O, so it can also be used to validate a job.
     *
     * @param configuration connector configuration from connectors.json
     * @param job job-level context and settings
     * @throws IllegalArgumentException if the configuration is invalid
     */
    RepositoryConnector create(Map<String, String> configuration, JobContext job);
}
