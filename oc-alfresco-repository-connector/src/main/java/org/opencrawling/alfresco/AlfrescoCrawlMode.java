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

import java.util.Locale;

/**
 * Execution / crawl modes supported by the Alfresco Content Services Repository Connector.
 */
public enum AlfrescoCrawlMode {
    /**
     * Hierarchical folder tree traversal starting from root or a specific folder path / node ID / site.
     * Supports incremental skip-unchanged and orphan reconciliation when {@code incremental-enabled=true}.
     */
    FOLDER,

    /**
     * Query-driven ingestion using the Alfresco Search REST API (AFTS, CMISQL, or Lucene).
     */
    QUERY,

    /**
     * Incremental Search API delta crawling anchored to {@code cm:modified:[<watermark> TO MAX]}, paired with
     * Trashcan / Audit deletion polling and optional periodic full scans for ACL-only changes and hard deletes.
     */
    SEARCH_DELTA;

    public static AlfrescoCrawlMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return FOLDER;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "query" -> QUERY;
            case "search_delta", "delta", "incremental" -> SEARCH_DELTA;
            default -> FOLDER;
        };
    }
}
