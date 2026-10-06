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
package org.opencrawling.core.connector;

import org.opencrawling.core.document.RepositoryDocument;
import reactor.core.publisher.Flux;

public non-sealed interface RepositoryConnector extends Connector {
    Flux<RepositoryDocument> scan(String basePath);

    default ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(java.util.List.of());
    }

    /**
     * Whether the standalone crawler may process this connector's documents in parallel lanes
     * ({@code spring.opencrawling.crawler.concurrency}).
     *
     * <p>Parallel processing decouples the scan from document processing, so the scan runs ahead and up to a few
     * hundred emitted documents are buffered. Return {@code true} only if a buffered document holds no scarce
     * resource (open connection, cursor, large in-memory payload) until its content is read, e.g. content streams
     * that are opened lazily, and if documents can be handled from any thread. Defaults to {@code false}
     * (sequential processing, the scan is paced by processing).
     */
    default boolean supportsConcurrentProcessing() {
        return false;
    }
}
