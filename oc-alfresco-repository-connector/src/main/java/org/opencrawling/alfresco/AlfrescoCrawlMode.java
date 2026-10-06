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
package org.opencrawling.alfresco;

/**
 * Execution / crawl modes supported by the Alfresco Content Services Repository Connector.
 */
public enum AlfrescoCrawlMode {
    /**
     * Hierarchical folder tree traversal starting from root or a specific folder path / node ID.
     */
    FOLDER,

    /**
     * Query-driven ingestion using Alfresco Search REST API (AFTS, CMISQL, or Lucene).
     */
    QUERY;

    public static AlfrescoCrawlMode fromString(String value) {
        if (value == null) return FOLDER;
        return switch (value.trim().toLowerCase()) {
            case "query" -> QUERY;
            default -> FOLDER;
        };
    }
}
