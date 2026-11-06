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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import reactor.core.publisher.Flux;

/**
 * A "crawl" of exactly one document that was already fetched (or of one tombstone), so that it goes through the
 * same path as every crawled document: content to the claim-check store with its own type and name, a message on
 * the documents topic, and a ledger entry.
 */
final class SingleDocumentConnector implements RepositoryConnector {

    private final RepositoryDocument document;
    private final RepositoryConnector source;

    /**
     * @param document the document to emit
     * @param source   the connector that fetched it, disconnected with this one; {@code null} for none
     */
    SingleDocumentConnector(RepositoryDocument document, RepositoryConnector source) {
        this.document = document;
        this.source = source;
    }

    /** Emits the document, whatever {@code basePath} is. */
    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.just(document);
    }

    @Override
    public String getName() {
        return source != null ? source.getName() : "single-document";
    }

    @Override
    public void connect() {
    }

    @Override
    public void disconnect() throws Exception {
        if (source != null) {
            source.disconnect();
        }
    }
}
