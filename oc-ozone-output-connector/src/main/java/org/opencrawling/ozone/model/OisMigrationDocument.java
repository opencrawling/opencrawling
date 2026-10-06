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
package org.opencrawling.ozone.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;

/**
 * Open Ingestion Standard (OIS) companion metadata document generated alongside
 * raw migrated binary content in Migration Mode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OisMigrationDocument(
    @JsonProperty("$schema")
    String schema,
    String id,
    String action,
    String uri,
    String lastModified,
    SourceRef source,
    ContentRef contentRef,
    Map<String, List<String>> metadata,
    SecurityConfig security,
    String acl
) {
    public static final String DEFAULT_SCHEMA = "https://opencrawling.org/schemas/v1/ois-document.json";

    public record SourceRef(
        String type,
        String originalUri,
        String originalPath
    ) {}

    /**
     * Locator and integrity information for the migrated binary.
     *
     * @param key            object key of the binary inside the target Ozone bucket
     * @param volume         target Ozone volume
     * @param bucket         target Ozone bucket
     * @param claimCheckUri  Claim Check URI the pristine binary was streamed from
     * @param filename       original file name in the source repository
     * @param mimeType       content MIME type
     * @param contentLength  binary size in bytes
     * @param checksumSha256 hex-encoded SHA-256 digest of the binary (bit-for-bit parity check)
     */
    public record ContentRef(
        String key,
        String volume,
        String bucket,
        String claimCheckUri,
        String filename,
        String mimeType,
        long contentLength,
        String checksumSha256
    ) {}

    public static OisMigrationDocument createTombstone(String id, String uri, String lastModified) {
        return new OisMigrationDocument(
            DEFAULT_SCHEMA,
            id,
            "DELETE",
            uri,
            lastModified,
            null,
            null,
            Map.of(),
            SecurityConfig.createPublic(),
            ""
        );
    }
}
