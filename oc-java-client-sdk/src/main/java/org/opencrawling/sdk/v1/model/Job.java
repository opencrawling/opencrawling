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
import org.opencrawling.sdk.models.JobAccess;
import org.opencrawling.sdk.models.JobAction;
import org.opencrawling.sdk.models.NarrativizationConfig;

import java.util.List;
import java.util.Map;

/**
 * A pipeline job, as the Platform API returns it.
 *
 * @param permissions what the caller may do with this job
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Job(String id, String name, String repositoryConnector, String outputConnector,
                  String authorityConnector, String transformationConnector, String path, String pipelineMode,
                  Map<String, Object> crawlSpec, NarrativizationConfig narrativization, JobAccess access,
                  State state, List<JobAction> permissions,
                  JobScheduleConfig schedule, JobScheduleState scheduleState) {

    public Job(String id, String name, String repositoryConnector, String outputConnector,
               String authorityConnector, String transformationConnector, String path, String pipelineMode,
               Map<String, Object> crawlSpec, NarrativizationConfig narrativization, JobAccess access,
               State state, List<JobAction> permissions) {
        this(id, name, repositoryConnector, outputConnector, authorityConnector, transformationConnector, path,
                pipelineMode, crawlSpec, narrativization, access, state, permissions, null, null);
    }

    /**
     * A job's current state.
     *
     * @param status    Ready, Running, Paused, Finished or Error
     * @param stage     the current or last stage, for example Idle, Scanning, Completed or Stopped
     * @param documents documents processed by the last run
     * @param lastRun   when the job last ran; null if never
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(String status, String stage, long documents, String lastRun) {}

    public Job {
        permissions = permissions != null ? List.copyOf(permissions) : List.of();
    }

    /** This job's definition, to change and send back with {@code replace}. */
    public JobInput toInput() {
        return new JobInput(name, repositoryConnector, outputConnector, authorityConnector, transformationConnector,
                path, pipelineMode, crawlSpec, narrativization, access, schedule);
    }
}
