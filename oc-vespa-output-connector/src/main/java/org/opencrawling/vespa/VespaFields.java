/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.vespa;

public final class VespaFields {

    private VespaFields() {
        // Prevent instantiation
    }

    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_URI = "uri";
    /** The source document's id, queryable, so a deletion finds every chunk of its document. */
    public static final String FIELD_DOCUMENT_ID = "document_id";
    public static final String FIELD_ACL = "acl";
    public static final String FIELD_LAST_MODIFIED = "lastModified";
    public static final String FIELD_EMBEDDING = "embedding";
    public static final String FIELD_METADATA_JSON = "metadata_json";
    public static final String FIELD_CHUNK_INDEX = "chunk_index";
    public static final String FIELD_TOTAL_CHUNKS = "total_chunks";
    public static final String FIELD_SECURITY_INHERITANCE = "security_inheritance";
    public static final String FIELD_SECURITY_ALLOWED_READ = "security_allowed_read";
    public static final String FIELD_SECURITY_DENIED_READ = "security_denied_read";

    // Dimension-specific document types, deployed alongside the configurable default so a single
    // running instance can feed multiple embedding models without redeploying the schema.
    public static final String DOCUMENT_TYPE_384 = "opencrawling_chunk_384";
    public static final String DOCUMENT_TYPE_768 = "opencrawling_chunk_768";
    public static final String DOCUMENT_TYPE_1024 = "opencrawling_chunk_1024";

    // Query-time tensor input names for nearestNeighbor searches, one per document type. Vespa
    // requires a query input with a given name to declare the same tensor type across every schema
    // in a content cluster, so each dimension needs its own name rather than sharing "q_embedding".
    public static final String QUERY_INPUT_384 = "q_embedding_384";
    public static final String QUERY_INPUT_768 = "q_embedding_768";
    public static final String QUERY_INPUT_1024 = "q_embedding_1024";
    public static final String QUERY_INPUT_DEFAULT = "q_embedding_default";
}
