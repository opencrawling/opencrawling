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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link ScheduleStore} used when no control-plane store or file directory is configured
 * (for example, standalone unit tests).
 */
public final class InMemoryScheduleStore implements ScheduleStore {

    private static final int MAX_DISPATCHES = 5000;

    private final Map<String, JobScheduleState> states = new ConcurrentHashMap<>();
    private final List<ScheduleDispatchRecord> dispatches = new CopyOnWriteArrayList<>();

    @Override
    public Optional<JobScheduleState> getState(String jobId) {
        return jobId == null ? Optional.empty() : Optional.ofNullable(states.get(jobId));
    }

    @Override
    public Map<String, JobScheduleState> getAllStates() {
        return new LinkedHashMap<>(states);
    }

    @Override
    public void saveState(JobScheduleState state) {
        states.put(state.jobId(), state);
    }

    @Override
    public void deleteState(String jobId) {
        if (jobId != null) {
            states.remove(jobId);
        }
    }

    @Override
    public List<JobScheduleState> findDue(Instant now, int limit) {
        int cap = Math.max(1, limit);
        return states.values().stream()
                .filter(s -> s.enabled() && s.nextFireAt() != null && !s.nextFireAt().isAfter(now))
                .sorted(Comparator.comparing(JobScheduleState::nextFireAt).thenComparing(JobScheduleState::jobId))
                .limit(cap)
                .toList();
    }

    @Override
    public void recordDispatch(ScheduleDispatchRecord dispatch) {
        dispatches.add(dispatch);
        while (dispatches.size() > MAX_DISPATCHES) {
            dispatches.removeFirst();
        }
    }

    @Override
    public List<ScheduleDispatchRecord> listDispatches(String jobId) {
        return dispatches.stream()
                .filter(d -> d.jobId().equals(jobId))
                .sorted(Comparator.comparing(ScheduleDispatchRecord::dispatchedAt).reversed()
                        .thenComparing(Comparator.comparing(ScheduleDispatchRecord::id).reversed()))
                .toList();
    }

    @Override
    public int countDispatchesSince(Instant since, List<DispatchOutcome> outcomes) {
        return (int) dispatches.stream()
                .filter(d -> !d.dispatchedAt().isBefore(since))
                .filter(d -> outcomes == null || outcomes.isEmpty() || outcomes.contains(d.outcome()))
                .count();
    }

    @Override
    public int deleteDispatchesBefore(Instant cutoff) {
        int before = dispatches.size();
        dispatches.removeIf(d -> d.dispatchedAt().isBefore(cutoff));
        return Math.max(0, before - dispatches.size());
    }
}
