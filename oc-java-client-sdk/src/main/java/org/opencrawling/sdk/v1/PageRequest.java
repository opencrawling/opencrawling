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

/**
 * Which page of a collection to read.
 *
 * @param limit  page size, 1 to 500; null for the runtime's default (50). Keep it the same while following cursors:
 *               an execution's documents refuse a cursor read with another page size
 * @param cursor the {@code nextCursor} of the previous page; null for the first page
 */
public record PageRequest(Integer limit, String cursor) {

    private static final PageRequest FIRST = new PageRequest(null, null);

    public static PageRequest first() {
        return FIRST;
    }

    public static PageRequest ofSize(int limit) {
        return new PageRequest(limit, null);
    }

    public PageRequest next(Page<?> page) {
        return new PageRequest(limit, page.page() != null ? page.page().nextCursor() : null);
    }
}
