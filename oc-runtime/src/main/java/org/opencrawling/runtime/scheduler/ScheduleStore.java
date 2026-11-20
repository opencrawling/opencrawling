/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence contract for native job scheduling runtime state and dispatch audit logs (#197).
 *
 * <p>{@link FileScheduleStore} persists JSON state alongside {@code data/} for single-node runtimes;
 * {@code JdbcScheduleStore} stores rows in {@code oc_schedule_state} and {@code oc_schedule_dispatch}
 * (Flyway V5) so multiple runtimes share schedules over the control-plane database (#315).
 */
public interface ScheduleStore {

    /** Whether multiple runtime instances share this store. */
    default boolean shared() {
        return false;
    }

    /** Current state for one job, if any has been initialized. */
    Optional<JobScheduleState> getState(String jobId);

    /** All stored schedule states keyed by {@code jobId}. */
    Map<String, JobScheduleState> getAllStates();

    /** Inserts or updates {@code state} by {@code jobId}. */
    void saveState(JobScheduleState state);

    /** Removes the schedule state for {@code jobId}. */
    void deleteState(String jobId);

    /** Due schedules ({@code enabled == true && nextFireAt <= now}), earliest first, capped to {@code limit}. */
    List<JobScheduleState> findDue(Instant now, int limit);

    /** Appends an immutable dispatch audit record. */
    void recordDispatch(ScheduleDispatchRecord dispatch);

    /** Dispatch records for {@code jobId}, newest first. */
    List<ScheduleDispatchRecord> listDispatches(String jobId);

    /** Count dispatches across all jobs (or matching {@code outcomes}) since {@code since}. */
    int countDispatchesSince(Instant since, List<DispatchOutcome> outcomes);

    /** Deletes dispatch records older than {@code cutoff}. */
    int deleteDispatchesBefore(Instant cutoff);
}
