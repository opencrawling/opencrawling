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
package org.opencrawling.hyland.cic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Access Control Entry (ACE) for Hyland Content Innovation Cloud (HXP) resources.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HylandCicPermission(
        @JsonProperty("identity") String identity,
        @JsonProperty("type") String type,
        @JsonProperty("permission") String permission,
        @JsonProperty("granted") boolean granted
) {
    public HylandCicPermission {
        if (identity == null) {
            identity = "";
        }
        if (type == null) {
            type = "USER";
        }
        if (permission == null) {
            permission = "READ";
        }
    }
}
