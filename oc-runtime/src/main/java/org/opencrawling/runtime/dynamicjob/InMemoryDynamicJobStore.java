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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.runtime.dynamicjob.DynamicJobService.Entry;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Dynamic jobs in this runtime's memory: lost on restart, and seen by this runtime only. */
final class InMemoryDynamicJobStore implements DynamicJobStore {

    /** In the order the jobs were created. */
    private final Map<String, Entry> jobs = new LinkedHashMap<>();
    /** Owner and idempotency key to the job it started. */
    private final Map<String, Keyed> keys = new HashMap<>();

    @Override
    public synchronized <T> T locked(Supplier<T> work) {
        return work.get();
    }

    @Override
    public synchronized Optional<Entry> get(String jobId) {
        return Optional.ofNullable(jobId == null ? null : jobs.get(jobId));
    }

    @Override
    public synchronized void save(Entry job) {
        jobs.put(job.jobId(), job);
    }

    @Override
    public synchronized void delete(String jobId) {
        jobs.remove(jobId);
        keys.values().removeIf(keyed -> keyed.jobId().equals(jobId));
    }

    @Override
    public synchronized List<Entry> list(String owner) {
        return jobs.values().stream().filter(job -> owner == null || job.owner().equals(owner))
                .sorted(Comparator.comparing(Entry::createdAt).reversed()).toList();
    }

    @Override
    public synchronized int count(String owner, State... states) {
        List<State> wanted = Arrays.asList(states);
        return (int) jobs.values().stream().filter(job -> job.owner().equals(owner) && wanted.contains(job.state())).count();
    }

    @Override
    public synchronized List<Entry> unansweredBefore(Instant cutoff) {
        return jobs.values().stream().filter(job -> job.state() == State.PENDING_APPROVAL && job.approvedBy() == null
                && job.createdAt().isBefore(cutoff)).toList();
    }

    @Override
    public synchronized void evict(Instant cutoff, int maxEnded) {
        jobs.values().removeIf(job -> job.finishedAt() != null && job.finishedAt().isBefore(cutoff));
        long ended = jobs.values().stream().filter(job -> job.finishedAt() != null).count();
        Iterator<Entry> oldestFirst = jobs.values().iterator();
        while (ended > maxEnded && oldestFirst.hasNext()) {
            if (oldestFirst.next().finishedAt() != null) {
                oldestFirst.remove();
                ended--;
            }
        }
        keys.values().removeIf(keyed -> !jobs.containsKey(keyed.jobId()));
    }

    @Override
    public synchronized Optional<Keyed> key(String owner, String key) {
        return Optional.ofNullable(keys.get(owner + "\n" + key));
    }

    @Override
    public synchronized void saveKey(String owner, String key, Keyed keyed) {
        keys.put(owner + "\n" + key, keyed);
    }

    @Override
    public synchronized void deleteKey(String owner, String key) {
        keys.remove(owner + "\n" + key);
    }

    @Override
    public synchronized List<String> ids() {
        return new ArrayList<>(jobs.keySet());
    }
}
