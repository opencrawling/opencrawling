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

import java.util.Arrays;
import java.util.List;

/**
 * Decides which discovered items the caller may see. A connector applies it to each page as it walks, so that
 * folders the caller cannot see are never opened and the bounds count only what the caller sees.
 *
 * <p>A filter that cannot decide must throw; the connector lets the exception through, so discovery fails closed.
 */
public interface VisibilityFilter {

    /** Shows everything: for administrative use and tests, never for a request on behalf of a user. */
    VisibilityFilter ALL = new VisibilityFilter() {
        @Override
        public boolean[] visible(List<DiscoveryNode> nodes) {
            boolean[] all = new boolean[nodes.size()];
            Arrays.fill(all, true);
            return all;
        }

        @Override
        public boolean showsEverything() {
            return true;
        }
    };

    /**
     * Decides a batch.
     *
     * @return one decision per node, in the same order
     */
    boolean[] visible(List<DiscoveryNode> nodes);

    /**
     * Whether this filter shows every item. Lets a connector report a count it did not check item by item as
     * exact.
     */
    default boolean showsEverything() {
        return false;
    }

    /**
     * Applies a filter, checking that it answered once per node.
     *
     * @throws IllegalStateException when it did not
     */
    static boolean[] decide(VisibilityFilter filter, List<DiscoveryNode> nodes) {
        if (nodes.isEmpty()) {
            return new boolean[0];
        }
        boolean[] decisions = filter.visible(nodes);
        if (decisions == null || decisions.length != nodes.size()) {
            throw new IllegalStateException("The visibility filter returned "
                    + (decisions == null ? "no" : decisions.length) + " decisions for " + nodes.size() + " items.");
        }
        return decisions;
    }
}
