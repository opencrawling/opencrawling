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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * System-wide OpenCrawling settings and configuration parameters.
 *
 * @param embeddingProvider default embedding provider (e.g. ollama, openai)
 * @param ollamaBaseUrl base URL for the Ollama service
 * @param ollamaModel model name used for embedding generation
 * @param vectorDimensions vector embedding dimensions
 * @param chunkerType semantic chunking implementation
 * @param chunkSize token chunk size
 * @param chunkOverlap token chunk overlap
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemSettings(
    String embeddingProvider,
    String ollamaBaseUrl,
    String ollamaModel,
    int vectorDimensions,
    String chunkerType,
    int chunkSize,
    int chunkOverlap
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String embeddingProvider = "Ollama";
        private String ollamaBaseUrl = "http://127.0.0.1:11434";
        private String ollamaModel = "mxbai-embed-large";
        private int vectorDimensions = 1024;
        private String chunkerType = "TokenTextSplitter";
        private int chunkSize = 800;
        private int chunkOverlap = 100;

        public Builder embeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
            return this;
        }

        public Builder ollamaBaseUrl(String ollamaBaseUrl) {
            this.ollamaBaseUrl = ollamaBaseUrl;
            return this;
        }

        public Builder ollamaModel(String ollamaModel) {
            this.ollamaModel = ollamaModel;
            return this;
        }

        public Builder vectorDimensions(int vectorDimensions) {
            this.vectorDimensions = vectorDimensions;
            return this;
        }

        public Builder chunkerType(String chunkerType) {
            this.chunkerType = chunkerType;
            return this;
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder chunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
            return this;
        }

        public SystemSettings build() {
            return new SystemSettings(
                embeddingProvider,
                ollamaBaseUrl,
                ollamaModel,
                vectorDimensions,
                chunkerType,
                chunkSize,
                chunkOverlap
            );
        }
    }
}
