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
 * Results of a breadth-first tree exploration of a repository hierarchy.
 *
 * @param connector the repository connector name
 * @param root the starting folder ID or path
 * @param nodes visible nodes discovered
 * @param hiddenCount count of items hidden by caller ACLs
 * @param truncated whether the exploration stopped early due to limits
 * @param truncationReason reason why the exploration stopped early (e.g. MAX_DEPTH, MAX_NODES, TIMEOUT)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryTreeView(
    String connector,
    String root,
    List<DiscoveryNodeView> nodes,
    Long hiddenCount,
    boolean truncated,
    String truncationReason
) {
    public DiscoveryTreeView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
