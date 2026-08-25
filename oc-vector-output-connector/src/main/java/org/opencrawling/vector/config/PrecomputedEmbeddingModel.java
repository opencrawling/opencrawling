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
import java.util.Map;

public class PrecomputedEmbeddingModel implements EmbeddingModel {

    private static final ThreadLocal<Map<String, float[]>> PRECOMPUTED_VECTORS = new ThreadLocal<>();

    private final int defaultDimensions;

    public PrecomputedEmbeddingModel() {
        this.defaultDimensions = 1536;
    }

    public PrecomputedEmbeddingModel(int defaultDimensions) {
        this.defaultDimensions = defaultDimensions;
    }

    public static void setPrecomputedVector(String text, float[] vector) {
        if (text != null && vector != null) {
            Map<String, float[]> map = PRECOMPUTED_VECTORS.get();
            if (map == null) {
                map = new java.util.HashMap<>();
                PRECOMPUTED_VECTORS.set(map);
            }
            map.put(text, vector);
        }
    }

    public static void setPrecomputedVectors(Map<String, float[]> vectors) {
        if (vectors != null) {
            PRECOMPUTED_VECTORS.set(new java.util.HashMap<>(vectors));
        }
    }

    public static void clear() {
        PRECOMPUTED_VECTORS.remove();
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
        Map<String, float[]> map = PRECOMPUTED_VECTORS.get();
        if (map != null && document.getText() != null && map.containsKey(document.getText())) {
            return map.get(document.getText());
        }
        if (map != null && map.size() == 1) {
            return map.values().iterator().next();
        }
        return createFallbackVector();
    }

    @Override
    public float[] embed(String text) {
        Map<String, float[]> map = PRECOMPUTED_VECTORS.get();
        if (map != null && text != null && map.containsKey(text)) {
            return map.get(text);
        }
        if (map != null && map.size() == 1) {
            return map.values().iterator().next();
        }
        return createFallbackVector();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Map<String, float[]> map = PRECOMPUTED_VECTORS.get();
        List<org.springframework.ai.embedding.Embedding> embeddings = request.getInstructions().stream()
            .map(text -> {
                float[] vector = null;
                if (map != null && text != null && map.containsKey(text)) {
                    vector = map.get(text);
                } else if (map != null && map.size() == 1) {
                    vector = map.values().iterator().next();
                }
                if (vector == null) {
                    vector = createFallbackVector();
                }
                return new org.springframework.ai.embedding.Embedding(vector, 0);
            })
            .toList();
        return new EmbeddingResponse(embeddings);
    }

    private float[] createFallbackVector() {
        float[] vector = new float[dimensions()];
        vector[0] = 1.0f; // Avoid zero-vector division by zero in pgvector
        return vector;
    }

    @Override
    public int dimensions() {
        return defaultDimensions; 
    }
}
