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
package org.opencrawling.core.connector;

import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import reactor.core.publisher.Mono;

public non-sealed interface OutputConnector extends Connector {
    Mono<Void> send(RepositoryDocument document);

    /**
     * Whether, in {@code mode}, this output's documents are written by its Kafka pipeline writer, so a job run must
     * not also {@link #send} them. A run publishes every document to the documents topic either way; writing it a
     * second time directly would store it twice, chunked and identified differently.
     *
     * <p>Vector stores return {@code true} for {@link PipelineMode#RAG}: their writer consumes
     * {@code opencrawling-embedded}. The default, {@code false}, keeps the direct write, for outputs that have no
     * pipeline writer for that mode.
     *
     * @param mode the run's pipeline mode
     * @return {@code true} if the run must not call {@link #send}
     */
    default boolean writtenByPipeline(PipelineMode mode) {
        return false;
    }
}
