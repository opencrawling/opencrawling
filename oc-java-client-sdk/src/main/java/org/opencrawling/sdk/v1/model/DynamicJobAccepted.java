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

import java.util.Map;

/**
 * The answer to a dynamic job request. Which fields are set depends on what happened:
 * <ul>
 *   <li><b>Accepted</b> (a job was started or filed for approval): {@code jobId}, {@code executionId},
 *       {@code statusUrl} (follow it with {@code dynamicJobs().get(jobId)}), {@code state}, and {@code replayed}
 *       when the same {@code Idempotency-Key} was used before.</li>
 *   <li><b>Validated</b> ({@code validateOnly}): {@code valid}, {@code connector} and the resolved {@code start}.</li>
 *   <li><b>Deletion accepted</b> (a {@code DELETE} of a document the repository reports gone): {@code connector},
 *       {@code document} and {@code note}.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DynamicJobAccepted(String jobId, String executionId, String statusUrl, String state, boolean replayed,
                                 Boolean valid, String connector, Map<String, Object> start, String document,
                                 String note) {}
