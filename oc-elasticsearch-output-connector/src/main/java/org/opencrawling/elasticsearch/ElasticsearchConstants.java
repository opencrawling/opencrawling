/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.elasticsearch;

import org.opencrawling.core.security.ReadKeys;

import java.util.Set;

/**
 * Field names of the chunk documents the Elasticsearch output writes. They match the OpenSearch outputs so the MCP
 * backends and the metadata ACL evaluator read every store the same way.
 */
public final class ElasticsearchConstants {

    private ElasticsearchConstants() {
        // Prevent instantiation
    }

    public static final String OUTPUT_TYPE = "elasticsearch";

    public static final String FIELD_DOCUMENT_ID = "documentId";
    public static final String FIELD_ID = "id";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_URI = "uri";
    public static final String FIELD_ACL = "acl";
    public static final String FIELD_LAST_MODIFIED = "lastModified";
    public static final String FIELD_SECURITY_INHERITANCE = "security_inheritance";
    public static final String FIELD_SECURITY_ALLOWED_READ = "security_allowed_read";
    public static final String FIELD_SECURITY_DENIED_READ = "security_denied_read";
    /** Normalized read keys for exact-match ACL pre-filtering, see {@link ReadKeys}. */
    public static final String FIELD_SECURITY_READ_KEYS = ReadKeys.FIELD;
    public static final String FIELD_EMBEDDINGS = "embeddings";

    /** Raw metadata key carrying the structured {@code SecurityConfig}; flattened into the fields above. */
    public static final String METADATA_SECURITY = "security";

    /** Fields the mapper owns; free-form document metadata never overwrites them. */
    public static final Set<String> RESERVED_FIELDS = Set.of(
            FIELD_DOCUMENT_ID, FIELD_ID, FIELD_TEXT, FIELD_URI, FIELD_ACL, FIELD_LAST_MODIFIED,
            FIELD_SECURITY_INHERITANCE, FIELD_SECURITY_ALLOWED_READ, FIELD_SECURITY_DENIED_READ,
            FIELD_SECURITY_READ_KEYS, FIELD_EMBEDDINGS, METADATA_SECURITY);
}
