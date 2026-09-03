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
package org.opencrawling.core.document;

import java.io.InputStream;
import java.util.Map;
import java.util.List;
import java.time.Instant;
import org.opencrawling.core.security.SecurityConfig;

public record RepositoryDocument(
    String id,
    String uri,
    InputStream contentStream,
    Map<String, List<String>> metadata,
    String acl,
    SecurityConfig security,
    Instant lastModified,
    DocumentAction action
) {
    public RepositoryDocument(
        String id,
        String uri,
        InputStream contentStream,
        Map<String, List<String>> metadata,
        String acl,
        SecurityConfig security,
        Instant lastModified
    ) {
        this(id, uri, contentStream, metadata, acl, security, lastModified, DocumentAction.UPSERT);
    }

    public RepositoryDocument(
        String id,
        String uri,
        InputStream contentStream,
        Map<String, List<String>> metadata,
        String acl,
        Instant lastModified
    ) {
        this(id, uri, contentStream, metadata, acl, SecurityConfig.createPublic(), lastModified, DocumentAction.UPSERT);
    }

    public static RepositoryDocument createTombstone(String id, String uri) {
        return new RepositoryDocument(id, uri, null, Map.of(), "", SecurityConfig.createPublic(), Instant.now(), DocumentAction.DELETE);
    }
}
