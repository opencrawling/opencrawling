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
import org.opencrawling.runtime.api.v1.ApiV1;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A run of any kind (#316): a job run, or a dynamic job (a folder or one document), from the moment it is asked for
 * (waiting for approval, if needed) until it ends, while it is kept.
 */
@Schema(description = "A run of any kind: a job run or a dynamic job")
public record RunResource(
        @Schema(description = "The run's id, its execution id; after it ran, its report is at /api/v1/executions/{runId}", example = "exec-1a2b3c4d") String runId,
        @Schema(description = "JOB, DYNAMIC_FOLDER or DYNAMIC_DOCUMENT", example = "JOB") String kind,
        @Schema(description = "The job it runs: a job id, or a dynamic job's id", example = "1763038591234") String jobId,
        @Schema(description = "The job's name", example = "Nightly policies") String name,
        @Schema(description = "RUNNING, PAUSED, COMPLETED, STOPPED, FAILED or INTERRUPTED; for dynamic jobs also PENDING_APPROVAL, REJECTED or EXPIRED", example = "RUNNING") String state,
        @Schema(description = "Whether a stop was requested") boolean stopRequested,
        @Schema(description = "The runtime that runs or ran it; absent before it started", example = "oc-mcp-server-1a2b3c4d") String runtimeId,
        @Schema(description = "Who started it, when known", example = "alice") String startedBy,
        @Schema(description = "When it was asked for (dynamic jobs) or started (job runs)") Instant createdAt,
        @Schema(description = "When it ended; absent while it waits or runs") Instant endedAt,
        @Schema(description = "Why it ended as it did, when there is something to say", example = "Rejected by carol: out of scope.") String note,
        @Schema(description = "Related resources: self, history, job or dynamicJob, execution") Map<String, String> links) {

    public static final String JOB = "JOB";
    public static final String DYNAMIC_FOLDER = "DYNAMIC_FOLDER";
    public static final String DYNAMIC_DOCUMENT = "DYNAMIC_DOCUMENT";

    public static RunResource of(JobRunRegistry.RunInfo run, String jobName) {
        return new RunResource(run.executionId(), JOB, run.jobId(), jobName, run.state() == null ? null : run.state().name(),
                run.stopRequested(), run.runtimeId(), run.caller(), run.startedAt(), run.endedAt(), null,
                links(run.executionId(), "job", ApiV1.BASE + "/jobs/" + run.jobId(), true));
    }

    public static RunResource of(DynamicJobService.JobStatus job, String runtimeId) {
        boolean ran = runtimeId != null;
        return new RunResource(job.executionId(),
                job.kind() == DynamicJobService.Kind.DOCUMENT ? DYNAMIC_DOCUMENT : DYNAMIC_FOLDER, job.jobId(), job.name(),
                job.state().name(), job.stopRequested(), runtimeId, job.owner(), job.createdAt(), job.finishedAt(), job.note(),
                links(job.executionId(), "dynamicJob", ApiV1.BASE + "/dynamic-jobs/" + job.jobId(), ran));
    }

    /** Whether it holds a run now. */
    public boolean active() {
        return "RUNNING".equals(state) || "PAUSED".equals(state);
    }

    private static Map<String, String> links(String runId, String jobLink, String jobUrl, boolean ran) {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("self", ApiV1.BASE + "/runs/" + runId);
        links.put("history", ApiV1.BASE + "/runs/" + runId + "/history");
        links.put(jobLink, jobUrl);
        if (ran) {
            links.put("execution", ApiV1.BASE + "/executions/" + runId);
        }
        return links;
    }
}
