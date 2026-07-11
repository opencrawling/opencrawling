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
package org.opencrawling.vector.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

public class PrecomputedEmbeddingModel implements EmbeddingModel {

    private final int defaultDimensions;

    public PrecomputedEmbeddingModel() {
        this.defaultDimensions = 1536;
    }

    public PrecomputedEmbeddingModel(int defaultDimensions) {
        this.defaultDimensions = defaultDimensions;
    }

    @Override
    public float[] embed(Document document) {
        Object emb = document.getMetadata().get("embedding");
        if (emb instanceof float[]) {
            return (float[]) emb;
        } else if (emb instanceof List) {
            List<?> list = (List<?>) emb;
            float[] vector = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                vector[i] = ((Number) list.get(i)).floatValue();
            }
            return vector;
        }
        throw new IllegalStateException("Embedding vector is missing in Document metadata. " +
            "The output connector is designed to be decoupled and requires precomputed embeddings.");
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions()];
        vector[0] = 1.0f; // Avoid zero-vector division by zero in pgvector
        return vector;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<org.springframework.ai.embedding.Embedding> embeddings = request.getInstructions().stream()
            .map(text -> {
                float[] vector = new float[dimensions()];
                vector[0] = 1.0f;
                return new org.springframework.ai.embedding.Embedding(vector, 0);
            })
            .toList();
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
        return defaultDimensions; 
    }
}
