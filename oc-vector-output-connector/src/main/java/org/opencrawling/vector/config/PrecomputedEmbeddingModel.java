package org.opencrawling.vector.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

public class PrecomputedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;

    public PrecomputedEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
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
        return delegate.embed(document);
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
