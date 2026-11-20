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
import java.util.List;
import java.util.ArrayList;

/**
 * Request payload for Auto-Narrativization Copilot template generation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CopilotRequest(
    String connectorType,
    List<FieldDto> fields
) {

    public record FieldDto(
        String name,
        String type,
        String description
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String connectorType;
        private List<FieldDto> fields = new ArrayList<>();

        public Builder connectorType(String connectorType) {
            this.connectorType = connectorType;
            return this;
        }

        public Builder fields(List<FieldDto> fields) {
            this.fields = fields != null ? fields : new ArrayList<>();
            return this;
        }

        public Builder addField(String name, String type, String description) {
            if (this.fields == null) {
                this.fields = new ArrayList<>();
            }
            this.fields.add(new FieldDto(name, type, description));
            return this;
        }

        public CopilotRequest build() {
            return new CopilotRequest(connectorType, fields);
        }
    }
}
