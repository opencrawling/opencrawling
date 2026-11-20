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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Recurring schedule definition embedded on a pipeline job (#197).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Recurring cron schedule configuration for a pipeline job")
public record JobScheduleConfig(
        @NotBlank @Size(max = 120)
        @Schema(description = "5-field POSIX cron (default, OIS-compatible), 6-field Spring cron (with seconds), or standard macro (@hourly, @daily, @weekly, @monthly)", example = "0 2 * * *")
        String cron,

        @Size(max = 64)
        @Schema(description = "IANA timezone ID for cron evaluation (defaults to runtime defaultTimezone, usually UTC)", example = "Europe/Rome")
        String timezone,

        @Schema(description = "Whether automatic scheduled triggering is active", example = "true")
        boolean enabled,

        @Schema(description = "Behavior when a scheduled tick arrives while the job already has an active run: SKIP (default), COALESCE, or STOP_PREVIOUS", example = "SKIP")
        OverlapPolicy overlapPolicy,

        @Schema(description = "Behavior when a scheduled tick was missed beyond the grace period during downtime: SKIP (default) or FIRE_ONCE", example = "SKIP")
        MisfirePolicy misfirePolicy,

        @Min(0) @Max(3600)
        @Schema(description = "Optional maximum random delay in seconds (0..3600) added to each scheduled tick to stagger concurrent crawls", example = "60")
        Integer jitterSeconds,

        @Schema(description = "Optional ISO-8601 instant before which scheduled ticks do not fire")
        Instant startAt,

        @Schema(description = "Optional ISO-8601 instant after which scheduled ticks stop firing")
        Instant endAt,

        @Min(0) @Max(100)
        @Schema(description = "Automatically pause the schedule after N consecutive failed or rejected scheduled runs (0 disables auto-pause)", example = "5")
        Integer maxConsecutiveFailures
) {
    /** How the scheduler handles a tick when {@code JobRunRegistry} already holds an active run for this job. */
    public enum OverlapPolicy {
        /** Skip this tick and record {@code SKIPPED_OVERLAP}. */
        SKIP,
        /** Coalesce into at most one catch-up run once the active run finishes. */
        COALESCE,
        /** Ask the active run to stop cleanly and start the new run if it clears within the grace wait, else coalesce. */
        STOP_PREVIOUS
    }

    /** How the scheduler handles a tick delayed past {@code opencrawling.scheduler.misfire-grace-period}. */
    public enum MisfirePolicy {
        /** Skip missed ticks and jump to the next future tick. */
        SKIP,
        /** Fire once immediately to catch up and resume on future ticks. */
        FIRE_ONCE
    }

    public JobScheduleConfig {
        cron = cron != null ? cron.strip() : null;
        timezone = timezone != null && !timezone.isBlank() ? timezone.strip() : null;
    }

    public JobScheduleConfig withEnabled(boolean nextEnabled) {
        return new JobScheduleConfig(cron, timezone, nextEnabled, overlapPolicy, misfirePolicy,
                jitterSeconds, startAt, endAt, maxConsecutiveFailures);
    }

    public String effectiveTimezone(String fallback) {
        if (timezone != null && !timezone.isBlank()) {
            return timezone;
        }
        return fallback != null && !fallback.isBlank() ? fallback : "UTC";
    }

    public OverlapPolicy effectiveOverlapPolicy() {
        return overlapPolicy != null ? overlapPolicy : OverlapPolicy.SKIP;
    }

    public MisfirePolicy effectiveMisfirePolicy() {
        return misfirePolicy != null ? misfirePolicy : MisfirePolicy.SKIP;
    }

    public int effectiveJitterSeconds() {
        return jitterSeconds != null ? Math.max(0, jitterSeconds) : 0;
    }

    public int effectiveMaxConsecutiveFailures() {
        return maxConsecutiveFailures != null ? Math.max(0, maxConsecutiveFailures) : 0;
    }
}
