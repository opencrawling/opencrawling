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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Macro executive summary of a crawl job execution run for audit reporting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobReportSummary(
        String jobId,
        String executionId,
        String jobName,
        String pipelineMode,
        String repositoryConnector,
        String outputConnector,
        String status,
        Instant startTime,
        Instant endTime,
        long durationMs,
        long totalDocuments,
        long successfulDocuments,
        long failedDocuments,
        long skippedDocuments,
        Long totalChunks,
        Long totalTokens,
        Double estimatedEmbeddingCostUsd,
        String embeddingModel,
        Long totalBinaryBytes,
        Long totalReclaimedBytes,
        Long safePrunedCount,
        Long verifiedChecksumCount,
        String reportPathParquet,
        String reportPathCsv,
        String reportPathJson
) {}
