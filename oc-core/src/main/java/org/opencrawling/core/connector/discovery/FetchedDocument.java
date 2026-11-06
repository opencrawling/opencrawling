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

import org.opencrawling.core.document.RepositoryDocument;

import java.util.Objects;

/**
 * What a repository says about one document a caller asked for by id.
 *
 * <p>There is no third answer for a document that exists but is out of the connector's scope or hidden from the
 * caller: {@link DocumentFetchingRepositoryConnector#fetchDocument} refuses those as not found.
 */
public sealed interface FetchedDocument {

    /**
     * The document exists, is in scope and visible to the caller.
     *
     * @param node     the document as discovery describes it, with its source ACL
     * @param document the document as a crawl would emit it, under the same id, with its content
     */
    record Found(DiscoveryNode node, RepositoryDocument document) implements FetchedDocument {
        public Found {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(document, "document");
        }
    }

    /**
     * The repository has no document with that id (any more).
     *
     * @param indexedId the id a crawl would have indexed it under, for a tombstone
     */
    record Gone(String indexedId) implements FetchedDocument {
        public Gone {
            Objects.requireNonNull(indexedId, "indexedId");
        }
    }
}
