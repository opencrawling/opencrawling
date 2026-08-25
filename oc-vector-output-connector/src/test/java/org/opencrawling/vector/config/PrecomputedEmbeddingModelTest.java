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
package org.opencrawling.vector.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrecomputedEmbeddingModelTest {

    private PrecomputedEmbeddingModel model;

    @BeforeEach
    void setUp() {
        model = new PrecomputedEmbeddingModel(1024);
        PrecomputedEmbeddingModel.clear();
    }

    @AfterEach
    void tearDown() {
        PrecomputedEmbeddingModel.clear();
    }

    @Test
    void testFallbackOneHotVector() {
        float[] vector = model.embed("some text");
        assertEquals(1024, vector.length);
        assertEquals(1.0f, vector[0]);
        assertEquals(0.0f, vector[1]);
    }

    @Test
    void testPrecomputedVectorFromThreadLocalByText() {
        float[] expectedVector = new float[]{0.1f, 0.2f, 0.3f};
        PrecomputedEmbeddingModel.setPrecomputedVector("hello world", expectedVector);

        float[] actual = model.embed("hello world");
        assertArrayEquals(expectedVector, actual);
    }

    @Test
    void testPrecomputedVectorsInEmbeddingRequest() {
        float[] v1 = new float[]{0.5f, 0.5f};
        float[] v2 = new float[]{0.9f, 0.1f};
        PrecomputedEmbeddingModel.setPrecomputedVectors(Map.of("chunk1", v1, "chunk2", v2));

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("chunk1", "chunk2"), null));
        assertEquals(2, response.getResults().size());
        assertArrayEquals(v1, response.getResults().get(0).getOutput());
        assertArrayEquals(v2, response.getResults().get(1).getOutput());
    }

    @Test
    void testMetadataEmbeddingTakesPrecedenceInDocument() {
        float[] metaVector = new float[]{0.7f, 0.8f};
        Document doc = new Document("id1", "content", Map.of("embedding", metaVector));

        float[] actual = model.embed(doc);
        assertArrayEquals(metaVector, actual);
    }
}
