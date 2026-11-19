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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Request to validate a cron expression and preview its upcoming fire times.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CronValidationRequest(
        String cron,
        String timezone,
        Integer count,
        Instant startAt,
        Instant endAt
) {
    public CronValidationRequest(String cron, String timezone, Integer count) {
        this(cron, timezone, count, null, null);
    }

    public static CronValidationRequest of(String cron) {
        return new CronValidationRequest(cron, null, 5, null, null);
    }
}
