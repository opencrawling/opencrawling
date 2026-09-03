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
package org.opencrawling.solr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.opencrawling.output.solr")
public record SolrOutputProperties(
    String mode,
    String url,
    String zkHost,
    String collection,
    int dimensions,
    String similarity,
    String vectorEncoding,
    String quantization,
    int hnswMaxConnections,
    int hnswBeamWidth,
    int efSearch,
    int commitWithinMs,
    String username,
    String password
) {
    public SolrOutputProperties {
        if (mode == null || mode.isBlank()) mode = "standalone";
        if (url == null || url.isBlank()) url = "http://localhost:8983/solr";
        if (collection == null || collection.isBlank()) collection = "enterprise_kb";
        if (dimensions <= 0) dimensions = 1024;
        if (similarity == null || similarity.isBlank()) similarity = "cosine";
        if (vectorEncoding == null || vectorEncoding.isBlank()) vectorEncoding = "FLOAT32";
        if (quantization == null || quantization.isBlank()) quantization = "none";
        if (hnswMaxConnections <= 0) hnswMaxConnections = 16;
        if (hnswBeamWidth <= 0) hnswBeamWidth = 100;
        if (efSearch <= 0) efSearch = 100;
        if (commitWithinMs <= 0) commitWithinMs = 1000;
    }
}
