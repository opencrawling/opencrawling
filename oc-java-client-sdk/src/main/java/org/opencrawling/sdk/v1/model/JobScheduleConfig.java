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
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Recurring cron schedule definition for a pipeline job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobScheduleConfig(
        String cron,
        String timezone,
        boolean enabled,
        OverlapPolicy overlapPolicy,
        MisfirePolicy misfirePolicy,
        Integer jitterSeconds,
        Instant startAt,
        Instant endAt,
        Integer maxConsecutiveFailures
) {
    public enum OverlapPolicy { SKIP, COALESCE, STOP_PREVIOUS }
    public enum MisfirePolicy { SKIP, FIRE_ONCE }

    public static JobScheduleConfig of(String cron) {
        return new JobScheduleConfig(cron, "UTC", true, OverlapPolicy.SKIP, MisfirePolicy.SKIP, null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public JobScheduleConfig withEnabled(boolean value) {
        return new JobScheduleConfig(cron, timezone, value, overlapPolicy, misfirePolicy, jitterSeconds, startAt, endAt, maxConsecutiveFailures);
    }

    public static final class Builder {
        private String cron;
        private String timezone = "UTC";
        private boolean enabled = true;
        private OverlapPolicy overlapPolicy = OverlapPolicy.SKIP;
        private MisfirePolicy misfirePolicy = MisfirePolicy.SKIP;
        private Integer jitterSeconds;
        private Instant startAt;
        private Instant endAt;
        private Integer maxConsecutiveFailures;

        public Builder cron(String cron) { this.cron = cron; return this; }
        public Builder timezone(String timezone) { this.timezone = timezone; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder overlapPolicy(OverlapPolicy overlapPolicy) { this.overlapPolicy = overlapPolicy; return this; }
        public Builder misfirePolicy(MisfirePolicy misfirePolicy) { this.misfirePolicy = misfirePolicy; return this; }
        public Builder jitterSeconds(Integer jitterSeconds) { this.jitterSeconds = jitterSeconds; return this; }
        public Builder startAt(Instant startAt) { this.startAt = startAt; return this; }
        public Builder endAt(Instant endAt) { this.endAt = endAt; return this; }
        public Builder maxConsecutiveFailures(Integer maxConsecutiveFailures) { this.maxConsecutiveFailures = maxConsecutiveFailures; return this; }

        public JobScheduleConfig build() {
            return new JobScheduleConfig(cron, timezone, enabled, overlapPolicy, misfirePolicy,
                    jitterSeconds, startAt, endAt, maxConsecutiveFailures);
        }
    }
}
