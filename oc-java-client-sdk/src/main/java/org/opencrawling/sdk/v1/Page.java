/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.sdk.v1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One page of a collection. Pass {@link PageInfo#nextCursor()} as the cursor of a {@link PageRequest} for the next
 * one, or use the client's {@code stream} methods, which follow the cursors for you.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Page<T>(List<T> items, PageInfo page) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageInfo(int limit, String nextCursor, int totalItems) {}

    public Page {
        items = items != null ? List.copyOf(items) : List.of();
    }

    /** Whether there is a next page. */
    @JsonIgnore
    public boolean hasNext() {
        return page != null && page.nextCursor() != null;
    }
}
