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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Response DTO representing an OpenCrawling Connector configuration.
 *
 * @param name the connector instance name
 * @param description the connector description
 * @param type the connector type (repository, transformation, output)
 * @param className the fully qualified connector class name
 * @param maxConnections maximum concurrent connections allowed
 * @param configuration map of connector configuration key-value pairs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorResponse(
    String name,
    String description,
    String type,
    String className,
    int maxConnections,
    Map<String, String> configuration
) {}
