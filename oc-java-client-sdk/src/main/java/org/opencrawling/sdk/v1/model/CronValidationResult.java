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

import java.time.Instant;
import java.util.List;

/**
 * Result of validating a cron expression via {@code POST /api/v1/schedules:validate-cron}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CronValidationResult(
        boolean valid,
        String cron,
        String normalizedCron,
        String timezone,
        String humanDescription,
        List<Instant> nextFireTimes,
        String error
) {
    public CronValidationResult {
        nextFireTimes = nextFireTimes != null ? List.copyOf(nextFireTimes) : List.of();
    }
}
