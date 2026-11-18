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
package org.opencrawling.runtime.api.v1.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.opencrawling.runtime.api.JobController;

import java.time.Instant;
import java.util.List;

/** A run, in progress or ended, with the history of its states; kept for the run retention (90 days by default). */
@Schema(description = "A run and the history of its states")
public record JobRunHistoryResource(
        @Schema(description = "Execution id", example = "exec-1a2b3c4d") String executionId,
        @Schema(description = "The job", example = "1763038591234") String jobId,
        @Schema(description = "RUNNING, PAUSED, COMPLETED, STOPPED, FAILED or INTERRUPTED", example = "COMPLETED") String state,
        @Schema(description = "When the run started") Instant startedAt,
        @Schema(description = "When it ended; absent while it runs") Instant endedAt,
        @Schema(description = "The runtime that ran it") String runtimeId,
        @Schema(description = "The changes of its state, in order") List<JobRunStepResource> steps) {

    public static JobRunHistoryResource of(JobController.RunHistory history) {
        var run = history.run();
        return new JobRunHistoryResource(run.executionId(), run.jobId(), run.state().name(), run.startedAt(),
                run.endedAt(), run.runtimeId(), history.steps().stream().map(JobRunStepResource::of).toList());
    }
}
