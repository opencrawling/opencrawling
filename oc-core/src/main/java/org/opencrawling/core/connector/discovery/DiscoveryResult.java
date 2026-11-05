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

/**
 * The nodes an exploration found, in breadth-first order.
 *
 * @param nodes            the nodes, never {@code null}
 * @param truncationReason why the walk stopped early, or {@code null} when it covered everything in its bounds
 */
public record DiscoveryResult(List<DiscoveryNode> nodes, TruncationReason truncationReason) {

    public DiscoveryResult {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public boolean truncated() {
        return truncationReason != null;
    }
}
