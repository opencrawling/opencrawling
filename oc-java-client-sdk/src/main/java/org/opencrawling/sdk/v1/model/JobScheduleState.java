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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Runtime-managed schedule execution state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobScheduleState(
        boolean enabled,
        Instant nextFireAt,
        Instant lastFiredAt,
        String lastExecutionId,
        DispatchOutcome lastOutcome,
        String lastDetail,
        boolean coalescePending,
        int consecutiveFailures,
        Instant updatedAt
) {
    public enum DispatchOutcome {
        STARTED, SKIPPED_OVERLAP, SKIPPED_CAPACITY, MISSED_SKIPPED, REJECTED, AUTO_PAUSED
    }
}
