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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Results of a crawl dry-run preview.
 *
 * @param connector the repository connector name
 * @param matchCount count of matching documents (null when withheld for query scopes)
 * @param countAccuracy accuracy tier (EXACT, ESTIMATE, LOWER_BOUND)
 * @param samples representative visible sample documents
 * @param hiddenCount count of matching documents hidden by caller ACLs (if revealed)
 * @param truncated whether the preview stopped early due to limits
 * @param truncationReason reason why the preview stopped early (e.g. MAX_SCAN, TIMEOUT)
 * @param note informational note (e.g. why matchCount was withheld or estimated)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryDryRunView(
    String connector,
    Long matchCount,
    String countAccuracy,
    List<DiscoveryNodeView> samples,
    Long hiddenCount,
    boolean truncated,
    String truncationReason,
    String note
) {
    public DiscoveryDryRunView {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }
}
