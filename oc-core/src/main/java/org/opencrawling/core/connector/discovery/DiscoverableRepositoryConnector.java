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

import org.opencrawling.core.connector.RepositoryConnector;

/**
 * A repository connector that can describe what it would crawl without crawling it: list the tree below a node,
 * and preview what a scope matches.
 *
 * <p>Neither operation reads document content. Both stay within the request's bounds and report when they
 * stopped early. Every node carries the access control the source reports for it.
 *
 * <p>What a caller sees is decided by a {@link VisibilityFilter}, applied page by page: a folder the caller cannot
 * see is not opened (so nothing below it is named), a starting folder the caller cannot see is reported as not
 * found, and the bounds count only visible items.
 *
 * <p>Implementations must reject a query that could change the repository: a dry-run scope is read-only.
 */
public interface DiscoverableRepositoryConnector extends RepositoryConnector {

    /**
     * Lists the folders (and, if asked, documents) below the request's starting folder that the filter shows.
     *
     * @throws IllegalArgumentException when the starting folder does not exist, is not a folder, is out of scope,
     *                                  or is hidden by the filter
     * @throws DiscoveryException       when the repository cannot be reached or answers with an error
     */
    DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter);

    /**
     * Counts the documents a crawl of the request's scope would pick up that the filter shows, and returns some of
     * them.
     *
     * @throws IllegalArgumentException when the scope is not a folder or a read-only query, or is a folder hidden by
     *                                  the filter
     * @throws DiscoveryException       when the repository cannot be reached or answers with an error
     */
    DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter);

    /** {@link #explore(ExploreRequest, VisibilityFilter)} showing everything. */
    default DiscoveryResult explore(ExploreRequest request) {
        return explore(request, VisibilityFilter.ALL);
    }

    /** {@link #dryRun(DryRunRequest, VisibilityFilter)} showing everything. */
    default DryRunResult dryRun(DryRunRequest request) {
        return dryRun(request, VisibilityFilter.ALL);
    }
}
