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

/**
 * Response DTO representing an OpenCrawling job status and details.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobResponse(
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
    public String getId() {
        return id;
    }
}
