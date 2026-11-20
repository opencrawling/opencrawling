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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Runtime-managed schedule state for a pipeline job (#197), kept in {@link ScheduleStore}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Runtime-managed execution state for a job's schedule")
public record JobScheduleState(
        @Schema(hidden = true)
        String jobId,
        @Schema(description = "Whether the schedule is currently enabled")
        boolean enabled,
        @Schema(description = "Next scheduled execution timestamp (UTC ISO-8601); null when paused or past endAt")
        Instant nextFireAt,
        @Schema(description = "When the scheduler last evaluated/triggered this job")
        Instant lastFiredAt,
        @Schema(description = "Execution ID started by the last successful scheduled trigger")
        String lastExecutionId,
        @Schema(description = "Outcome of the last dispatch attempt")
        DispatchOutcome lastOutcome,
        @Schema(description = "Human-readable detail for the last non-STARTED outcome")
        String lastDetail,
        @Schema(description = "Whether a coalesced catch-up run will fire once the active run finishes")
        boolean coalescePending,
        @Schema(description = "Consecutive failed or rejected scheduled runs since the last completion")
        int consecutiveFailures,
        @Schema(description = "Timestamp when this state row was last updated")
        Instant updatedAt
) {}
