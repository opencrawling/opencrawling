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
 * Bounds for previewing what a crawl would pick up.
 *
 * @param scope      what to preview: a folder id or path, or a read-only query in the connector's own query
 *                   language; {@code null} for the connector's configured scope
 * @param sampleSize how many matching documents to return as samples
 * @param maxScan    the most documents to look at before reporting a lower-bound count
 * @param timeout    how long the preview may take
 */
public record DryRunRequest(String scope, int sampleSize, int maxScan, Duration timeout) {

    public DryRunRequest {
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize must not be negative");
        }
        if (maxScan < 1) {
            throw new IllegalArgumentException("maxScan must be at least 1");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        scope = scope == null || scope.isBlank() ? null : scope.strip();
    }
}
