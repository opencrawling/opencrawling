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

import com.fasterxml.jackson.core.type.TypeReference;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.exceptions.OpenCrawlingNetworkException;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.JobReportSummary;
import org.opencrawling.sdk.models.PaginatedLedgerResponse;
import org.opencrawling.sdk.models.QueryResult;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of ReportClient utilizing HttpTransport.
 */
public class DefaultReportClient implements ReportClient {

    private final HttpTransport transport;

    public DefaultReportClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<JobReportSummary> list() {
        return transport.execute("GET", "/api/reports", null, new TypeReference<List<JobReportSummary>>() {});
    }

    @Override
    public Optional<JobReportSummary> get(String jobId, String executionId) {
        try {
            JobReportSummary summary = transport.execute("GET", "/api/reports/" + jobId + "/" + executionId, null, JobReportSummary.class);
            return Optional.ofNullable(summary);
        } catch (OpenCrawlingApiException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public PaginatedLedgerResponse getDocumentLedger(String jobId, String executionId, String status, String query, int page, int size) {
        StringBuilder sb = new StringBuilder("/api/reports/")
                .append(jobId).append("/").append(executionId).append("/documents")
                .append("?page=").append(page)
                .append("&size=").append(size);

        if (status != null && !status.isBlank()) {
            sb.append("&status=").append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        }
        if (query != null && !query.isBlank()) {
            sb.append("&query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }

        return transport.execute("GET", sb.toString(), null, PaginatedLedgerResponse.class);
    }

    @Override
    public byte[] download(String jobId, String executionId, String format) {
        String fmt = format != null ? format.toLowerCase() : "json";
        return transport.executeBytes("GET", "/api/reports/" + jobId + "/" + executionId + "/export?format=" + fmt);
    }

    @Override
    public void downloadToFile(String jobId, String executionId, String format, Path destination) {
        byte[] bytes = download(jobId, executionId, format);
        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.write(destination, bytes);
        } catch (IOException e) {
            throw new OpenCrawlingNetworkException("Failed to write downloaded report to " + destination, e);
        }
    }

    @Override
    public QueryResult query(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be empty");
        }
        return transport.execute("POST", "/api/reports/query", Map.of("sql", sql), QueryResult.class);
    }
}
