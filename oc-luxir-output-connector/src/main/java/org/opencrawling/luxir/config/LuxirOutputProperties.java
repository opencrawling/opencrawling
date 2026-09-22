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
package org.opencrawling.luxir.config;

import org.opencrawling.luxir.LuxirConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.opencrawling.output.luxir")
public record LuxirOutputProperties(
    @DefaultValue(LuxirConstants.DEFAULT_ENDPOINT) String endpoint,
    @DefaultValue(LuxirConstants.DEFAULT_COLLECTION) String collection,
    @DefaultValue(LuxirConstants.DEFAULT_VECTOR_FIELD) String vectorField,
    @DefaultValue("1024") int dimensions,
    @DefaultValue(LuxirConstants.DEFAULT_SIMILARITY) String similarity,
    @DefaultValue("true") boolean autoCommit,
    @DefaultValue("0") int commitWithinMs,
    @DefaultValue("30") int timeoutSeconds
) {
    public LuxirOutputProperties {
        if (endpoint == null || endpoint.isBlank()) endpoint = LuxirConstants.DEFAULT_ENDPOINT;
        if (collection == null || collection.isBlank()) collection = LuxirConstants.DEFAULT_COLLECTION;
        if (vectorField == null || vectorField.isBlank()) vectorField = LuxirConstants.DEFAULT_VECTOR_FIELD;
        if (dimensions <= 0) dimensions = LuxirConstants.DEFAULT_DIMENSIONS;
        if (similarity == null || similarity.isBlank()) similarity = LuxirConstants.DEFAULT_SIMILARITY;
        if (timeoutSeconds <= 0) timeoutSeconds = LuxirConstants.DEFAULT_TIMEOUT_SECONDS;
    }
}
