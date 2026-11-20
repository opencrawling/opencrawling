/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The Platform API's entry point.
 *
 * @param links         collection and resource URLs
 * @param features      the optional features and whether they are enabled on this installation
 * @param documentation where the API is described
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiIndex(String name, String apiVersion, Map<String, String> links, Map<String, Boolean> features,
                       Map<String, String> documentation) {

    /** Whether an optional feature (discovery, dynamicJobs, sidecars, ...) is enabled. */
    public boolean hasFeature(String feature) {
        return features != null && Boolean.TRUE.equals(features.get(feature));
    }
}
