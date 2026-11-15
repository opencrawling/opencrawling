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
package org.opencrawling.runtime.api.v1;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Remembers the answers to {@code POST} requests sent with an {@code Idempotency-Key}, per caller, for a limited
 * time. Only successful answers are kept, so a failed request can be retried with the same key.
 *
 * <p>Two implementations: {@link InMemoryIdempotencyStore} (the default, one runtime) and
 * {@link RedisIdempotencyStore} (shared by every runtime instance and kept across restarts), chosen with
 * {@code opencrawling.api.idempotency.store}.
 */
public interface IdempotencyStore {

    /** A remembered answer. */
    record StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StoredResponse that && status == that.status && headers.equals(that.headers)
                    && Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * status + headers.hashCode()) + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "StoredResponse[status=" + status + ", headers=" + headers + ", body=" + body.length + " bytes]";
        }
    }

    sealed interface Begin {
        /** First use of the key: go ahead, then {@link #complete} or {@link #abandon}. */
        record Proceed() implements Begin {}
        /** Done before with the same request: send this answer again. */
        record Replay(StoredResponse response) implements Begin {}
        /** A request with this key is still running. */
        record InProgress() implements Begin {}
        /** The key was used for a different request. */
        record Mismatch() implements Begin {}
    }

    /** The store cannot be reached, so the request must not run: it could not be told apart from a retry. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Records the first use of a key, or tells how an earlier use went.
     *
     * @param scope       who sent it and where: the caller, method and path
     * @param fingerprint a digest of the request body
     * @throws UnavailableException when the store cannot be reached
     */
    Begin begin(String scope, String key, String fingerprint);

    /**
     * Remembers the successful answer to the request that {@link #begin} let proceed.
     *
     * @param fingerprint the same digest of the request body that was given to {@link #begin}
     */
    void complete(String scope, String key, String fingerprint, StoredResponse response);

    /** Forgets the key of a request that failed, so it can be retried. */
    void abandon(String scope, String key);
}
