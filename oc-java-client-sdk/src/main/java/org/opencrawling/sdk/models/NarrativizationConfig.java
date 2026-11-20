/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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

/**
 * Configuration for Auto-Narrativization Copilot enrichment on crawler jobs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NarrativizationConfig(
    boolean enabled,
    String template,
    String connectorType
) {
    public static NarrativizationConfig disabled() {
        return new NarrativizationConfig(false, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enabled = true;
        private String template;
        private String connectorType;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder template(String template) {
            this.template = template;
            return this;
        }

        public Builder connectorType(String connectorType) {
            this.connectorType = connectorType;
            return this;
        }

        public NarrativizationConfig build() {
            return new NarrativizationConfig(enabled, template, connectorType);
        }
    }
}
