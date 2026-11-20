/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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
import java.util.function.Predicate;

/**
 * Strategy interface abstraction for Apache Ozone client backends (S3 Gateway vs Native RPC client).
 */
public interface OzoneClientStrategy {

    /**
     * Store content into Apache Ozone.
     *
     * @param id Object key/identifier
     * @param content Input stream of object content
     * @param contentLength Length of content in bytes (-1 if unknown)
     * @param contentType MIME type of content or null
     * @return Stored object URI
     * @throws Exception if storing fails
     */
    URI put(String id, InputStream content, long contentLength, String contentType) throws Exception;

    /**
     * Read content from Apache Ozone by URI.
     *
     * @param claimCheckUri Stored object URI
     * @return Input stream of object content
     * @throws Exception if retrieval fails
     */
    InputStream get(URI claimCheckUri) throws Exception;

    /**
     * Delete content from Apache Ozone by URI.
     *
     * @param claimCheckUri Stored object URI
     * @throws Exception if deletion fails
     */
    void delete(URI claimCheckUri) throws Exception;

    /**
     * Delete expired objects older than given maxAge, keeping none.
     *
     * @param maxAge Maximum age duration
     * @return Number of deleted objects
     * @throws Exception if sweep fails
     */
    default int deleteExpired(java.time.Duration maxAge) throws Exception {
        return deleteExpired(maxAge, uri -> false);
    }

    /**
     * Delete expired objects older than given maxAge, except those {@code retain} keeps, such as the claims of
     * dead-lettered messages. Every strategy must implement it, giving {@code retain} each object's URI exactly as
     * {@link #put} returned it: a sweep that ignored {@code retain} would delete claims a replay still needs.
     *
     * @param maxAge Maximum age duration
     * @param retain Objects to keep whatever their age
     * @return Number of deleted objects
     * @throws Exception if sweep fails
     */
    int deleteExpired(java.time.Duration maxAge, Predicate<URI> retain) throws Exception;

    /**
     * Check if given URI is supported by this client strategy.
     *
     * @param claimCheckUri URI to check
     * @return true if supported, false otherwise
     */
    boolean supports(URI claimCheckUri);
}
