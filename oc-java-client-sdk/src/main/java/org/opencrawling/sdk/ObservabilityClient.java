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
package org.opencrawling.sdk;

import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.ErrorLogsResponse;
import org.opencrawling.sdk.models.JobTraceResponse;
import org.opencrawling.sdk.models.ThroughputMetricsResponse;

/**
 * Client for AIOps diagnostics, OpenTelemetry traces, error logs, and metrics.
 */
public interface ObservabilityClient {

    /**
     * Triggers AI root cause analysis and diagnosis for a job.
     */
    DiagnosticReport diagnose(String jobId);

    /**
     * Retrieves correlated OpenTelemetry execution spans and timing breakdown for a job.
     */
    JobTraceResponse getTraces(String jobId);

    /**
     * Retrieves error logs for a job within a given timeframe window (e.g. "1h", "24h", "all").
     */
    ErrorLogsResponse getErrors(String jobId, String timeframe);

    /**
     * Queries connector throughput and virtual thread concurrency metrics.
     */
    ThroughputMetricsResponse getMetrics(String connectorId);
}
