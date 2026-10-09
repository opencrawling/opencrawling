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
package org.opencrawling.runtime.orchestrator;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.MustacheTransformationConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.result.ScanResult;
import org.opencrawling.runtime.api.JobController.NarrativizationConfig;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.claimcheck.CompositeClaimCheckStore;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

@Service
public class JobOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ClaimCheckStore claimCheckStore;
    private final TelemetryTraceStore traceStore;

    @Autowired(required = false)
    private PipelineProperties pipelineProperties;

    @Value("${opencrawling.pipeline.mode:rag}")
    private String defaultPipelineMode = "rag";

    @Value("${spring.ai.ollama.embedding.options.model:mxbai-embed-large}")
    private String defaultOllamaModel = "mxbai-embed-large";

    /**
     * Parallel lanes used by the standalone crawler to externalize content (claim check) and publish documents
     * to Kafka. Documents are assigned to lanes by id, so per-document ordering is preserved. {@code 1} restores
     * fully sequential processing. Ignored (always sequential) when a direct OutputConnector is used, or when the
     * repository connector does not opt in via {@link RepositoryConnector#supportsConcurrentProcessing()}.
     */
    @Value("${spring.opencrawling.crawler.concurrency:4}")
    private int crawlerConcurrency = 4;

    public JobOrchestrator(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore,
            TelemetryTraceStore traceStore) {
        this.kafkaTemplate = kafkaTemplate;
        this.claimCheckStore = claimCheckStore;
        this.traceStore = traceStore;
    }

    void setCrawlerConcurrency(int crawlerConcurrency) {
        this.crawlerConcurrency = crawlerConcurrency;
    }

    @SuppressWarnings("preview")
    public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path) {
        return runJob(repositoryConnector, outputConnector, path, null);
    }

    @SuppressWarnings("preview")
    public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path, String transformationConnector) {
        return runJob(repositoryConnector, outputConnector, path, transformationConnector, "1");
    }

    @SuppressWarnings("preview")
    public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path, String transformationConnector, String jobId) {
        return runJob(repositoryConnector, outputConnector, path, transformationConnector, jobId, null);
    }

    @SuppressWarnings("preview")
    public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path,
            String transformationConnector, String jobId, NarrativizationConfig narrativization) {
        return runJob(repositoryConnector, outputConnector, path, transformationConnector, jobId, narrativization, null);
    }

    @SuppressWarnings("preview")
    public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path,
            String transformationConnector, String jobId, NarrativizationConfig narrativization, PipelineMode pipelineMode) {
        
        PipelineMode resolvedMode = pipelineMode != null ? pipelineMode :
            (pipelineProperties != null ? pipelineProperties.getMode() : PipelineMode.fromString(defaultPipelineMode));

        final MustacheTransformationConnector mustacheConnector =
            (resolvedMode != PipelineMode.MIGRATION &&
             narrativization != null && narrativization.enabled() &&
             narrativization.template() != null && !narrativization.template().isBlank())
            ? new MustacheTransformationConnector(narrativization.template())
            : null;

        if (resolvedMode == PipelineMode.MIGRATION) {
            log.info("Job {} running in MIGRATION mode. Narrativization and vector embeddings are bypassed.", jobId);
        } else if (mustacheConnector != null) {
            log.info("Narrativization enabled for job {}. Template preview: {}", jobId,
                narrativization.template().substring(0, Math.min(60, narrativization.template().length())));
        }

        return runJobInternal(repositoryConnector, outputConnector, path, transformationConnector, jobId, mustacheConnector, resolvedMode);
    }

    @SuppressWarnings("preview")
    private boolean runJobInternal(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path,
            String transformationConnector, String jobId, MustacheTransformationConnector mustacheConnector, PipelineMode pipelineMode) {
        log.info("Starting job {} for path: {} with transformation connector: {} (pipeline mode: {})", jobId, path, transformationConnector, pipelineMode);
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String currentJobId = jobId != null ? jobId : "1";
        
        Transformation transformation = resolveTransformation(transformationConnector);
        final String finalEngine = transformation.engine();
        final java.util.Map<String, String> finalConfig = transformation.config();

        try (var scope = StructuredTaskScope.open()) {
            
            StructuredTaskScope.Subtask<List<ScanResult>> scanTask = scope.fork(org.opencrawling.observability.concurrency.ObservabilityTask.observed(() -> {
                Function<RepositoryDocument, ScanResult> processDocument = initialDoc -> {
                        try {
                            RepositoryDocument doc = initialDoc;
                            if (mustacheConnector != null) {
                                try {
                                    boolean isBlob = initialDoc.metadata() != null &&
                                        Boolean.parseBoolean(initialDoc.metadata().getOrDefault("is_blob", List.of("false")).get(0));
                                    if (!isBlob) {
                                        doc = mustacheConnector.transform(initialDoc).blockFirst();
                                        log.debug("Applied Mustache narrativization to tabular document: {}", doc.id());
                                    } else {
                                        log.debug("Preserving raw BLOB/document content stream for direct embedding: {}", initialDoc.id());
                                    }
                                } catch (Exception ex) {
                                    log.warn("Mustache transformation failed for doc {}: {}", initialDoc.id(), ex.getMessage());
                                }
                            }

                            String finalUri = doc.uri();
                            boolean isLocalFileUri = finalUri != null && finalUri.startsWith("file:");
                            boolean isSupportedByPrimary = claimCheckStore instanceof CompositeClaimCheckStore composite
                                    ? composite.getPrimaryStore().supports(URI.create(finalUri))
                                    : claimCheckStore.supports(URI.create(finalUri));

                            // Save stream via ClaimCheckStore if remote stream OR primary store requires non-local persistence
                            if (doc.action() != DocumentAction.DELETE && doc.contentStream() != null && (!isLocalFileUri || !isSupportedByPrimary)) {
                                String resolvedName = doc.metadata().getOrDefault("filename", doc.metadata().getOrDefault("fileName", List.of())).stream().findFirst().orElse(null);
                                String filename;
                                if (resolvedName != null && !resolvedName.isBlank()) {
                                    filename = doc.id() + "_" + resolvedName.replaceAll("[^a-zA-Z0-9.-]", "_");
                                } else {
                                    filename = doc.id() + "_" + doc.metadata().getOrDefault("name", List.of("document")).get(0).replaceAll("[^a-zA-Z0-9.-]", "_");
                                }
                                String mimeType = null;
                                List<String> mimeList = doc.metadata().get("mimeType");
                                if (mimeList != null && !mimeList.isEmpty()) {
                                    mimeType = mimeList.get(0);
                                }
                                
                                try (InputStream in = doc.contentStream()) {
                                    if (in != null) {
                                        URI claimUri = claimCheckStore.put(filename, in, -1, mimeType);
                                        finalUri = claimUri.toString();
                                        log.info("Saved document content to Claim Check store: {}", finalUri);
                                    } else {
                                        log.warn("Document content stream is null for id: {}", doc.id());
                                    }
                                } catch (Exception e) {
                                    // The content could not be externalized, so downstream consumers would only receive
                                    // a reference they cannot resolve (e.g. a crawler-local file: URI). Do not publish
                                    // or ingest a dangling reference: report the document as failed instead.
                                    log.error("Failed to write Claim Check content for doc: {}. Document will not be published.", doc.id(), e);
                                    traceStore.recordError(currentJobId, "ERROR", "ClaimCheckStore", "Failed to write Claim Check content for doc: " + doc.id(), e.toString());
                                    return new ScanResult.Failure(doc.id(), e);
                                }
                            }

                            IngestionMessage msg = new IngestionMessage(
                                doc.id(),
                                finalUri,
                                doc.metadata(),
                                doc.acl(),
                                doc.security(),
                                doc.lastModified().toString(),
                                transformationConnector,
                                finalEngine,
                                finalConfig,
                                doc.action(),
                                pipelineMode
                            );
                            
                            // Publish document metadata to Kafka topic and wait for confirmation
                            try {
                                kafkaTemplate.send(KafkaConfig.TOPIC_NAME, doc.id(), msg).get();
                                log.info("Published document reference to Kafka: {}", doc.id());
                            } catch (Exception kafkaEx) {
                                if (outputConnector == null) {
                                    // Standalone crawler: Kafka is the only delivery path, losing the message means losing the document.
                                    log.error("Kafka publish failed for doc {}: {}", doc.id(), kafkaEx.getMessage());
                                    traceStore.recordError(currentJobId, "ERROR", "Kafka", "Kafka publish failed for doc: " + doc.id(), kafkaEx.toString());
                                    return new ScanResult.Failure(doc.id(), kafkaEx);
                                }
                                log.warn("Kafka publish skipped or unavailable for doc {}: {}", doc.id(), kafkaEx.getMessage());
                            }
                            
                            // Direct ingestion to OutputConnector for synchronous application runtime
                            if (outputConnector != null) {
                                try {
                                    RepositoryDocument outputDoc = doc;
                                    if (doc.contentStream() != null && !finalUri.equals(doc.uri())) {
                                        try {
                                            InputStream freshStream = claimCheckStore.get(URI.create(finalUri));
                                            outputDoc = new RepositoryDocument(
                                                doc.id(),
                                                finalUri,
                                                freshStream,
                                                doc.metadata(),
                                                doc.acl(),
                                                doc.security(),
                                                doc.lastModified(),
                                                doc.action()
                                            );
                                        } catch (Exception ex) {
                                            log.debug("Could not resolve fresh stream from ClaimCheckStore: {}", ex.getMessage());
                                        }
                                    }
                                    outputConnector.send(outputDoc).block();
                                    log.info("Successfully ingested document {} directly into OutputConnector: {}", doc.id(), outputConnector.getName());
                                } catch (Exception outEx) {
                                    log.error("Direct OutputConnector ingestion failed for doc {}: {}", doc.id(), outEx.getMessage(), outEx);
                                }
                            }
                            
                            return new ScanResult.Success(doc.id(), "1.0");
                        } catch (Exception e) {
                            traceStore.recordError(currentJobId, "ERROR", "RepositoryConnector", "Scan failed for doc " + initialDoc.id() + ": " + e.getMessage(), e.toString());
                            return new ScanResult.Failure(initialDoc.id(), e);
                        }
                };

                // Standalone crawler (no direct OutputConnector): externalize and publish documents in parallel lanes.
                // A document always maps to the same lane (hash of its id), so events of one document keep their order,
                // like Kafka partitions. With a direct OutputConnector processing stays sequential, because output
                // connectors are not required to be thread-safe. Repository connectors must opt in: when processing is
                // decoupled the scan runs ahead, and buffered documents must not hold open connections or cursors.
                boolean parallel = outputConnector == null && crawlerConcurrency > 1
                        && repositoryConnector.supportsConcurrentProcessing();
                int lanes = parallel ? crawlerConcurrency : 1;
                if (lanes == 1) {
                    if (outputConnector == null && crawlerConcurrency > 1) {
                        log.info("Job {}: {} does not support concurrent processing, processing documents sequentially",
                                currentJobId, repositoryConnector.getName());
                    }
                    return repositoryConnector.scan(path)
                        .map(processDocument)
                        .collectList()
                        .block();
                }
                log.info("Job {} processing documents with {} parallel lanes", currentJobId, lanes);
                try (ExecutorService laneExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                    Scheduler laneScheduler = Schedulers.fromExecutorService(laneExecutor, "oc-crawl-lanes");
                    return repositoryConnector.scan(path)
                        .groupBy(doc -> Math.floorMod(Objects.hashCode(doc.id()), lanes))
                        .flatMap(lane -> lane.concatMap(doc ->
                                Mono.fromCallable(org.opencrawling.observability.concurrency.ObservabilityTask.observed(
                                        () -> processDocument.apply(doc)))
                                    .subscribeOn(laneScheduler)), lanes)
                        .collectList()
                        .block();
                }
            }));
            
            scope.join();
            
            List<ScanResult> scanResults = scanTask.get();
            scanResults.forEach(r -> log.info(r.summarize()));
            
            long duration = System.currentTimeMillis() - startTime;
            traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                UUID.randomUUID().toString(),
                traceId,
                currentJobId,
                "Scanning",
                repositoryConnector.getClass().getSimpleName(),
                startTime,
                duration,
                "SUCCESS",
                null,
                Map.of("path", path, "scannedCount", String.valueOf(scanResults.size()))
            ));

            log.info("Job scanning phase completed in {} ms. Documents published to Kafka.", duration);
            return true;
        } catch (StructuredTaskScope.FailedException e) {
            log.error("Job execution failed due to subtask failure: ", e.getCause());
            traceStore.recordError(currentJobId, "ERROR", "JobOrchestrator", "Job execution failed due to subtask failure: " + e.getCause().getMessage(), e.toString());
            return false;
        } catch (Exception e) {
            log.error("Job execution failed: ", e);
            traceStore.recordError(currentJobId, "ERROR", "JobOrchestrator", "Job execution failed: " + e.getMessage(), e.toString());
            return false;
        }
    }

    /**
     * Publishes one document that an external producer sent for a job, in RAG mode. An UPSERT stores the
     * document's content, which must not be null, in the claim-check store as {@code text/plain} and publishes
     * a reference to it; a DELETE publishes a tombstone. Returns once Kafka has acknowledged the message.
     *
     * @throws Exception if the content cannot be stored or Kafka does not acknowledge the message
     */
    public void publishDocument(RepositoryDocument doc, String transformationConnector) throws Exception {
        String uri = doc.uri();
        Map<String, List<String>> metadata = doc.metadata();
        if (doc.action() != DocumentAction.DELETE) {
            // A fresh name per message: IngestionConsumer deletes the claim it consumed, which must not be
            // the claim of a newer message for the same document.
            try (InputStream in = doc.contentStream()) {
                uri = claimCheckStore.put(UUID.randomUUID() + ".txt", in, -1, "text/plain").toString();
            }
            // The text extractor picks its parser from these keys; they must describe the stored text.
            metadata = new java.util.HashMap<>(metadata);
            metadata.keySet().removeAll(List.of("mimetype", "Content-Type", "content-type"));
            metadata.put("mimeType", List.of("text/plain"));
        }
        Transformation transformation = resolveTransformation(transformationConnector);
        IngestionMessage message = new IngestionMessage(doc.id(), uri, metadata, doc.acl(), doc.security(),
                doc.lastModified().toString(), transformationConnector, transformation.engine(), transformation.config(),
                doc.action(), PipelineMode.RAG);
        kafkaTemplate.send(KafkaConfig.TOPIC_NAME, doc.id(), message).get();
    }

    /**
     * The embedding engine and its configuration, from the named transformation connector in connectors.json.
     * Without a match, or when connectors.json cannot be read, Ollama with the default embedding model.
     */
    private Transformation resolveTransformation(String transformationConnector) {
        String engine = "ollama";
        java.util.Map<String, String> config = java.util.Map.of("model", defaultOllamaModel != null && !defaultOllamaModel.isBlank() ? defaultOllamaModel : "mxbai-embed-large");

        if (transformationConnector != null && !transformationConnector.isBlank()) {
            try {
                java.util.List<org.opencrawling.runtime.api.ConnectorController.ConnectorDTO> connectors = 
                    org.opencrawling.runtime.api.PersistenceHelper.loadList("connectors.json", org.opencrawling.runtime.api.ConnectorController.ConnectorDTO.class, java.util.List.of());
                for (org.opencrawling.runtime.api.ConnectorController.ConnectorDTO conn : connectors) {
                    if (conn.name().equals(transformationConnector)) {
                        engine = conn.configuration().getOrDefault("engine", "ollama");
                        config = new java.util.HashMap<>(conn.configuration());
                        if (defaultOllamaModel != null && !defaultOllamaModel.isBlank() && "Ollama_Embedding_Default".equals(transformationConnector)) {
                            config.put("model", defaultOllamaModel);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load connector configuration for {}: {}", transformationConnector, e.getMessage());
            }
        }
        return new Transformation(engine, config);
    }

    private record Transformation(String engine, java.util.Map<String, String> config) {
    }

    private String resolveSharedDir() {
        File dockerData = new File("/data");
        if (dockerData.exists() && dockerData.isDirectory() && dockerData.canWrite()) {
            return "/data";
        }
        File localData = new File("data");
        if (!localData.exists()) {
            localData.mkdirs();
        }
        return localData.getAbsolutePath();
    }
}
