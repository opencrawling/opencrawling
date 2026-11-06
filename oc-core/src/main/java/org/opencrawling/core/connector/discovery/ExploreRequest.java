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
package org.opencrawling.core.connector.discovery;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds for listing a repository's tree.
 *
 * <p>The walk is breadth-first from {@code start}, so a truncated result always holds the levels closest to it.
 *
 * @param start            where to start: a repository id or path, or {@code null} for the connector's configured
 *                         root
 * @param maxDepth         how many levels below {@code start} to list (1 = its children only)
 * @param maxNodes         the most nodes to return
 * @param includeDocuments whether to list documents as well as folders
 * @param timeout          how long the walk may take
 */
public record ExploreRequest(String start, int maxDepth, int maxNodes, boolean includeDocuments, Duration timeout) {

    public ExploreRequest {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be at least 1");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        start = start == null || start.isBlank() ? null : start.strip();
    }
}
