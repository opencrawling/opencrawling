/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import org.opencrawling.runtime.orchestrator.JobRunRegistry;

import java.time.Instant;

/** A job run in progress, in any runtime that shares the control plane. Finished runs are executions, with their reports. */
@Schema(description = "A job run in progress")
public record JobRunResource(
        @Schema(description = "Execution id; after the run, its report is at /api/v1/executions/{executionId}", example = "exec-1a2b3c4d") String executionId,
        @Schema(description = "The job that runs", example = "1763038591234") String jobId,
        @Schema(description = "When the run started") Instant startedAt,
        @Schema(description = "Whether a stop was requested") boolean stopRequested,
        @Schema(description = "RUNNING, or PAUSED: the run holds before its next document until :resume or :stop", example = "RUNNING") String state,
        @Schema(description = "The runtime that runs it", example = "oc-mcp-server-1a2b3c4d") String runtimeId) {

    public static JobRunResource of(JobRunRegistry.RunInfo run) {
        return new JobRunResource(run.executionId(), run.jobId(), run.startedAt(), run.stopRequested(),
                run.state() == null ? null : run.state().name(), run.runtimeId());
    }

    /** The same run, with a stop requested. */
    public JobRunResource stopping() {
        return new JobRunResource(executionId, jobId, startedAt, true, state, runtimeId);
    }
}
