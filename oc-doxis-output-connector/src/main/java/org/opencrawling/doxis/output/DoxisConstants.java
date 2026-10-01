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

/**
 * Defaults and metadata keys used by the Doxis 4 (CSB REST API) output connector.
 */
public final class DoxisConstants {

    private DoxisConstants() {
    }

    public static final String DEFAULT_BASE_URL = "http://localhost:8080/restws/publicws/rest/api/v1";
    public static final String DEFAULT_DOCUMENT_TYPE = "BaseDocument";
    public static final String DEFAULT_EXTERNAL_ID_ATTRIBUTE = "ObjectNumber";
    public static final String DEFAULT_TITLE_ATTRIBUTE = "ObjectName";
    public static final String DEFAULT_LOCATOR_METADATA_KEY = "doxisLocator";
    public static final String DEFAULT_CLIENT_ID = "OpenCrawling";
    public static final long DEFAULT_UPLOAD_MAX_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public static final String HASH_ALGORITHM_SHA256 = "SHA-256";
    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /** OIS metadata keys read by the connector when present. */
    public static final String META_MIME_TYPE = "mimeType";
    public static final String META_NAME = "name";
    public static final String META_TITLE = "title";
    public static final String META_SIZE = "sizeInBytes";
    public static final String META_CONTENT_LENGTH = "contentLength";
    public static final String META_SHA256 = "sha256";
    public static final String META_HASH_VALUE = "hashValue";
}
