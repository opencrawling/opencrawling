/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.ErrorLogsResponse;
import org.opencrawling.sdk.models.JobTraceResponse;
import org.opencrawling.sdk.models.ThroughputMetricsResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Default implementation of ObservabilityClient using HttpTransport.
 */
public class DefaultObservabilityClient implements ObservabilityClient {

    private final HttpTransport transport;

    public DefaultObservabilityClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public DiagnosticReport diagnose(String jobId) {
        return transport.execute("GET", "/api/observability/diagnose/" + jobId, null, DiagnosticReport.class);
    }

    @Override
    public JobTraceResponse getTraces(String jobId) {
        return transport.execute("GET", "/api/observability/traces/" + jobId, null, JobTraceResponse.class);
    }

    @Override
    public ErrorLogsResponse getErrors(String jobId, String timeframe) {
        String tf = timeframe != null ? URLEncoder.encode(timeframe, StandardCharsets.UTF_8) : "all";
        return transport.execute("GET", "/api/observability/errors/" + jobId + "?timeframe=" + tf, null, ErrorLogsResponse.class);
    }

    @Override
    public ThroughputMetricsResponse getMetrics(String connectorId) {
        String path = "/api/observability/metrics";
        if (connectorId != null && !connectorId.isBlank()) {
            path += "?connectorId=" + URLEncoder.encode(connectorId, StandardCharsets.UTF_8);
        }
        return transport.execute("GET", path, null, ThroughputMetricsResponse.class);
    }
}
