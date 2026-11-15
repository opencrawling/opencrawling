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

import java.util.List;

/**
 * Paged response wrapper for Hyland Content Innovation Cloud API requests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HylandCicPagedResult(
        @JsonProperty("items") List<HylandCicItem> items,
        @JsonProperty("totalCount") Long totalCount,
        @JsonProperty("hasMore") boolean hasMore,
        @JsonProperty("nextOffset") Integer nextOffset
) {
    public HylandCicPagedResult {
        if (items == null) {
            items = List.of();
        }
    }
}
