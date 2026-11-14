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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers the answers to {@code POST} requests sent with an {@code Idempotency-Key}, per caller, for a limited
 * time, in this runtime's memory. Only successful answers are kept, so a failed request can be retried with the same
 * key.
 */
public class IdempotencyStore {

    /** A remembered answer. */
    public record StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {}

    public sealed interface Begin {
        /** First use of the key: go ahead, then {@link #complete} or {@link #abandon}. */
        record Proceed() implements Begin {}
        /** Done before with the same request: send this answer again. */
        record Replay(StoredResponse response) implements Begin {}
        /** A request with this key is still running. */
        record InProgress() implements Begin {}
        /** The key was used for a different request. */
        record Mismatch() implements Begin {}
    }

    private static final class Entry {
        final String fingerprint;
        final Instant createdAt;
        StoredResponse response;

        Entry(String fingerprint, Instant createdAt) {
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Duration retention;
    private final int maxEntries;
    private final Clock clock;

    public IdempotencyStore(Duration retention, int maxEntries, Clock clock) {
        this.retention = retention;
        this.maxEntries = maxEntries;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public IdempotencyStore() {
        this(Duration.ofHours(24), 10_000, null);
    }

    /**
     * @param scope       who sent it and where: the caller, method and path
     * @param fingerprint a digest of the request body
     */
    public synchronized Begin begin(String scope, String key, String fingerprint) {
        evictExpired();
        String id = scope + "\n" + key;
        Entry entry = entries.get(id);
        if (entry == null) {
            if (entries.size() >= maxEntries) {
                Iterator<String> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            entries.put(id, new Entry(fingerprint, clock.instant()));
            return new Begin.Proceed();
        }
        if (!entry.fingerprint.equals(fingerprint)) {
            return new Begin.Mismatch();
        }
        return entry.response == null ? new Begin.InProgress() : new Begin.Replay(entry.response);
    }

    public synchronized void complete(String scope, String key, StoredResponse response) {
        Entry entry = entries.get(scope + "\n" + key);
        if (entry != null) {
            entry.response = response;
        }
    }

    public synchronized void abandon(String scope, String key) {
        entries.remove(scope + "\n" + key);
    }

    synchronized int size() {
        return entries.size();
    }

    private void evictExpired() {
        Instant cutoff = clock.instant().minus(retention);
        entries.values().removeIf(entry -> entry.createdAt.isBefore(cutoff));
    }
}
