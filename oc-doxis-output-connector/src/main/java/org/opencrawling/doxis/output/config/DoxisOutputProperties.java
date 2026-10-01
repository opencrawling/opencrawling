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
package org.opencrawling.doxis.output.config;

import org.opencrawling.doxis.output.DoxisConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.opencrawling.output.doxis")
public record DoxisOutputProperties(
    @DefaultValue(DoxisConstants.DEFAULT_BASE_URL) String baseUrl,
    String apiKey,
    String datasetId,
    @DefaultValue(DoxisConstants.DEFAULT_DATASET_NAME) String datasetName,
    @DefaultValue("true") boolean autoCreateDataset,
    @DefaultValue("true") boolean uploadContent,
    @DefaultValue("true") boolean includeSourceMetadata,
    @DefaultValue("true") boolean applySecurityAcls,
    @DefaultValue("REPLACE") ConflictResolution conflictResolution,
    @DefaultValue("3") int maxRetries,
    @DefaultValue("60") int timeoutSeconds
) {

    /**
     * How an UPSERT is handled when a row with the same external id already exists.
     * Document cells are write-once in the Dataset API, so a content change always needs a new row.
     */
    public enum ConflictResolution {
        /** Insert a fresh row (new content + metadata), then delete the previous rows. */
        REPLACE,
        /** Keep the archived content and only overwrite the metadata and security cells. */
        UPDATE_METADATA
    }

    public DoxisOutputProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DoxisConstants.DEFAULT_BASE_URL;
        if (datasetName == null || datasetName.isBlank()) datasetName = DoxisConstants.DEFAULT_DATASET_NAME;
        if (conflictResolution == null) conflictResolution = ConflictResolution.REPLACE;
        if (maxRetries < 0) maxRetries = DoxisConstants.DEFAULT_MAX_RETRIES;
        if (timeoutSeconds <= 0) timeoutSeconds = DoxisConstants.DEFAULT_TIMEOUT_SECONDS;
    }

    public static DoxisOutputProperties defaults() {
        return new DoxisOutputProperties(null, null, null, null, true, true, true, true,
                ConflictResolution.REPLACE, DoxisConstants.DEFAULT_MAX_RETRIES, DoxisConstants.DEFAULT_TIMEOUT_SECONDS);
    }
}
