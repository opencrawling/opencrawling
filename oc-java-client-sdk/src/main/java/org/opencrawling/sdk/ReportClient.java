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

import org.opencrawling.sdk.models.JobReportSummary;
import org.opencrawling.sdk.models.PaginatedLedgerResponse;
import org.opencrawling.sdk.models.QueryResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Client for interacting with OpenCrawling Post-Job Audit Reporting REST APIs.
 */
public interface ReportClient {

    /**
     * Lists all job execution audit report summaries.
     */
    List<JobReportSummary> list();

    /**
     * Retrieves an audit report summary for a specific job execution.
     */
    Optional<JobReportSummary> get(String jobId, String executionId);

    /**
     * Retrieves document-level ledger entries with filtering and pagination.
     */
    PaginatedLedgerResponse getDocumentLedger(String jobId, String executionId, String status, String query, int page, int size);

    /**
     * Retrieves document-level ledger entries with default pagination (first 50 entries).
     */
    default PaginatedLedgerResponse getDocumentLedger(String jobId, String executionId) {
        return getDocumentLedger(jobId, executionId, null, null, 0, 50);
    }

    /**
     * Downloads an audit artifact (parquet, avro, csv, json) as raw bytes.
     */
    byte[] download(String jobId, String executionId, String format);

    /**
     * Downloads an audit artifact and writes it directly to the specified destination file.
     */
    void downloadToFile(String jobId, String executionId, String format, Path destination);

    /**
     * Executes a serverless DuckDB analytical SQL query over the Parquet Lakehouse files.
     */
    QueryResult query(String sql);
}
