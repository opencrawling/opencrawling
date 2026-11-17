/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

/**
 * A job run in progress. After the run, its report is the execution with the same id.
 *
 * @param stopRequested whether a stop was requested
 * @param state         {@code RUNNING}, or {@code PAUSED}: the run holds before its next document
 * @param runtimeId     the runtime that runs it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobRun(String executionId, String jobId, Instant startedAt, boolean stopRequested, String state,
                     String runtimeId) {}
