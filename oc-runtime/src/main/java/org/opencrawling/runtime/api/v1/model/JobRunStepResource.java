/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.api.v1.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.opencrawling.runtime.orchestrator.RunStore;

import java.time.Instant;

/** One entry of a run's history: a change of its state. */
@Schema(description = "A change of a run's state")
public record JobRunStepResource(
        @Schema(description = "Position in the run's history, from 1", example = "1") int seq,
        @Schema(description = "STARTED, PAUSED, RESUMED, STOP_REQUESTED, then COMPLETED, STOPPED, FAILED or INTERRUPTED;"
                + " a run asked for (a dynamic job) starts with QUEUED or APPROVAL_REQUESTED, may be APPROVED, and may end"
                + " REJECTED or EXPIRED without starting", example = "STARTED") String event,
        @Schema(description = "When it happened") Instant at,
        @Schema(description = "The runtime that recorded it") String runtimeId,
        @Schema(description = "Why, for FAILED and INTERRUPTED (and a rejection's or cancellation's note); who, for APPROVED") String detail) {

    public static JobRunStepResource of(RunStore.RunStep step) {
        return new JobRunStepResource(step.seq(), step.event().name(), step.at(), step.runtimeId(), step.detail());
    }
}
