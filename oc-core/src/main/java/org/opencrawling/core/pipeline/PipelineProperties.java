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
package org.opencrawling.core.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for OpenCrawling pipeline mode execution.
 */
@ConfigurationProperties(prefix = "opencrawling.pipeline")
public class PipelineProperties {

    /**
     * Active pipeline execution mode (rag or migration). Default is rag.
     */
    private PipelineMode mode = PipelineMode.RAG;

    public PipelineMode getMode() {
        return mode;
    }

    public void setMode(PipelineMode mode) {
        this.mode = mode != null ? mode : PipelineMode.RAG;
    }
}
