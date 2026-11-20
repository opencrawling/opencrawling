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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where dynamic jobs are kept (#316): their record (who asked, what, its state, a pending request waiting for approval)
 * and the idempotency keys that started them. The run itself is kept by the run store, like every job run.
 *
 * <p>{@link RunDynamicJobStore} keeps each job as one run in the run registry's store, from its request to its end:
 * in this runtime's memory with the {@code file} control-plane store, in the control-plane database with {@code jdbc},
 * where every runtime on it sees the same jobs, after a restart too. A change that depends on what is stored (count,
 * check, then save) runs inside {@link #locked}.
 */
interface DynamicJobStore {

    /** A key's earlier request: the job it started and the checked request, to tell a retry from another request. */
    record Keyed(String jobId, Object request) {}

    /** Runs {@code work} with no other change of dynamic jobs in between, in this runtime or another sharing the store. */
    <T> T locked(Supplier<T> work);

    Optional<Entry> get(String jobId);

    /** Stores a job, replacing the one with its id. */
    void save(Entry job);

    /** Forgets a job and the keys that started it. */
    void delete(String jobId);

    /** The jobs of {@code owner}, or every job when {@code owner} is {@code null}, newest first. */
    List<Entry> list(String owner);

    /** How many of {@code owner}'s jobs are in one of {@code states}. */
    int count(String owner, State... states);

    /** The jobs waiting for approval that were filed before {@code cutoff}, and not yet being approved. */
    List<Entry> unansweredBefore(Instant cutoff);

    /**
     * Forgets the jobs that ended before {@code cutoff}, then the oldest ended jobs beyond {@code maxEnded}, with the
     * keys that started them.
     */
    void evict(Instant cutoff, int maxEnded);

    Optional<Keyed> key(String owner, String key);

    void saveKey(String owner, String key, Keyed keyed);

    void deleteKey(String owner, String key);

    /** For tests: the ids of the jobs held, oldest first. */
    List<String> ids();
}
