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
package org.opencrawling.sdk.v1;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When the Platform API client repeats a request: after a network error, a {@code 502}, {@code 503}, {@code 504} or
 * {@code 429}, or a {@code 409 idempotency-in-progress} (its first attempt is still running). It only repeats requests that are safe to repeat: {@code GET}, {@code PUT} and {@code DELETE}, and
 * {@code POST}s that carry an {@code Idempotency-Key}; the client gives every {@code POST} one unless the caller did.
 * Waits grow exponentially, with jitter, and follow the runtime's {@code Retry-After}; when that asks for longer than
 * {@code maxDelay}, the client gives up instead of retrying early.
 *
 * <p>A conditional change ({@code If-Match}) whose answer was lost may come back from its repeat as {@code 412}: the
 * first attempt succeeded and changed the version. Read the resource to see.
 *
 * @param maxAttempts how many times a request is sent at most, the first time included (1 means no retries)
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        baseDelay = baseDelay != null ? baseDelay : Duration.ofMillis(200);
        maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(10);
    }

    /** Three attempts, from 200 ms, at most 10 s apart. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(10));
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    /**
     * How long to wait before attempt {@code attempt + 1}: the runtime's Retry-After if it sent one, else backoff; null
     * when the runtime asks for a longer wait than {@code maxDelay}, so the caller gives up rather than retry too early.
     */
    Duration delay(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                Duration asked = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                return asked.compareTo(maxDelay) > 0 ? null : asked;
            } catch (NumberFormatException ignored) {
                // an HTTP date: fall back to backoff
            }
        }
        long exponential = baseDelay.toMillis() * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exponential, maxDelay.toMillis());
        return Duration.ofMillis(capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1));
    }
}
