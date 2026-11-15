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
 * Granular document-level execution ledger record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentLedgerEntry(
        String jobId,
        String executionId,
        String pipelineMode,
        String documentId,
        String sourceUri,
        String connectorSource,
        String connectorTarget,
        String status,
        long durationMs,
        Instant timestampUtc,
        String errorCode,
        String errorMessage,
        Integer ragChunkCount,
        Integer ragTokenCount,
        String ragEmbeddingModel,
        Integer ragEmbeddingDimensions,
        String ragVectorTable,
        Long migBinarySizeBytes,
        String migSha256Checksum,
        String migTargetBucket,
        String migTargetKey,
        Boolean migSidecarWritten,
        Integer migCallbackHttpStatus,
        Boolean migSafePruned,
        Long migReclaimedBytes
) {}
