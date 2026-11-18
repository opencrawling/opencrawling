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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A connector definition, to create, replace or check a connector.
 *
 * @param name          unique; 1 to 100 letters, digits, spaces, '.', '_' or '-'
 * @param type          repository, output, transformation or authority
 * @param configuration implementation-specific settings; a {@link Connector#MASK} value keeps the stored secret
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorInput(String name, String description, String type, String className,
                             Integer maxConnections, Map<String, String> configuration) {

    public static ConnectorInput of(String name, String type, String className, Map<String, String> configuration) {
        return new ConnectorInput(name, null, type, className, null, configuration);
    }

    public ConnectorInput withDescription(String value) {
        return new ConnectorInput(name, value, type, className, maxConnections, configuration);
    }

    public ConnectorInput withMaxConnections(Integer value) {
        return new ConnectorInput(name, description, type, className, value, configuration);
    }

    public ConnectorInput withConfiguration(Map<String, String> value) {
        return new ConnectorInput(name, description, type, className, maxConnections, value);
    }
}
