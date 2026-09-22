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
package org.opencrawling.luxir;

/**
 * Constants and field definitions for the Luxir Output Connector.
 * In Luxir's dynamic schema, field name suffixes determine types:
 * _s (string), _ss (string array), _t (text), _v (dense vector), _vs (vector array), _b (boolean).
 */
public final class LuxirConstants {

    private LuxirConstants() {}

    public static final String DEFAULT_ENDPOINT = "http://localhost:9400";
    public static final String DEFAULT_COLLECTION = "opencrawling";
    public static final String DEFAULT_VECTOR_FIELD = "embedding_v";
    public static final int DEFAULT_DIMENSIONS = 1024;
    public static final String DEFAULT_SIMILARITY = "cosine";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // Standard Luxir Document Fields
    public static final String FIELD_ID = "id";
    public static final String FIELD_DOC_ID = "doc_id_s";
    public static final String FIELD_TITLE = "title_t";
    public static final String FIELD_TEXT = "text_t";
    public static final String FIELD_EMBEDDING = "embedding_v";
    public static final String FIELD_PASSAGES = "passages_vs";
    public static final String FIELD_ACL = "acl_ss";
    public static final String FIELD_SOURCE = "source_s";
    public static final String FIELD_URI = "uri_s";
    public static final String FIELD_LAST_MODIFIED = "last_modified_s";

    // Zero-Trust Security Fields
    public static final String FIELD_SECURITY_INHERITANCE = "security_inheritance_s";
    public static final String FIELD_SECURITY_ALLOWED_READ = "security_allowed_read_ss";
    public static final String FIELD_SECURITY_DENIED_READ = "security_denied_read_ss";
}
