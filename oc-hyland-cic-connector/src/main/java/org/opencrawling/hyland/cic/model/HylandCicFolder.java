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
 * Folder / Dossier representation in Hyland Content Innovation Cloud (HXP).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HylandCicFolder(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("created") Instant created,
        @JsonProperty("modified") Instant modified,
        @JsonProperty("permissions") List<HylandCicPermission> permissions
) {
    public HylandCicFolder {
        if (permissions == null) {
            permissions = List.of();
        }
    }
}
