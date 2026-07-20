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
package org.opencrawling.core.claimcheck;

import java.io.InputStream;
import java.net.URI;

/**
 * Interface defining the Claim Check Pattern storage abstraction.
 * Decouples message broker payload references from binary content storage (local filesystem, Apache Ozone, S3, etc.).
 */
public interface ClaimCheckStore {

    /**
     * Put document content stream into claim check store.
     *
     * @param id Unique identifier or filename for the claim check content
     * @param content Input stream of binary content
     * @param contentLength Length of the content in bytes, or -1 if unknown
     * @param contentType MIME type of the content (e.g. application/pdf), or null
     * @return URI reference to stored claim check
     * @throws Exception if storing fails
     */
    URI put(String id, InputStream content, long contentLength, String contentType) throws Exception;

    /**
     * Convenience put method without explicit contentType.
     */
    default URI put(String id, InputStream content, long contentLength) throws Exception {
        return put(id, content, contentLength, null);
    }

    /**
     * Retrieve input stream of content associated with given claim check URI.
     *
     * @param claimCheckUri URI reference returned by put() or received in IngestionMessage
     * @return InputStream of binary content
     * @throws Exception if retrieving fails
     */
    InputStream get(URI claimCheckUri) throws Exception;

    /**
     * Delete claim check content associated with given URI.
     *
     * @param claimCheckUri URI reference of claim check to delete
     * @throws Exception if deleting fails
     */
    void delete(URI claimCheckUri) throws Exception;

    /**
     * Check if given URI is supported by this store implementation.
     *
     * @param claimCheckUri URI to check
     * @return true if supported, false otherwise
     */
    boolean supports(URI claimCheckUri);
}
