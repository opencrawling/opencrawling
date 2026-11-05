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
 * stopped early. Every node carries the access control the source reports for it, so the caller can decide what a
 * user may see; a connector never filters by user itself.
 *
 * <p>Implementations must reject a query that could change the repository: a dry-run scope is read-only.
 */
public interface DiscoverableRepositoryConnector extends RepositoryConnector {

    /**
     * Lists folders (and, if asked, documents) below the request's starting node.
     *
     * @throws IllegalArgumentException when the starting node does not exist or is not a folder
     * @throws DiscoveryException       when the repository cannot be reached or answers with an error
     */
    DiscoveryResult explore(ExploreRequest request);

    /**
     * Counts the documents a crawl of the request's scope would pick up and returns some of them.
     *
     * @throws IllegalArgumentException when the scope is not a folder or a read-only query
     * @throws DiscoveryException       when the repository cannot be reached or answers with an error
     */
    DryRunResult dryRun(DryRunRequest request);
}
