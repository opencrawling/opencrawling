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
package org.opencrawling.core.event;

import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.ExecutionProvenance;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Spring application event emitted at the conclusion of a crawl job execution.
 * Triggers asynchronous post-job reporting generation (Parquet, Avro, CSV, JSON).
 */
public record JobExecutionCompletedEvent(
        String jobId,
        String executionId,
        String jobName,
        PipelineMode pipelineMode,
        String repositoryConnector,
        String outputConnector,
        Instant startTime,
        Instant endTime,
        long totalDocuments,
        long successfulDocuments,
        long failedDocuments,
        long skippedDocuments,
        Map<String, Object> summaryMetrics,
        List<DocumentLedgerEntry> documentEntries,
        ExecutionProvenance provenance
) {

    public JobExecutionCompletedEvent {
        provenance = provenance != null ? provenance : ExecutionProvenance.unknown();
    }

    /** An event whose provenance is unknown. */
    public JobExecutionCompletedEvent(String jobId, String executionId, String jobName, PipelineMode pipelineMode,
                                      String repositoryConnector, String outputConnector, Instant startTime, Instant endTime,
                                      long totalDocuments, long successfulDocuments, long failedDocuments, long skippedDocuments,
                                      Map<String, Object> summaryMetrics, List<DocumentLedgerEntry> documentEntries) {
        this(jobId, executionId, jobName, pipelineMode, repositoryConnector, outputConnector, startTime, endTime,
                totalDocuments, successfulDocuments, failedDocuments, skippedDocuments, summaryMetrics, documentEntries, null);
    }
}
