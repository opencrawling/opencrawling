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
package org.opencrawling.runtime.observability;

import org.springframework.stereotype.Component;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OpenTelemetry and Micrometer Telemetry Recorder for OpenCrawling.
 * Captures correlated pipeline spans, performance metrics, and error logs by jobId.
 */
@Component
public class TelemetryTraceStore implements SpanExporter {

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

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData span : spans) {
            String spanId = span.getSpanContext().getSpanId();
            String traceId = span.getSpanContext().getTraceId();
            
            Map<String, String> attrs = new HashMap<>();
            span.getAttributes().forEach((key, value) -> attrs.put(key.getKey(), String.valueOf(value)));
            
            String jobId = attrs.getOrDefault("jobId", attrs.getOrDefault("job.id", "system"));
            String stage = attrs.getOrDefault("pipeline.stage", span.getName());
            String component = attrs.getOrDefault("component", span.getName());
            
            long startTimeMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(span.getStartEpochNanos());
            long durationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos());
            
            String status = span.getStatus().getStatusCode().name();
            String errorMessage = span.getStatus().getDescription();
            
            SpanRecord record = new SpanRecord(
                spanId,
                traceId,
                jobId,
                stage,
                component,
                startTimeMillis,
                durationMillis,
                status,
                errorMessage,
                attrs
            );
            recordSpan(record);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
