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

import java.util.List;
import java.util.Objects;

/**
 * What a crawl of a scope would pick up, as far as the caller may see it.
 *
 * @param matchCount       how many matching documents the filter shows; for a scope whose items cannot be
 *                         checked one by one (a query), how many match in all, reported as {@code ESTIMATE}
 * @param countAccuracy    how far {@code matchCount} can be trusted
 * @param samples          up to the requested number of visible matching documents, never {@code null}
 * @param hiddenCount      how many matching documents the filter hid, or {@code null} when they were not checked
 * @param truncationReason why the preview stopped early, or {@code null}
 */
public record DryRunResult(long matchCount, CountAccuracy countAccuracy, List<DiscoveryNode> samples,
                           Long hiddenCount, TruncationReason truncationReason) {

    public DryRunResult {
        Objects.requireNonNull(countAccuracy, "countAccuracy");
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public boolean truncated() {
        return truncationReason != null;
    }
}
