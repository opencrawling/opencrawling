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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps idempotency keys in this runtime's memory: the default. Keys are lost when the runtime restarts and are not
 * shared between runtime instances; use {@link RedisIdempotencyStore} for that.
 *
 * <p>Bounded, without ever dropping a key whose request is still running (a retry would then run it again): expired
 * keys go first, then the oldest finished key. Each caller may hold at most {@code maxEntriesPerCaller} keys, so no
 * caller can push out other callers' keys. When only running requests are left to drop, a new key is refused
 * ({@link UnavailableException}, so {@code 503}).
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final class Entry {
        final String caller;
        final String fingerprint;
        final Instant createdAt;
        StoredResponse response;
        Integer statusWithoutAnswer;

        Entry(String caller, String fingerprint, Instant createdAt) {
            this.caller = caller;
            this.fingerprint = fingerprint;
            this.createdAt = createdAt;
        }

        boolean finished() {
            return response != null || statusWithoutAnswer != null;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Integer> perCaller = new HashMap<>();
    private final Duration retention;
    private final int maxEntries;
    private final int maxEntriesPerCaller;
    private final Clock clock;

    public InMemoryIdempotencyStore(Duration retention, int maxEntries, int maxEntriesPerCaller, Clock clock) {
        this.retention = retention;
        this.maxEntries = maxEntries;
        this.maxEntriesPerCaller = Math.max(1, Math.min(maxEntriesPerCaller, maxEntries));
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public InMemoryIdempotencyStore(Duration retention, int maxEntries, Clock clock) {
        this(retention, maxEntries, maxEntries, clock);
    }

    public InMemoryIdempotencyStore() {
        this(Duration.ofHours(24), 10_000, 1_000, null);
    }

    @Override
    public synchronized Begin begin(String scope, String key, String fingerprint) {
        evictExpired();
        String id = scope + "\n" + key;
        Entry entry = entries.get(id);
        if (entry == null) {
            String caller = callerOf(scope);
            if (perCaller.getOrDefault(caller, 0) >= maxEntriesPerCaller && !evictOldestFinished(caller)) {
                throw new UnavailableException("This caller has " + maxEntriesPerCaller
                        + " requests with an Idempotency-Key still running", null);
            }
            if (entries.size() >= maxEntries && !evictOldestFinished(null)) {
                throw new UnavailableException(maxEntries + " requests with an Idempotency-Key are still running", null);
            }
            entries.put(id, new Entry(caller, fingerprint, clock.instant()));
            perCaller.merge(caller, 1, Integer::sum);
            return new Begin.Proceed();
        }
        if (!entry.fingerprint.equals(fingerprint)) {
            return new Begin.Mismatch();
        }
        if (entry.response != null) {
            return new Begin.Replay(entry.response);
        }
        return entry.statusWithoutAnswer != null ? new Begin.AnswerNotKept(entry.statusWithoutAnswer) : new Begin.InProgress();
    }

    @Override
    public synchronized void complete(String scope, String key, String fingerprint, StoredResponse response) {
        Entry entry = entries.get(scope + "\n" + key);
        if (entry != null) {
            entry.response = response;
        }
    }

    @Override
    public synchronized void completeWithoutAnswer(String scope, String key, String fingerprint, int status) {
        Entry entry = entries.get(scope + "\n" + key);
        if (entry != null) {
            entry.statusWithoutAnswer = status;
        }
    }

    @Override
    public synchronized void abandon(String scope, String key) {
        remove(scope + "\n" + key);
    }

    synchronized int size() {
        return entries.size();
    }

    /** The caller part of a scope ({@code "<caller> POST <path>"}; a path has no spaces). */
    static String callerOf(String scope) {
        int method = scope.lastIndexOf(" POST ");
        return method >= 0 ? scope.substring(0, method) : scope;
    }

    private void evictExpired() {
        Instant cutoff = clock.instant().minus(retention);
        List<String> expired = new ArrayList<>();
        entries.forEach((id, entry) -> {
            if (entry.createdAt.isBefore(cutoff)) {
                expired.add(id);
            }
        });
        expired.forEach(this::remove);
    }

    /** Drops the oldest finished key (of this caller, or of anyone); false when every candidate is still running. */
    private boolean evictOldestFinished(String caller) {
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            Entry entry = candidate.getValue();
            if (entry.finished() && (caller == null || caller.equals(entry.caller))) {
                remove(candidate.getKey());
                return true;
            }
        }
        return false;
    }

    private void remove(String id) {
        Entry removed = entries.remove(id);
        if (removed != null) {
            perCaller.computeIfPresent(removed.caller, (caller, count) -> count > 1 ? count - 1 : null);
        }
    }
}
