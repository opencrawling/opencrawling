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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.opencrawling.sdk.models.JobAccess;
import org.opencrawling.sdk.models.NarrativizationConfig;

import java.util.Map;

/**
 * A job definition, to create or replace a job. Only {@code name}, {@code repositoryConnector},
 * {@code outputConnector} and {@code path} are required; start from {@link #of} and add the rest with the
 * {@code with...} methods.
 *
 * @param pipelineMode {@code rag} or {@code migration}; null for the runtime's default
 * @param access       who may see and run the job when management authorization is on; on create the caller
 *                     becomes the owner, and on replace null keeps the current access
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobInput(String name, String repositoryConnector, String outputConnector, String authorityConnector,
                       String transformationConnector, String path, String pipelineMode,
                       Map<String, Object> crawlSpec, NarrativizationConfig narrativization, JobAccess access) {

    public static JobInput of(String name, String repositoryConnector, String outputConnector, String path) {
        return new JobInput(name, repositoryConnector, outputConnector, null, null, path, null, null, null, null);
    }

    public JobInput withName(String value) {
        return new JobInput(value, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path, pipelineMode, crawlSpec, narrativization, access);
    }

    public JobInput withPath(String value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, value, pipelineMode, crawlSpec, narrativization, access);
    }

    public JobInput withAuthorityConnector(String value) {
        return new JobInput(name, repositoryConnector, outputConnector, value, transformationConnector, path, pipelineMode, crawlSpec, narrativization, access);
    }

    public JobInput withTransformationConnector(String value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, value, path, pipelineMode, crawlSpec, narrativization, access);
    }

    public JobInput withPipelineMode(String value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path, value, crawlSpec, narrativization, access);
    }

    public JobInput withCrawlSpec(Map<String, Object> value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path, pipelineMode, value, narrativization, access);
    }

    public JobInput withNarrativization(NarrativizationConfig value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path, pipelineMode, crawlSpec, value, access);
    }

    public JobInput withAccess(JobAccess value) {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path, pipelineMode, crawlSpec, narrativization, value);
    }
}
