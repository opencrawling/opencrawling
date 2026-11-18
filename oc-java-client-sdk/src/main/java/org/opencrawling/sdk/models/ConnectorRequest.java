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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.HashMap;

/**
 * Request DTO for creating or registering an OpenCrawling Connector.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorRequest(
    String name,
    String description,
    String type,
    String className,
    int maxConnections,
    Map<String, String> configuration
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description;
        private String type = "repository";
        private String className;
        private int maxConnections = 10;
        private Map<String, String> configuration = new HashMap<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder configuration(Map<String, String> configuration) {
            this.configuration = configuration != null ? configuration : new HashMap<>();
            return this;
        }

        public Builder addConfiguration(String key, String value) {
            if (this.configuration == null) {
                this.configuration = new HashMap<>();
            }
            this.configuration.put(key, value);
            return this;
        }

        public ConnectorRequest build() {
            return new ConnectorRequest(name, description, type, className, maxConnections, configuration);
        }
    }
}
