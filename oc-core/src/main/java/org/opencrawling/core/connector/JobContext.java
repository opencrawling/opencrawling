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
import java.util.function.Function;

/**
 * Job-level context passed to a {@link RepositoryConnectorFactory}.
 *
 * @param jobId job identifier
 * @param path the job's scan path (for the web connector: one or more seed URLs)
 * @param jobSettings connector-specific job settings (for example the web crawler's crawlSpec), may be empty
 * @param propertyResolver resolves runtime-wide defaults, typically Spring's {@code Environment::getProperty}
 */
public record JobContext(
    String jobId,
    String path,
    Map<String, Object> jobSettings,
    Function<String, String> propertyResolver
) {
    public JobContext {
        jobSettings = jobSettings != null ? jobSettings : Map.of();
        propertyResolver = propertyResolver != null ? propertyResolver : key -> null;
    }
}
