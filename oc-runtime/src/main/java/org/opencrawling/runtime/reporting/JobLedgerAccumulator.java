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
package org.opencrawling.runtime.reporting;

import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory ledger accumulator that tracks active job executions,
 * records granular document-level events, aggregates macro statistics,
 * and publishes {@link JobExecutionCompletedEvent} upon execution completion.
 */
@Component
public class JobLedgerAccumulator {

    private static final Logger log = LoggerFactory.getLogger(JobLedgerAccumulator.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();
    private final Map<String, JobExecutionCompletedEvent> completedExecutions = new ConcurrentHashMap<>();

    public JobLedgerAccumulator(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void startExecution(
            String jobId,
            String executionId,
            String jobName,
            PipelineMode pipelineMode,
            String repositoryConnector,
            String outputConnector) {
        startExecution(jobId, executionId, jobName, pipelineMode, repositoryConnector, outputConnector, null);
    }

    /**
     * Starts tracking an execution.
     *
     * @param provenance who started it and how; {@code null} when unknown
     */
    public void startExecution(
            String jobId,
            String executionId,
            String jobName,
            PipelineMode pipelineMode,
            String repositoryConnector,
            String outputConnector,
            ExecutionProvenance provenance) {
        ExecutionContext context = new ExecutionContext(
                jobId,
                executionId,
                jobName != null ? jobName : "Job #" + jobId,
                pipelineMode != null ? pipelineMode : PipelineMode.RAG,
                repositoryConnector != null ? repositoryConnector : "UnknownRepository",
                outputConnector != null ? outputConnector : "UnknownOutput",
                Instant.now(),
                provenance != null ? provenance : ExecutionProvenance.unknown()
        );
        activeExecutions.put(executionId, context);
        log.info("Initialized JobLedgerAccumulator for job '{}' [Exec ID: {}, Mode: {}, Source: {}, Triggered by: {}]",
                context.jobName, executionId, context.pipelineMode, context.provenance.triggerSource(),
                context.provenance.triggeredBy() != null ? context.provenance.triggeredBy() : "unknown");
    }

    public boolean hasActiveExecution(String executionId) {
        return executionId != null && activeExecutions.containsKey(executionId);
    }

    public void cancelExecution(String executionId) {
        if (executionId != null) {
            activeExecutions.remove(executionId);
        }
    }

    public void recordDocument(DocumentLedgerEntry entry) {
        if (entry == null || entry.executionId() == null) return;
        ExecutionContext context = activeExecutions.get(entry.executionId());
        if (context != null) {
            context.entries.add(entry);
        } else {
            log.debug("No active execution found for executionId {}, dropping ledger entry for doc {}", entry.executionId(), entry.documentId());
        }
    }

    public JobExecutionCompletedEvent completeExecution(String executionId, boolean success, Map<String, Object> extraMetrics) {
        ExecutionContext context = activeExecutions.remove(executionId);
        if (context == null) {
            log.warn("Attempted to complete non-existent or already completed execution ID: {}", executionId);
            return null;
        }

        Instant endTime = Instant.now();
        List<DocumentLedgerEntry> entries = Collections.unmodifiableList(context.entries);

        long total = entries.size();
        long successful = entries.stream().filter(e -> "SUCCESS".equalsIgnoreCase(e.status())).count();
        long failed = entries.stream().filter(e -> "FAILED".equalsIgnoreCase(e.status())).count();
        long skipped = entries.stream().filter(e -> "SKIPPED".equalsIgnoreCase(e.status())).count();

        Map<String, Object> summaryMetrics = new HashMap<>(extraMetrics != null ? extraMetrics : Map.of());

        if (context.pipelineMode == PipelineMode.RAG) {
            long totalChunks = entries.stream().mapToInt(e -> e.ragChunkCount() != null ? e.ragChunkCount() : 0).sum();
            long totalTokens = entries.stream().mapToInt(e -> e.ragTokenCount() != null ? e.ragTokenCount() : 0).sum();
            // Pricing heuristic: approx $0.0001 per 1K tokens for local/cloud standard models
            double estimatedCost = (totalTokens / 1000.0) * 0.0001;
            String model = entries.stream()
                    .map(DocumentLedgerEntry::ragEmbeddingModel)
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst()
                    .orElse("mxbai-embed-large");

            summaryMetrics.putIfAbsent("totalChunks", totalChunks);
            summaryMetrics.putIfAbsent("totalTokens", totalTokens);
            summaryMetrics.putIfAbsent("estimatedEmbeddingCostUsd", estimatedCost);
            summaryMetrics.putIfAbsent("embeddingModel", model);
        } else if (context.pipelineMode == PipelineMode.MIGRATION) {
            long totalBytes = entries.stream().mapToLong(e -> e.migBinarySizeBytes() != null ? e.migBinarySizeBytes() : 0L).sum();
            long reclaimedBytes = entries.stream().mapToLong(e -> e.migReclaimedBytes() != null ? e.migReclaimedBytes() : 0L).sum();
            long prunedDocs = entries.stream().filter(e -> Boolean.TRUE.equals(e.migSafePruned())).count();
            long verifiedChecksums = entries.stream().filter(e -> e.migSha256Checksum() != null && !e.migSha256Checksum().isBlank()).count();

            summaryMetrics.putIfAbsent("totalBinaryBytes", totalBytes);
            summaryMetrics.putIfAbsent("totalReclaimedBytes", reclaimedBytes);
            summaryMetrics.putIfAbsent("safePrunedCount", prunedDocs);
            summaryMetrics.putIfAbsent("verifiedChecksumCount", verifiedChecksums);
        }

        JobExecutionCompletedEvent event = new JobExecutionCompletedEvent(
                context.jobId,
                context.executionId,
                context.jobName,
                context.pipelineMode,
                context.repositoryConnector,
                context.outputConnector,
                context.startTime,
                endTime,
                total,
                successful,
                failed,
                skipped,
                summaryMetrics,
                entries,
                context.provenance
        );

        completedExecutions.put(executionId, event);
        log.info("Emitting JobExecutionCompletedEvent for Job '{}' [Exec: {}, Total Docs: {}, Success: {}, Failed: {}]",
                context.jobName, executionId, total, successful, failed);

        eventPublisher.publishEvent(event);
        return event;
    }

    public JobExecutionCompletedEvent getCompletedEvent(String executionId) {
        return completedExecutions.get(executionId);
    }

    public Collection<JobExecutionCompletedEvent> getAllCompletedEvents() {
        return Collections.unmodifiableCollection(completedExecutions.values());
    }

    public List<DocumentLedgerEntry> getEntries(String executionId) {
        ExecutionContext active = activeExecutions.get(executionId);
        if (active != null) {
            return new ArrayList<>(active.entries);
        }
        JobExecutionCompletedEvent completed = completedExecutions.get(executionId);
        if (completed != null) {
            return completed.documentEntries();
        }
        return Collections.emptyList();
    }

    private static class ExecutionContext {
        final String jobId;
        final String executionId;
        final String jobName;
        final PipelineMode pipelineMode;
        final String repositoryConnector;
        final String outputConnector;
        final Instant startTime;
        final ExecutionProvenance provenance;
        final List<DocumentLedgerEntry> entries = new CopyOnWriteArrayList<>();

        ExecutionContext(String jobId, String executionId, String jobName, PipelineMode pipelineMode,
                         String repositoryConnector, String outputConnector, Instant startTime,
                         ExecutionProvenance provenance) {
            this.jobId = jobId;
            this.executionId = executionId;
            this.jobName = jobName;
            this.pipelineMode = pipelineMode;
            this.repositoryConnector = repositoryConnector;
            this.outputConnector = outputConnector;
            this.startTime = startTime;
            this.provenance = provenance;
        }
    }
}
