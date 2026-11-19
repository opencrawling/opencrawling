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
package org.opencrawling.core.messaging;

/**
 * The Kafka topics the pipeline's stages exchange documents on. Every stage, writer and connector that names one uses
 * these constants, so a rename reaches all of them (a writer reading another topic than the embedding stage publishes
 * to would silently store nothing).
 */
public final class PipelineTopics {

    /** Documents to ingest (claim-check references and tombstones), published by crawls and pushes. */
    public static final String DOCUMENTS = "opencrawling-documents";

    /** Chunks of extracted text, published by the ingestion stage. */
    public static final String CHUNKS = "opencrawling-chunks";

    /** Chunks with their embeddings, published by the embedding stage and read by the store writers. */
    public static final String EMBEDDED = "opencrawling-embedded";

    private PipelineTopics() {}
}
