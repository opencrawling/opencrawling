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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * A run of any kind: a job run, or a dynamic job (a folder or one document).
 *
 * @param runId     the run's id, its execution id
 * @param kind      {@code JOB}, {@code DYNAMIC_FOLDER} or {@code DYNAMIC_DOCUMENT}
 * @param state     {@code RUNNING}, {@code PAUSED}, {@code COMPLETED}, {@code STOPPED}, {@code FAILED} or
 *                  {@code INTERRUPTED}; for dynamic jobs also {@code PENDING_APPROVAL}, {@code REJECTED} or
 *                  {@code EXPIRED}
 * @param runtimeId the runtime that runs or ran it; {@code null} before it started
 * @param createdAt when it was asked for (dynamic jobs) or started (job runs)
 * @param endedAt   when it ended; {@code null} while it waits or runs
 * @param links     related resources: {@code self}, {@code history}, {@code job} or {@code dynamicJob},
 *                  {@code execution}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Run(String runId, String kind, String jobId, String name, String state, boolean stopRequested,
                  String runtimeId, String startedBy, Instant createdAt, Instant endedAt, String note,
                  Map<String, String> links) {}
