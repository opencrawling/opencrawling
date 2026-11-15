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
package org.opencrawling.core.reporting;

import org.opencrawling.core.pipeline.PipelineMode;
import java.time.Instant;

/**
 * Granular document-level execution record capturing audit and performance metadata
 * for both RAG and Migration pipelines.
 */
public record DocumentLedgerEntry(
        String jobId,
        String executionId,
        PipelineMode pipelineMode,
        String documentId,
        String sourceUri,
        String connectorSource,
        String connectorTarget,
        String status,
        long durationMs,
        Instant timestampUtc,
        String errorCode,
        String errorMessage,
        // RAG Pipeline fields
        Integer ragChunkCount,
        Integer ragTokenCount,
        String ragEmbeddingModel,
        Integer ragEmbeddingDimensions,
        String ragVectorTable,
        // Migration Pipeline fields
        Long migBinarySizeBytes,
        String migSha256Checksum,
        String migTargetBucket,
        String migTargetKey,
        Boolean migSidecarWritten,
        Integer migCallbackHttpStatus,
        Boolean migSafePruned,
        Long migReclaimedBytes
) {
    public static DocumentLedgerEntry ragSuccess(
            String jobId,
            String executionId,
            String documentId,
            String sourceUri,
            String connectorSource,
            String connectorTarget,
            long durationMs,
            int chunkCount,
            int tokenCount,
            String embeddingModel,
            int dimensions,
            String vectorTable) {
        return new DocumentLedgerEntry(
                jobId, executionId, PipelineMode.RAG, documentId, sourceUri,
                connectorSource, connectorTarget, "SUCCESS", durationMs, Instant.now(),
                null, null,
                chunkCount, tokenCount, embeddingModel, dimensions, vectorTable,
                null, null, null, null, null, null, null, null
        );
    }

    public static DocumentLedgerEntry migrationSuccess(
            String jobId,
            String executionId,
            String documentId,
            String sourceUri,
            String connectorSource,
            String connectorTarget,
            long durationMs,
            long binarySizeBytes,
            String sha256Checksum,
            String targetBucket,
            String targetKey,
            boolean sidecarWritten,
            Integer callbackStatus,
            boolean safePruned,
            long reclaimedBytes) {
        return new DocumentLedgerEntry(
                jobId, executionId, PipelineMode.MIGRATION, documentId, sourceUri,
                connectorSource, connectorTarget, "SUCCESS", durationMs, Instant.now(),
                null, null,
                null, null, null, null, null,
                binarySizeBytes, sha256Checksum, targetBucket, targetKey,
                sidecarWritten, callbackStatus, safePruned, reclaimedBytes
        );
    }

    public static DocumentLedgerEntry failure(
            String jobId,
            String executionId,
            PipelineMode mode,
            String documentId,
            String sourceUri,
            String connectorSource,
            String connectorTarget,
            long durationMs,
            String errorCode,
            String errorMessage) {
        return new DocumentLedgerEntry(
                jobId, executionId, mode, documentId, sourceUri,
                connectorSource, connectorTarget, "FAILED", durationMs, Instant.now(),
                errorCode, errorMessage,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
    }
}
