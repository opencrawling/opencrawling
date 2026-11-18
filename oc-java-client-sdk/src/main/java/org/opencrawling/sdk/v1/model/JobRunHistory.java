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
 * A run, in progress or ended, with the history of its states.
 *
 * @param state   {@code RUNNING}, {@code PAUSED}, {@code COMPLETED}, {@code STOPPED}, {@code FAILED} or
 *                {@code INTERRUPTED}
 * @param endedAt when it ended; {@code null} while it runs
 * @param steps   the changes of its state, in order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobRunHistory(String executionId, String jobId, String state, Instant startedAt, Instant endedAt,
                            String runtimeId, List<Step> steps) {

    /**
     * One change of the run's state.
     *
     * @param event  {@code STARTED}, {@code PAUSED}, {@code RESUMED}, {@code STOP_REQUESTED}, then {@code COMPLETED},
     *               {@code STOPPED}, {@code FAILED} or {@code INTERRUPTED}
     * @param detail why, for {@code FAILED} and {@code INTERRUPTED}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(int seq, String event, Instant at, String runtimeId, String detail) {}
}
