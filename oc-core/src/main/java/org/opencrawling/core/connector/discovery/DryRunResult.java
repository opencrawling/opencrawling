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
 * What a crawl of a scope would pick up, before any authorization check.
 *
 * <p>The count covers every matching document in the source. Callers that expose it to a user must check the
 * samples, and report the count as an estimate, since the user may not be allowed to read all of them.
 *
 * @param matchCount       how many documents match
 * @param countAccuracy    how far {@code matchCount} can be trusted
 * @param samples          up to the requested number of matching documents, never {@code null}
 * @param truncationReason why the preview stopped early, or {@code null}
 */
public record DryRunResult(long matchCount, CountAccuracy countAccuracy, List<DiscoveryNode> samples,
                           TruncationReason truncationReason) {

    public DryRunResult {
        Objects.requireNonNull(countAccuracy, "countAccuracy");
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public boolean truncated() {
        return truncationReason != null;
    }
}
