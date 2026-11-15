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
import java.util.Map;

/**
 * A dynamic job: a crawl of one folder, or the ingest or removal of one document, started on demand.
 *
 * @param kind          FOLDER or DOCUMENT
 * @param state         for example PENDING_APPROVAL, RUNNING, COMPLETED, FAILED, STOPPED or REJECTED
 * @param start         what it crawls: the connector's start folder or document, as the runtime resolved it
 * @param executionId   the run's execution id, once it runs; its report is the execution with this id
 * @param note          why it ended as it did, when the runtime says
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DynamicJob(String jobId, String executionId, String name, String connector, String kind,
                         Map<String, Object> start, String state, boolean stopRequested, Instant createdAt,
                         Instant finishedAt, Long documents, String owner, String triggerSource, String approvedBy,
                         String note, Map<String, Object> chunking) {}
