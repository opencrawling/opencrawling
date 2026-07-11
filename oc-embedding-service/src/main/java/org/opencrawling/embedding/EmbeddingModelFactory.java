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
package org.opencrawling.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class EmbeddingModelFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelFactory.class);
    
    private final EmbeddingModel defaultModel;
    private final ConcurrentHashMap<String, EmbeddingModel> activeClients = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    public EmbeddingModelFactory(@org.springframework.beans.factory.annotation.Qualifier("ollamaEmbeddingModel") EmbeddingModel defaultModel) {
        this.defaultModel = defaultModel;
    }

    public EmbeddingModel getModel(String engineType, Map<String, String> config) {
        if (engineType == null || engineType.isBlank()) {
            log.info("Using autowired default embedding model.");
            return defaultModel;
        }

        String modelName = config != null ? config.getOrDefault("model", "default") : "default";
        String cacheKey = engineType.toLowerCase() + "-" + modelName;

        return activeClients.computeIfAbsent(cacheKey, key -> {
            log.info("Creating dynamic embedding client for engine: {} with model: {}", engineType, modelName);
            switch (engineType.toLowerCase()) {
                case "ollama":
                    String baseUrl = config != null ? config.getOrDefault("baseUrl", ollamaBaseUrl) : ollamaBaseUrl;
                    var ollamaApi = org.springframework.ai.ollama.api.OllamaApi.builder()
                        .baseUrl(baseUrl)
                        .build();
                    return org.springframework.ai.ollama.OllamaEmbeddingModel.builder()
                        .ollamaApi(ollamaApi)
                        .options(org.springframework.ai.ollama.api.OllamaEmbeddingOptions.builder()
                            .model(modelName)
                            .build())
                        .build();
                case "openai":
                    String apiKey = config != null ? config.get("apiKey") : null;
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalArgumentException("API Key is required for OpenAI embedding engine.");
                    }
                    return new org.springframework.ai.openai.OpenAiEmbeddingModel(
                        org.springframework.ai.openai.OpenAiEmbeddingOptions.builder()
                            .apiKey(apiKey)
                            .model(modelName)
                            .build()
                    );
                default:
                    throw new IllegalArgumentException("Unsupported embedding engine: " + engineType);
            }
        });
    }
}
