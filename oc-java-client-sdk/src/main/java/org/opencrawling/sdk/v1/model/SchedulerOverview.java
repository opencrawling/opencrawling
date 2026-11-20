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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Overall native scheduler status, aggregate counts and upcoming timeline.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchedulerOverview(
        boolean schedulerEnabled,
        String pollInterval,
        String defaultTimezone,
        int totalScheduledJobs,
        int activeSchedules,
        int pausedSchedules,
        int autoPausedSchedules,
        int skippedLast24h,
        List<UpcomingExecution> upcoming
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpcomingExecution(
            String jobId,
            String jobName,
            String cron,
            String timezone,
            Instant scheduledAt
    ) {}

    public SchedulerOverview {
        upcoming = upcoming != null ? List.copyOf(upcoming) : List.of();
    }
}
