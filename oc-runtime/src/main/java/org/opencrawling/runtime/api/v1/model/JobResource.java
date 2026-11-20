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
import org.opencrawling.runtime.api.JobAccess;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.scheduler.JobScheduleConfig;
import org.opencrawling.runtime.scheduler.JobScheduleState;
import org.opencrawling.runtime.security.ManagementAuthorization.JobAction;

import java.util.List;
import java.util.Map;

/** A job as the Platform API returns it: its definition, its current state, and what the caller may do with it. */
@Schema(description = "A pipeline job")
public record JobResource(
        @Schema(description = "Job id, assigned by the runtime", example = "1763038591234") String id,
        @Schema(description = "Human-readable name", example = "HR contracts") String name,
        @Schema(description = "Repository connector name", example = "FileSystem_Local") String repositoryConnector,
        @Schema(description = "Output connector name", example = "PGVector_Output") String outputConnector,
        @Schema(description = "Authority connector name, if any") String authorityConnector,
        @Schema(description = "Transformation (embedding) connector name", example = "Ollama_Embedding_Default") String transformationConnector,
        @Schema(description = "Start path, URL or repository location", example = "/data/hr") String path,
        @Schema(description = "Pipeline mode; null means the runtime default", example = "rag") String pipelineMode,
        @Schema(description = "Connector-specific settings") Map<String, Object> crawlSpec,
        @Schema(description = "Auto-narrativization settings") JobController.NarrativizationConfig narrativization,
        @Schema(description = "Who may see and run the job; null for jobs created before management authorization was on") JobAccess access,
        @Schema(description = "Current state, managed by the runtime") JobState state,
        @Schema(description = "What the caller may do with this job", example = "[\"READ\", \"EXECUTE\", \"MANAGE\"]") List<JobAction> permissions,
        @Schema(description = "Recurring schedule configuration, if scheduled") JobScheduleConfig schedule,
        @Schema(description = "Runtime-managed schedule execution state, if scheduled") JobScheduleState scheduleState) {

    public JobResource(String id, String name, String repositoryConnector, String outputConnector,
                       String authorityConnector, String transformationConnector, String path, String pipelineMode,
                       Map<String, Object> crawlSpec, JobController.NarrativizationConfig narrativization,
                       JobAccess access, JobState state, List<JobAction> permissions) {
        this(id, name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path,
                pipelineMode, crawlSpec, narrativization, access, state, permissions, null, null);
    }

    @Schema(description = "A job's current state")
    public record JobState(
            @Schema(description = "Ready, Running, Paused, Finished or Error", example = "Ready") String status,
            @Schema(description = "Current or last stage, for example Idle, Scanning, Completed, Stopped, Failed or 'Rejected: ...'", example = "Idle") String stage,
            @Schema(description = "Documents processed by the last run", example = "0") long documents,
            @Schema(description = "When the job last ran; null if never") String lastRun) {}

    public static JobResource of(JobController.JobDTO job, List<JobAction> permissions) {
        return of(job, permissions, null);
    }

    public static JobResource of(JobController.JobDTO job, List<JobAction> permissions, JobScheduleState scheduleState) {
        String lastRun = job.lastRun() == null || job.lastRun().isBlank() || "N/A".equals(job.lastRun()) ? null : job.lastRun();
        return new JobResource(job.id(), job.name(), job.repositoryConnector(), job.outputConnector(),
                JobInput.blankToNull(job.authorityConnector()), job.transformationConnector(), job.path(),
                job.pipelineMode(), job.crawlSpec(), job.narrativization(), job.access(),
                new JobState(job.status(), job.currentStage(), job.documents(), lastRun),
                permissions == null ? List.of() : permissions,
                job.schedule(),
                job.schedule() != null ? scheduleState : null);
    }
}
