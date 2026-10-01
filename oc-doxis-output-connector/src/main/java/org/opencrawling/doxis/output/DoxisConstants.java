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
package org.opencrawling.doxis.output;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defaults and Dataset v3 column slugs used by the Doxis AI.dp output connector.
 */
public final class DoxisConstants {

    private DoxisConstants() {
    }

    public static final String DEFAULT_BASE_URL = "https://dochorizon.klippa.com";
    public static final String DEFAULT_DATASET_NAME = "OpenCrawling Ingestion";
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public static final String API_KEY_HEADER = "x-api-key";
    public static final String API_PREFIX = "/api/services";
    public static final String DATASETS_PATH = API_PREFIX + "/datasets/v3/datasets";
    public static final String AUTH_INFO_PATH = API_PREFIX + "/auth/v1/info";

    // Column data types (Dataset API v3)
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_INT = "int";
    public static final String TYPE_BOOL = "bool";
    public static final String TYPE_TIMESTAMP = "timestamp";
    public static final String TYPE_DOCUMENT = "document";

    // Record types stored in the record_type column
    public static final String RECORD_TYPE_DOCUMENT = "document";
    public static final String RECORD_TYPE_CHUNK = "chunk";

    // Column slugs
    public static final String COL_EXTERNAL_ID = "external_id";
    public static final String COL_DOCUMENT_ID = "document_id";
    public static final String COL_RECORD_TYPE = "record_type";
    public static final String COL_URI = "uri";
    public static final String COL_TITLE = "title";
    public static final String COL_SOURCE_SYSTEM = "source_system";
    public static final String COL_MIME_TYPE = "mime_type";
    public static final String COL_CONTENT_LENGTH = "content_length";
    public static final String COL_LAST_MODIFIED = "last_modified";
    public static final String COL_INGESTED_AT = "ingested_at";
    public static final String COL_ACL = "acl";
    public static final String COL_SECURITY_INHERITANCE = "security_inheritance";
    public static final String COL_SECURITY_ALLOWED_READ = "security_allowed_read";
    public static final String COL_SECURITY_DENIED_READ = "security_denied_read";
    public static final String COL_SECURITY_JSON = "security_json";
    public static final String COL_METADATA_JSON = "metadata_json";
    public static final String COL_CHUNK_TEXT = "chunk_text";
    public static final String COL_DOCUMENT = "document";

    /**
     * The columns the connector provisions on the target dataset, keyed by slug, valued by data type.
     */
    public static final Map<String, String> COLUMNS;

    static {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put(COL_EXTERNAL_ID, TYPE_TEXT);
        columns.put(COL_DOCUMENT_ID, TYPE_TEXT);
        columns.put(COL_RECORD_TYPE, TYPE_TEXT);
        columns.put(COL_URI, TYPE_TEXT);
        columns.put(COL_TITLE, TYPE_TEXT);
        columns.put(COL_SOURCE_SYSTEM, TYPE_TEXT);
        columns.put(COL_MIME_TYPE, TYPE_TEXT);
        columns.put(COL_CONTENT_LENGTH, TYPE_INT);
        columns.put(COL_LAST_MODIFIED, TYPE_TIMESTAMP);
        columns.put(COL_INGESTED_AT, TYPE_TIMESTAMP);
        columns.put(COL_ACL, TYPE_TEXT);
        columns.put(COL_SECURITY_INHERITANCE, TYPE_BOOL);
        columns.put(COL_SECURITY_ALLOWED_READ, TYPE_TEXT);
        columns.put(COL_SECURITY_DENIED_READ, TYPE_TEXT);
        columns.put(COL_SECURITY_JSON, TYPE_TEXT);
        columns.put(COL_METADATA_JSON, TYPE_TEXT);
        columns.put(COL_CHUNK_TEXT, TYPE_TEXT);
        columns.put(COL_DOCUMENT, TYPE_DOCUMENT);
        COLUMNS = Collections.unmodifiableMap(columns);
    }
}
