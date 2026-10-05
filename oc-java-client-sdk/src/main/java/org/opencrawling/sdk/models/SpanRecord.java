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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Record representing an OpenTelemetry span execution trace.
 *
 * @param spanId the unique span identifier
 * @param traceId the parent trace identifier
 * @param jobId the related crawling job identifier
 * @param stage the processing stage
 * @param component the executing component name
 * @param startTimeMillis execution start time in milliseconds epoch
 * @param durationMillis duration of the span in milliseconds
 * @param status the span execution status (OK, ERROR)
 * @param errorMessage error message if failed
 * @param attributes additional span key-value attributes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpanRecord(
    String spanId,
    String traceId,
    String jobId,
    String stage,
    String component,
    long startTimeMillis,
    long durationMillis,
    String status,
    String errorMessage,
    Map<String, String> attributes
) {}
