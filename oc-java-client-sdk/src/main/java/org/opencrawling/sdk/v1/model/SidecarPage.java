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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One page of OIS metadata sidecars returned by {@code GET /api/v1/sidecar-stores/{connector}/sidecars}.
 *
 * @param connector  sidecar store connector name
 * @param prefix     optional object key prefix filter
 * @param items      authorized OIS sidecar documents in this page
 * @param cursor     cursor used for this page, if any
 * @param nextCursor cursor for the next page, or {@code null} on the last page
 * @param truncated  whether scanning was bounded before exhausting the store
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SidecarPage(
        String connector,
        String prefix,
        List<Map<String, Object>> items,
        String cursor,
        String nextCursor,
        boolean truncated
) {
    public SidecarPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasNext() {
        return nextCursor != null && !nextCursor.isBlank();
    }
}
