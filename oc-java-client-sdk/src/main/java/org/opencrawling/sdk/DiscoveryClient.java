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
package org.opencrawling.sdk;

import org.opencrawling.sdk.models.DiscoveryConnectorInfo;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunView;
import org.opencrawling.sdk.models.DiscoveryTreeRequest;
import org.opencrawling.sdk.models.DiscoveryTreeView;

import java.util.List;

/**
 * Client for pre-crawl repository discovery and dry-run inspection (/api/discovery/**).
 */
public interface DiscoveryClient {

    /**
     * Lists registered repository connectors that support discovery.
     *
     * @return list of discoverable repository connectors
     */
    List<DiscoveryConnectorInfo> listConnectors();

    /**
     * Lists registered repository connectors that support discovery with asserted caller identity.
     *
     * @param userPrincipal asserted user principal (dev/test only)
     * @param userRoles asserted comma-separated roles/groups (dev/test only)
     * @return list of discoverable repository connectors
     */
    List<DiscoveryConnectorInfo> listConnectors(String userPrincipal, String userRoles);

    /**
     * Explores a repository's folder/document tree breadth-first using request parameters.
     *
     * @param connector repository connector name
     * @param request exploration parameters
     * @return discovery tree view with visible nodes
     */
    DiscoveryTreeView exploreTree(String connector, DiscoveryTreeRequest request);

    /**
     * Explores a repository's folder/document tree breadth-first with common parameters.
     *
     * @param connector repository connector name
     * @param start starting folder ID or path (null for configured root)
     * @param depth exploration depth limit
     * @param maxNodes maximum nodes to return
     * @param includeDocuments whether to include document nodes
     * @return discovery tree view with visible nodes
     */
    default DiscoveryTreeView exploreTree(String connector, String start, Integer depth, Integer maxNodes, Boolean includeDocuments) {
        return exploreTree(connector, DiscoveryTreeRequest.builder()
                .start(start)
                .depth(depth)
                .maxNodes(maxNodes)
                .includeDocuments(includeDocuments)
                .build());
    }

    /**
     * Previews a crawl of a folder scope or query (dry-run).
     *
     * @param connector repository connector name
     * @param request dry-run parameters
     * @return dry-run preview with match counts and samples
     */
    DiscoveryDryRunView dryRun(String connector, DiscoveryDryRunRequest request);

    /**
     * Previews a crawl of a folder scope or query with common parameters.
     *
     * @param connector repository connector name
     * @param scope folder ID/path or read-only query (null for configured scope)
     * @param sampleSize maximum sample documents to return
     * @param maxScan maximum items scanned before returning a lower-bound count
     * @return dry-run preview with match counts and samples
     */
    default DiscoveryDryRunView dryRun(String connector, String scope, Integer sampleSize, Integer maxScan) {
        return dryRun(connector, DiscoveryDryRunRequest.builder()
                .scope(scope)
                .sampleSize(sampleSize)
                .maxScan(maxScan)
                .build());
    }
}
