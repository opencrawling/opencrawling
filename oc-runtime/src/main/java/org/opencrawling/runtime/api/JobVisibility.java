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
package org.opencrawling.runtime.api;

import org.opencrawling.core.reporting.JobReportSummary;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.security.ManagementAuthorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Whether the current caller may read a job's traces, errors and reports, for jobs from the job list and for dynamic
 * jobs started by agents. Everything is visible with authorization off.
 *
 * <ul>
 *   <li>A job in the job list: its {@link JobAccess} decides (see {@link ManagementAuthorization}).</li>
 *   <li>Any other job id (a dynamic job, or a job deleted since): admins, and the user a still-remembered dynamic job
 *       was started for. For reports, also the user the run was recorded as running for.</li>
 * </ul>
 */
@Component
public class JobVisibility {

    private final ManagementAuthorization authorization;
    private final JobController jobController;
    private final ObjectProvider<DynamicJobService> dynamicJobs;

    public JobVisibility(ManagementAuthorization authorization, @Lazy JobController jobController,
                         ObjectProvider<DynamicJobService> dynamicJobs) {
        this.authorization = authorization;
        this.jobController = jobController;
        this.dynamicJobs = dynamicJobs;
    }

    public boolean canReadJob(String jobId) {
        if (!authorization.isEnabled()) {
            return true;
        }
        JobController.JobDTO job = jobController.findJobById(jobId);
        if (job != null) {
            return jobController.canRead(job);
        }
        if (authorization.isAdmin()) {
            return true;
        }
        DynamicJobService dynamic = dynamicJobs.getIfAvailable();
        return dynamic != null && authorization.currentCaller()
                .map(caller -> dynamic.isStartedFor(caller, jobId))
                .orElse(false);
    }

    public boolean canReadReport(JobReportSummary summary) {
        if (!authorization.isEnabled()) {
            return true;
        }
        if (summary == null) {
            return false;
        }
        JobController.JobDTO job = jobController.findJobById(summary.jobId());
        if (job != null) {
            return jobController.canRead(job);
        }
        return authorization.isAdmin() || canReadJob(summary.jobId()) || authorization.ranFor(summary.provenance());
    }

    public boolean isAdmin() {
        return authorization.isAdmin();
    }
}
