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
 * Scanning modes for CMIS repository crawling:
 * - FOLDER: Hierarchical folder traversal starting from a root folder path/id.
 * - QUERY: Relational search via CMISQL (CMIS Query Language).
 */
public enum CmisCrawlMode {
    FOLDER,
    QUERY;

    public static CmisCrawlMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return FOLDER;
        }
        for (CmisCrawlMode m : values()) {
            if (m.name().equalsIgnoreCase(value.trim())) {
                return m;
            }
        }
        return FOLDER;
    }
}
