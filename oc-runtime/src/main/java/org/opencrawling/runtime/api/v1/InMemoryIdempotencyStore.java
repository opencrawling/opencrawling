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
import java.util.Map;

/**
 * Keeps idempotency keys in this runtime's memory: the default. Keys are lost when the runtime restarts and are not
 * shared between runtime instances; use {@link RedisIdempotencyStore} for that.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

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

    public InMemoryIdempotencyStore(Duration retention, int maxEntries, Clock clock) {
        this.retention = retention;
        this.maxEntries = maxEntries;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public InMemoryIdempotencyStore() {
        this(Duration.ofHours(24), 10_000, null);
    }

    @Override
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

    @Override
    public synchronized void complete(String scope, String key, String fingerprint, StoredResponse response) {
        Entry entry = entries.get(scope + "\n" + key);
        if (entry != null) {
            entry.response = response;
        }
    }

    @Override
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
