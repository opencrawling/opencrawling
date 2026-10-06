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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request payload for creating or updating an OpenCrawling Job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobRequest(
    String id,
    String name,
    String repositoryConnector,
    String outputConnector,
    String authorityConnector,
    String path,
    String status,
    String currentStage,
    long documents,
    String lastRun,
    String transformationConnector,
    NarrativizationConfig narrativization,
    String pipelineMode
) {

    public JobRequest {
        if (narrativization == null) {
            narrativization = NarrativizationConfig.disabled();
        }
    }

    public JobRequest(
        String id,
        String name,
        String repositoryConnector,
        String outputConnector,
        String authorityConnector,
        String path,
        String status,
        String currentStage,
        long documents,
        String lastRun,
        String transformationConnector,
        NarrativizationConfig narrativization
    ) {
        this(id, name, repositoryConnector, outputConnector, authorityConnector, path, status, currentStage, documents, lastRun, transformationConnector, narrativization, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private String repositoryConnector = "FileSystem_Local";
        private String outputConnector = "PGVector_Output";
        private String authorityConnector = "";
        private String path = "/data";
        private String status = "Ready";
        private String currentStage = "Idle";
        private long documents = 0;
        private String lastRun = "N/A";
        private String transformationConnector = "Ollama_Embedding_Default";
        private NarrativizationConfig narrativization;
        private String pipelineMode; // null = inherit the server-side opencrawling.pipeline.mode default

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder repositoryConnector(String repositoryConnector) {
            this.repositoryConnector = repositoryConnector;
            return this;
        }

        public Builder outputConnector(String outputConnector) {
            this.outputConnector = outputConnector;
            return this;
        }

        public Builder authorityConnector(String authorityConnector) {
            this.authorityConnector = authorityConnector;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder targetUrl(String targetUrl) {
            this.path = targetUrl;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder currentStage(String currentStage) {
            this.currentStage = currentStage;
            return this;
        }

        public Builder documents(long documents) {
            this.documents = documents;
            return this;
        }

        public Builder lastRun(String lastRun) {
            this.lastRun = lastRun;
            return this;
        }

        public Builder transformationConnector(String transformationConnector) {
            this.transformationConnector = transformationConnector;
            return this;
        }

        public Builder narrativization(NarrativizationConfig narrativization) {
            this.narrativization = narrativization;
            return this;
        }

        public Builder pipelineMode(String pipelineMode) {
            this.pipelineMode = pipelineMode;
            return this;
        }

        public JobRequest build() {
            return new JobRequest(
                id,
                name,
                repositoryConnector,
                outputConnector,
                authorityConnector,
                path,
                status,
                currentStage,
                documents,
                lastRun,
                transformationConnector,
                narrativization != null ? narrativization : NarrativizationConfig.disabled(),
                pipelineMode
            );
        }
    }
}
