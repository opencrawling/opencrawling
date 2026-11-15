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
package org.opencrawling.sdk.auth;

/**
 * Supplies the bearer token the client sends with each request.
 *
 * <p>Implementations must be thread-safe: one client may be used from many threads.
 */
public interface TokenProvider {

    /** The access token to send now, or {@code null} to send none. */
    String accessToken();

    /**
     * Called when the server refused the token ({@code 401}). A provider that can get a new token drops the cached
     * one and returns {@code true}, and the client repeats the request once; a fixed token returns {@code false}.
     */
    default boolean invalidate() {
        return false;
    }

    /** A provider that always returns the same token. */
    static TokenProvider of(String token) {
        String value = token == null || token.isBlank() ? null : token.trim();
        return () -> value;
    }
}
