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
package org.opencrawling.runtime.scheduler;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Immutable audit record for a single scheduler dispatch attempt or auto-pause transition (#197).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Audit entry for a single scheduler dispatch attempt")
public record ScheduleDispatchRecord(
        @Schema(description = "Unique dispatch id")
        String id,
        @Schema(description = "Target job id")
        String jobId,
        @Schema(description = "Target fire timestamp evaluated by the scheduler")
        Instant scheduledFor,
        @Schema(description = "Actual timestamp when the runtime evaluated/dispatched this tick")
        Instant dispatchedAt,
        @Schema(description = "ID of the runtime replica that claimed and evaluated the tick")
        String runtimeId,
        @Schema(description = "Execution ID created when outcome is STARTED")
        String executionId,
        @Schema(description = "Outcome of this dispatch attempt")
        DispatchOutcome outcome,
        @Schema(description = "Additional detail for skipped, rejected or auto-paused dispatches")
        String detail
) {}
