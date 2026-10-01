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
package org.opencrawling.cmis;

/**
 * Versioning ingestion policy for CMIS documents:
 * - LATEST_MAJOR: Only ingest the latest major version (e.g., 1.0, 2.0).
 * - LATEST: Ingest the latest version (including minor versions like 1.1, 2.3).
 * - ALL: Ingest all historical versions.
 */
public enum CmisVersionsMode {
    LATEST_MAJOR,
    LATEST,
    ALL;

    public static CmisVersionsMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return LATEST_MAJOR;
        }
        for (CmisVersionsMode v : values()) {
            if (v.name().equalsIgnoreCase(value.trim())) {
                return v;
            }
        }
        return LATEST_MAJOR;
    }
}
