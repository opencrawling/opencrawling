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

import java.time.Instant;
import java.util.List;

/**
 * Item entry returned during folder listing or queries in Hyland Content Innovation Cloud.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HylandCicItem(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("type") String type, // "folder" or "document"
        @JsonProperty("contentType") String contentType,
        @JsonProperty("fileSize") Long fileSize,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("modified") Instant modified,
        @JsonProperty("permissions") List<HylandCicPermission> permissions
) {
    public boolean isFolder() {
        return "folder".equalsIgnoreCase(type);
    }

    public boolean isDocument() {
        return "document".equalsIgnoreCase(type) || type == null;
    }
}
