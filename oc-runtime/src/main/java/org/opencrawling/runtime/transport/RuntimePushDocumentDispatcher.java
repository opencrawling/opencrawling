/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.transport;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.push.grpc.GrpcPushProperties;
import org.opencrawling.push.grpc.OisDocumentTranslator;
import org.opencrawling.push.grpc.PushDocumentDispatcher;
import org.opencrawling.push.v1.*;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenCrawling Runtime implementation of {@link PushDocumentDispatcher}.
 * Bridges the gRPC Push ingestion service with {@link JobController} validation,
 * {@link JobOrchestrator} Kafka event bus dispatch, and {@link JobLedgerAccumulator} audit reporting.
 */
@Service
public class RuntimePushDocumentDispatcher implements PushDocumentDispatcher {

    /** Pushed documents carry no caller identity; their executions record only how they arrived. */
    private static final ExecutionProvenance PUSH_PROVENANCE =
            ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_GRPC_PUSH, null);

    private static final Logger log = LoggerFactory.getLogger(RuntimePushDocumentDispatcher.class);

    private final JobController jobController;
    private final JobOrchestrator jobOrchestrator;
    private final ClaimCheckStore claimCheckStore;
    private final GrpcPushProperties properties;
    private final JobLedgerAccumulator ledgerAccumulator;
    private final ConcurrentHashMap<String, List<StreamExecutionTrack>> activeStreams = new ConcurrentHashMap<>();

    public RuntimePushDocumentDispatcher(
            JobController jobController,
            JobOrchestrator jobOrchestrator,
            ClaimCheckStore claimCheckStore,
            GrpcPushProperties properties) {
        this(jobController, jobOrchestrator, claimCheckStore, properties, null);
    }

    @Autowired
    public RuntimePushDocumentDispatcher(
            JobController jobController,
            JobOrchestrator jobOrchestrator,
            ClaimCheckStore claimCheckStore,
            GrpcPushProperties properties,
            @Autowired(required = false) JobLedgerAccumulator ledgerAccumulator) {
        this.jobController = jobController;
        this.jobOrchestrator = jobOrchestrator;
        this.claimCheckStore = claimCheckStore;
        this.properties = properties;
        this.ledgerAccumulator = ledgerAccumulator;
    }

    @Override
    public PushDocumentResponse dispatch(PushDocumentRequest request) {
        return dispatch(request, null);
    }

    @Override
    public PushDocumentResponse dispatch(PushDocumentRequest request, String streamExecutionId) {
        if (request == null) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("PushDocumentRequest payload cannot be null")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        String jobId = request.getJobId();
        if (jobId == null || jobId.isBlank()) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Target job_id is required")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        JobDTO job;
        try {
            ResponseEntity<JobDTO> response = jobController.getJob(jobId);
            job = response != null ? response.getBody() : null;
        } catch (Exception e) {
            log.warn("Failed to retrieve job {} during gRPC push validation: {}", jobId, e.getMessage());
            job = null;
        }

        if (job == null) {
            String docId = request.getDocument() != null ? request.getDocument().getId() : "";
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docId)
                    .setStatus(IngestionStatus.REJECTED_JOB_NOT_FOUND)
                    .setMessage("Target job not found: " + jobId)
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        PipelineMode mode = jobController.resolvePipelineMode(job);
        if (mode != null && mode.isMigration()) {
            String docId = request.getDocument() != null ? request.getDocument().getId() : "";
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docId)
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Target job runs in migration mode; OIS push ingestion supports RAG jobs only")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        OisDocumentPayload docPayload = request.getDocument();
        if (docPayload == null || docPayload.getId() == null || docPayload.getId().isBlank()) {
            return PushDocumentResponse.newBuilder()
                    .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                    .setMessage("Document ID is required in OisDocumentPayload")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }

        long docStartTime = System.currentTimeMillis();
        boolean standalone = (streamExecutionId == null || streamExecutionId.isBlank());
        String repoName = resolveRepositoryConnectorName(job);
        String outName = resolveOutputConnectorName(job);
        String jobName = (job.name() != null && !job.name().isBlank()) ? job.name() : ("Job #" + job.id());
        String model = (job.transformationConnector() != null && !job.transformationConnector().isBlank())
                ? job.transformationConnector()
                : "mxbai-embed-large";

        StreamExecutionTrack streamTrack = null;
        String execId;
        if (standalone) {
            execId = "exec-push-" + UUID.randomUUID().toString().substring(0, 8);
            if (ledgerAccumulator != null) {
                ledgerAccumulator.startExecution(job.id(), execId, jobName, PipelineMode.RAG, repoName, outName, PUSH_PROVENANCE);
            }
        } else {
            streamTrack = getOrCreateStreamTrack(streamExecutionId, job, jobName, repoName, outName, model);
            execId = streamTrack.executionId;
        }

        try {
            int thresholdKb = properties != null ? properties.getInlineBinaryThresholdKb() : 512;
            RepositoryDocument doc = OisDocumentTranslator.translate(
                    docPayload,
                    request.getOptions(),
                    claimCheckStore,
                    thresholdKb
            );

            jobOrchestrator.publishDocument(doc, job.transformationConnector());

            long durationMs = Math.max(1L, System.currentTimeMillis() - docStartTime);
            if (ledgerAccumulator != null) {
                int tokenCount = estimateTokenCount(docPayload, doc);
                int chunkCount = doc.action() == DocumentAction.DELETE ? 0 : Math.max(1, (int) Math.ceil(tokenCount / 500.0));
                ledgerAccumulator.recordDocument(DocumentLedgerEntry.ragSuccess(
                        job.id(),
                        execId,
                        doc.id(),
                        doc.uri() != null ? doc.uri() : doc.id(),
                        repoName,
                        outName,
                        durationMs,
                        chunkCount,
                        tokenCount,
                        model,
                        1024,
                        "vector_store"
                ));
                if (standalone) {
                    ledgerAccumulator.completeExecution(execId, true, Map.of("durationMs", durationMs, "embeddingModel", model));
                }
            }

            if (standalone) {
                jobController.recordPushExecution(job.id(), 1L, true);
            } else if (streamTrack != null) {
                streamTrack.acceptedDocs.incrementAndGet();
            }

            return PushDocumentResponse.newBuilder()
                    .setDocumentId(doc.id())
                    .setStatus(IngestionStatus.ACCEPTED)
                    .setMessage("Document accepted and published to Kafka ingestion pipeline")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - docStartTime);
            if (ledgerAccumulator != null) {
                ledgerAccumulator.recordDocument(DocumentLedgerEntry.failure(
                        job.id(),
                        execId,
                        PipelineMode.RAG,
                        docPayload.getId(),
                        docPayload.getId(),
                        repoName,
                        outName,
                        durationMs,
                        "KAFKA_PUBLISH_ERROR",
                        e.getMessage()
                ));
                if (standalone) {
                    ledgerAccumulator.completeExecution(execId, false, Map.of(
                            "durationMs", durationMs,
                            "embeddingModel", model,
                            "error", e.getMessage() != null ? e.getMessage() : "Ingestion error"
                    ));
                }
            }
            if (standalone) {
                jobController.recordPushExecution(job.id(), 0L, false);
            } else if (streamTrack != null) {
                streamTrack.failedDocs.incrementAndGet();
            }
            log.error("Failed to publish pushed OIS document {} for job {}: {}", docPayload.getId(), jobId, e.getMessage(), e);
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(docPayload.getId())
                    .setStatus(IngestionStatus.ERROR_INTERNAL)
                    .setMessage("Ingestion error: " + e.getMessage())
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        }
    }

    @Override
    public void completeStreamExecution(String streamExecutionId) {
        if (streamExecutionId == null || streamExecutionId.isBlank()) {
            return;
        }
        List<StreamExecutionTrack> tracks = activeStreams.remove(streamExecutionId);
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        for (StreamExecutionTrack track : tracks) {
            long durationMs = Math.max(1L, System.currentTimeMillis() - track.startTimeMs);
            boolean success = track.failedDocs.get() == 0;
            if (ledgerAccumulator != null && ledgerAccumulator.hasActiveExecution(track.executionId)) {
                ledgerAccumulator.completeExecution(track.executionId, success, Map.of(
                        "durationMs", durationMs,
                        "embeddingModel", track.embeddingModel
                ));
            }
            jobController.recordPushExecution(track.jobId, track.acceptedDocs.get(), success);
        }
    }

    private StreamExecutionTrack getOrCreateStreamTrack(
            String streamExecutionId,
            JobDTO job,
            String jobName,
            String repoName,
            String outName,
            String model) {
        List<StreamExecutionTrack> tracks = activeStreams.computeIfAbsent(streamExecutionId, k -> new CopyOnWriteArrayList<>());
        synchronized (tracks) {
            for (StreamExecutionTrack existing : tracks) {
                if (existing.jobId.equals(job.id())) {
                    return existing;
                }
            }
            String execId = tracks.isEmpty()
                    ? streamExecutionId
                    : streamExecutionId + "-" + job.id().replaceAll("[^a-zA-Z0-9_-]", "");
            if (ledgerAccumulator != null && !ledgerAccumulator.hasActiveExecution(execId)) {
                ledgerAccumulator.startExecution(job.id(), execId, jobName, PipelineMode.RAG, repoName, outName, PUSH_PROVENANCE);
            }
            StreamExecutionTrack created = new StreamExecutionTrack(job.id(), execId, model, System.currentTimeMillis());
            tracks.add(created);
            return created;
        }
    }

    private static String resolveRepositoryConnectorName(JobDTO job) {
        if (job != null && job.repositoryConnector() != null && !job.repositoryConnector().isBlank()
                && !job.repositoryConnector().equalsIgnoreCase("FileSystem_Local")) {
            return job.repositoryConnector();
        }
        return "GrpcPushConnector";
    }

    private static String resolveOutputConnectorName(JobDTO job) {
        if (job != null && job.outputConnector() != null && !job.outputConnector().isBlank()) {
            return job.outputConnector();
        }
        return "PGVector_Output";
    }

    private static int estimateTokenCount(OisDocumentPayload payload, RepositoryDocument doc) {
        if (doc.action() == DocumentAction.DELETE) {
            return 0;
        }
        if (payload.getContentPayloadCase() == OisDocumentPayload.ContentPayloadCase.TEXT_CONTENT) {
            return Math.max(1, payload.getTextContent().length() / 4);
        }
        if (payload.getContentPayloadCase() == OisDocumentPayload.ContentPayloadCase.RAW_BYTES) {
            return Math.max(1, payload.getRawBytes().size() / 4);
        }
        return 100;
    }

    private static final class StreamExecutionTrack {
        final String jobId;
        final String executionId;
        final String embeddingModel;
        final long startTimeMs;
        final AtomicLong acceptedDocs = new AtomicLong(0);
        final AtomicLong failedDocs = new AtomicLong(0);

        StreamExecutionTrack(String jobId, String executionId, String embeddingModel, long startTimeMs) {
            this.jobId = jobId;
            this.executionId = executionId;
            this.embeddingModel = embeddingModel;
            this.startTimeMs = startTimeMs;
        }
    }
}
