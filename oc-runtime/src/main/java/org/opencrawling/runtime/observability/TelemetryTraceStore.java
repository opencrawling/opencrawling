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
package org.opencrawling.runtime.observability;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OpenTelemetry and Micrometer Telemetry Recorder for OpenCrawling.
 * Captures correlated pipeline spans, performance metrics, and error logs by jobId.
 */
@Component
public class TelemetryTraceStore {

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

    public record ErrorLogRecord(
            String jobId,
            Instant timestamp,
            String level,
            String component,
            String message,
            String stackTrace
    ) {}

    public record MetricRecord(
            String connectorId,
            String metricName,
            double value,
            Instant timestamp
    ) {}

    private final Map<String, List<SpanRecord>> jobSpans = new ConcurrentHashMap<>();
    private final Map<String, List<ErrorLogRecord>> jobErrors = new ConcurrentHashMap<>();
    private final List<MetricRecord> metrics = new CopyOnWriteArrayList<>();

    public void recordSpan(SpanRecord span) {
        if (span.jobId() != null) {
            jobSpans.computeIfAbsent(span.jobId(), k -> new CopyOnWriteArrayList<>()).add(span);
        }
    }

    public void recordError(String jobId, String level, String component, String message, String stackTrace) {
        ErrorLogRecord record = new ErrorLogRecord(
                jobId != null ? jobId : "system",
                Instant.now(),
                level,
                component,
                message,
                stackTrace
        );
        jobErrors.computeIfAbsent(record.jobId(), k -> new CopyOnWriteArrayList<>()).add(record);
    }

    public void recordMetric(String connectorId, String metricName, double value) {
        metrics.add(new MetricRecord(connectorId, metricName, value, Instant.now()));
    }

    public List<SpanRecord> getSpansForJob(String jobId) {
        return jobSpans.getOrDefault(jobId, Collections.emptyList());
    }

    public List<ErrorLogRecord> getErrorsForJob(String jobId) {
        return jobErrors.getOrDefault(jobId, Collections.emptyList());
    }

    public Map<String, List<SpanRecord>> getAllSpans() {
        return Collections.unmodifiableMap(jobSpans);
    }

    public List<MetricRecord> getMetricsForConnector(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) return Collections.unmodifiableList(metrics);
        return metrics.stream()
                .filter(m -> m.connectorId() != null && m.connectorId().equalsIgnoreCase(connectorId))
                .toList();
    }
}
