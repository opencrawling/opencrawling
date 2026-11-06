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

/**
 * A discoverable connector that can also fetch one document by id, the way its crawl would emit it.
 *
 * <p>This is how a single document someone else asks for (an agent, a workflow) is held to the connector's scope:
 * the document must be one the crawl would take (inside the configured root, not in an excluded folder, matching
 * the version and type filters) and one the filter shows.
 */
public interface DocumentFetchingRepositoryConnector extends DiscoverableRepositoryConnector {

    /**
     * Fetches a document by its repository id.
     *
     * @param documentId the document's id in the repository (a {@link DiscoveryNode#sourceId()}), never a path
     * @return {@link FetchedDocument.Found} with its content, or {@link FetchedDocument.Gone} when the repository
     *         reports no such document
     * @throws IllegalArgumentException when the document exists but is not a document the crawl would take, is out
     *                                  of scope or is hidden by the filter, or the id is not a valid id (all
     *                                  reported alike)
     * @throws DiscoveryException       when the repository cannot be reached or answers with an error
     */
    FetchedDocument fetchDocument(String documentId, VisibilityFilter filter);
}
