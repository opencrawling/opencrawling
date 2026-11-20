/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.core.embedding;

import java.util.function.Function;

/**
 * A chunk is stored only with its real vector (#329, #333). A placeholder vector would be saved but never found by a
 * similarity search, while the run reports success; so a missing model, a failed embedding call or an empty vector
 * fails the document instead. Output connectors call these before they write anything; Kafka writer consumers call
 * {@link #ofMessage} so the record is retried and dead-lettered.
 */
public final class RequiredEmbeddings {

    private RequiredEmbeddings() {
    }

    /**
     * Refuses a document when no embedding model is configured.
     *
     * @throws IllegalStateException when {@code model} is null
     */
    public static void requireModel(Object model, String documentId) {
        if (model == null) {
            throw new IllegalStateException("No embedding model is configured, so document " + documentId
                    + " cannot be embedded; nothing was stored");
        }
    }

    /**
     * The vector of one chunk, from {@code embedder}; never a placeholder.
     *
     * @throws IllegalStateException when the call fails or returns no vector
     */
    public static float[] embed(Function<String, float[]> embedder, String text, String documentId) {
        float[] vector;
        try {
            vector = embedder.apply(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Embedding failed for document " + documentId + "; nothing was stored: "
                    + e.getMessage(), e);
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("The embedding model returned no vector for document " + documentId
                    + "; nothing was stored");
        }
        return vector;
    }

    /**
     * The vector a Kafka message carries for an upsert.
     *
     * @throws IllegalArgumentException when it has none, so the record is retried and dead-lettered
     */
    public static float[] ofMessage(float[] embedding, String chunkId, String documentId) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("chunk " + chunkId + " of document " + documentId + " has no embedding");
        }
        return embedding;
    }
}
