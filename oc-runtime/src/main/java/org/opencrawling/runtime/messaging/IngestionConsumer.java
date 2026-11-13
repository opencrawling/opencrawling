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
package org.opencrawling.runtime.messaging;

import org.opencrawling.core.ois.OisMetadata;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.core.text.TextExtractionResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.governance.GovernanceGate;
import org.opencrawling.runtime.observability.TelemetryTraceStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final int MAX_CACHED_SPLITTERS = 64;

    private final TokenTextSplitter textSplitter;
    private final Map<ChunkingSpec, TokenTextSplitter> splitters = new java.util.concurrent.ConcurrentHashMap<>();
    private final TextExtractionService textExtractionService;
    private final ClaimCheckStore claimCheckStore;
    private final ClaimCheckProperties claimCheckProperties;
    private final TelemetryTraceStore traceStore;
    private GovernanceGate governanceGate;

    public IngestionConsumer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore,
            ClaimCheckProperties claimCheckProperties,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TelemetryTraceStore traceStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TextExtractionService textExtractionService) {
        this.kafkaTemplate = kafkaTemplate;
        this.claimCheckStore = claimCheckStore;
        this.claimCheckProperties = claimCheckProperties;
        this.traceStore = traceStore;
        this.textSplitter = TokenTextSplitter.builder().build();
        this.textExtractionService = textExtractionService != null ? textExtractionService : TextExtractionService.noop();
    }

    /**
     * Turns the pre-embedding governance gate on: every UPSERT then goes through it before it is chunked. Without a
     * gate (the default), documents are chunked as they come.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGovernanceGate(GovernanceGate governanceGate) {
        this.governanceGate = governanceGate;
    }

    /**
     * The splitter for a message: the default one, or one built from the job's chunking. Chunking outside the limits
     * no job may exceed is ignored (with a warning) rather than failing the document; the producers check it first.
     */
    TokenTextSplitter splitterFor(IngestionMessage message) {
        ChunkingSpec chunking = message.chunking();
        if (chunking == null || chunking.isEmpty()) {
            return textSplitter;
        }
        if (!chunking.isWithinLimits()) {
            log.warn("Document {} asks for chunking outside the allowed limits ({}); using the default chunking.",
                    message.documentId(), chunking);
            return textSplitter;
        }
        TokenTextSplitter cached = splitters.get(chunking);
        if (cached != null) {
            return cached;
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunking.effectiveChunkSize())
                .withMinChunkSizeChars(chunking.effectiveMinChunkSizeChars())
                .withMinChunkLengthToEmbed(chunking.effectiveMinChunkLengthToEmbed())
                .withKeepSeparator(chunking.effectiveKeepSeparator())
                .build();
        // A few distinct settings are expected; past the bound, splitters are built per document instead.
        if (splitters.size() < MAX_CACHED_SPLITTERS) {
            splitters.putIfAbsent(chunking, splitter);
        }
        return splitter;
    }

    private static Map<String, String> chunkingAttributes(IngestionMessage message, int chunkCount) {
        ChunkingSpec chunking = message.chunking();
        if (chunking == null) {
            return Map.of("chunkCount", String.valueOf(chunkCount));
        }
        return Map.of("chunkCount", String.valueOf(chunkCount),
                "chunkSize", String.valueOf(chunking.effectiveChunkSize()));
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("IngestionConsumer initialized successfully with ClaimCheckStore: {}!", claimCheckStore.getClass().getSimpleName());
    }

    @KafkaListener(
            topics = KafkaConfig.TOPIC_NAME,
            groupId = "${spring.kafka.consumer.ingestion-group-id:opencrawling-ingestion-group}")
    public void consume(IngestionMessage message) {
        log.info("Received document message from Kafka: {}", message.documentId());
        if (message.pipelineMode() != null && message.pipelineMode().isMigration()) {
            log.info("Document {} received in MIGRATION mode. Skipping Tika extraction, chunking, and embedding.", message.documentId());
            return;
        }
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String jobId = message.documentId();

        try {
            if (IngestionMessage.isGovernanceRejection(message)) {
                // Published by this consumer when governance rejected the document; its chunks were already removed
                // then. Forwarding it again could remove a later version's chunks, so it is meant for the other readers.
                log.debug("Skipping the governance rejection notice for document {}.", message.documentId());
                return;
            }
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for document: {}. Forwarding tombstone to chunks topic.", message.documentId());
                publishTombstone(message);
                return;
            }

            URI fileUri = URI.create(message.uri());
            
            // Resolve stream via ClaimCheckStore (supports local filesystem, Apache Ozone, S3, etc.) or HTTP
            byte[] contentBytes;
            if ("http".equalsIgnoreCase(fileUri.getScheme()) || "https".equalsIgnoreCase(fileUri.getScheme())) {
                try (InputStream httpStream = fileUri.toURL().openStream()) {
                    contentBytes = httpStream.readAllBytes();
                } catch (Exception e) {
                    log.warn("Failed to read HTTP stream directly for {}: {}", fileUri, e.getMessage());
                    contentBytes = new byte[0];
                }
            } else {
                try (InputStream contentStream = claimCheckStore.get(fileUri)) {
                    contentBytes = contentStream.readAllBytes();
                }
            }
                
                if (contentBytes.length == 0) {
                    log.warn("Document {} content is empty, skipping chunking.", message.documentId());
                    return;
                }

                // Extract raw text using Apache Tika via TextExtractionService (isolated in child process if configured)
                TextExtractionResult extractionResult = textExtractionService.extractText(contentBytes, message.metadata());
                String text = extractionResult.text();

                if (!extractionResult.success()) {
                    log.warn("Tika text extraction failed for document {}: {}", message.documentId(), extractionResult.errorMessage());
                }

                if (extractionResult.trimmed()) {
                    log.info("Document {} text was trimmed during extraction.", message.documentId());
                }

                if (text.isBlank()) {
                    boolean isBlob = message.metadata() != null &&
                        (Boolean.parseBoolean(message.metadata().getOrDefault("is_blob", List.of("false")).get(0))
                         || "image".equals(message.metadata().getOrDefault("media_type", List.of("")).get(0))
                         || message.metadata().getOrDefault("mimeType", List.of("")).stream().anyMatch(m -> m.startsWith("image/")));

                    if (isBlob) {
                        String title = message.metadata().getOrDefault("title", message.metadata().getOrDefault("name", List.of())).stream().findFirst().orElse("");
                        String filename = message.metadata().getOrDefault("filename", List.of()).stream().findFirst().orElse("");
                        String mime = message.metadata().getOrDefault("mimeType", List.of()).stream().findFirst().orElse("image/binary");
                        StringBuilder desc = new StringBuilder();
                        desc.append("# Binary Item: ").append(message.documentId());
                        if (!filename.isBlank()) desc.append(" (").append(filename).append(")");
                        desc.append("\n\nType: ").append(mime);
                        if (!title.isBlank()) desc.append("\nTitle: ").append(title);
                        desc.append("\n\nAttributes:\n");
                        message.metadata().forEach((k, vals) -> {
                            if (!k.startsWith("tk:") && !k.equals("documentId") && !k.equals("uri") && !k.equals("acl") && !k.equals("security") && !k.equals("is_blob") && !k.equals("has_blob")) {
                                desc.append("- **").append(k).append("**: ").append(String.join("; ", vals)).append("\n");
                            }
                        });
                        text = desc.toString();
                        log.info("Generated descriptive metadata text for BLOB item {} (MIME: {}) for direct embedding.", message.documentId(), mime);
                    } else {
                        log.warn("Document {} extracted text is empty, skipping.", message.documentId());
                        return;
                    }
                }

                long extractTime = System.currentTimeMillis() - startTime;
                if (traceStore != null) {
                    traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                            UUID.randomUUID().toString(), traceId, jobId, "Extracting", "TikaExtractor",
                            startTime, extractTime, "SUCCESS", null, Map.of("length", String.valueOf(text.length()))
                    ));
                }

                log.info("Extracted {} characters from document: {}", text.length(), message.documentId());

                // Only the gate below writes ois:governance; whatever the document brought under it is dropped.
                Map<String, List<String>> sourceMetadata = OisMetadata.withoutGovernance(message.metadata());
                if (governanceGate != null) {
                    GovernanceGate.Result governed = govern(message, text, sourceMetadata, traceId, jobId);
                    if (!governed.embeds()) {
                        if (governed.status() == GovernanceGate.Status.REJECTED) {
                            cleanupClaim(fileUri);
                        }
                        return;
                    }
                    text = governed.text();
                    sourceMetadata = governanceRecord(governed, governed.metadata());
                }

                // Map repository metadata to Vector Document metadata
                Map<String, Object> metadata = new HashMap<>();
                sourceMetadata.forEach((key, val) -> {
                    if (val != null) {
                        List<String> cleanedList = new ArrayList<>();
                        for (String s : val) {
                            if (s != null) {
                                cleanedList.add(s.replace("\u0000", ""));
                            }
                        }
                        metadata.put(key, cleanedList);
                    }
                });
                metadata.put("documentId", message.documentId());
                metadata.put("uri", message.uri());
                metadata.put("acl", message.acl());
                EffectiveAcl effectiveAcl = EffectiveAcl.resolve(message.security(), message.acl());
                metadata.put("security", effectiveAcl.security());
                metadata.put("lastModified", message.lastModified());

                // Flattened security fields for role-based and zero-trust pre-filtering
                metadata.put("security_inheritance", effectiveAcl.inheritanceEnabled());
                metadata.put("security_allowed_read", effectiveAcl.allowedRead());
                metadata.put("security_denied_read", effectiveAcl.deniedRead());
                
                // Construct Spring AI Document
                Document aiDoc = new Document(message.documentId(), text, metadata);
                
                // Chunk the document using TokenTextSplitter, with the job's own settings when it has any
                long chunkStart = System.currentTimeMillis();
                List<Document> chunks = splitterFor(message).apply(List.of(aiDoc));
                long chunkTime = System.currentTimeMillis() - chunkStart;

                if (traceStore != null) {
                    traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                            UUID.randomUUID().toString(), traceId, jobId, "Chunking", "TokenTextSplitter",
                            chunkStart, chunkTime, "SUCCESS", null, chunkingAttributes(message, chunks.size())
                    ));
                }

                log.info("Split document {} into {} chunks. Publishing to Kafka topic: {}", 
                    message.documentId(), chunks.size(), KafkaConfig.CHUNKS_TOPIC_NAME);
                
                // Chunk ids derive from the document id and the chunk position, so a re-crawl rewrites the same rows.
                // The position equals the splitter's chunk_index, which the writer uses to drop the rows past a
                // shorter document's new length. A document that becomes empty publishes no chunk and keeps its rows.
                // Chunks are keyed by document id, like the document's DELETE, so the writers apply them in order.
                for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                    Document chunk = chunks.get(chunkIndex);
                    String chunkId = UUID.nameUUIDFromBytes(
                            (message.documentId() + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
                    DocumentChunkMessage chunkMsg = new DocumentChunkMessage(
                        message.documentId(),
                        chunkId,
                        chunk.getText(),
                        chunk.getMetadata(),
                        message.transformationConnector(),
                        message.transformationEngine(),
                        message.transformationConfig()
                    );
                    kafkaTemplate.send(KafkaConfig.CHUNKS_TOPIC_NAME, chunkMsg.partitionKey(), chunkMsg).get();
                }
                log.info("Successfully published all chunks for document: {}", message.documentId());

            cleanupClaim(fileUri);
        } catch (java.nio.file.NoSuchFileException e) {
            // Retrying cannot bring the content back, so this one is skipped rather than retried.
            log.warn("Ingestion file no longer exists (possibly a temporary test file): {}", message.uri());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to process Kafka ingestion message: {}", message.documentId(), e);
            if (traceStore != null) {
                traceStore.recordError(jobId, "ERROR", "IngestionConsumer", "Failed to process Kafka ingestion message: " + e.getMessage(), e.toString());
            }
            // Rethrown so the container's error handler retries the message, then dead-letters it. A retry republishes
            // the same chunk ids, and the claim is only cleaned up after a successful run, so retrying is safe.
            throw new IllegalStateException("Ingestion failed for document " + message.documentId(), e);
        }
    }

    /**
     * The metadata with the OIS {@code ois:governance} block for the gate's decision: {@code ALLOW} for an approved
     * document, {@code REDACT} for a sanitized one, with the categories the hooks reported masking
     * ({@code <hook>.masked.<category>} audit facts) and the hooks that ran as the policy.
     */
    Map<String, List<String>> governanceRecord(GovernanceGate.Result governed, Map<String, List<String>> metadata) {
        OisMetadata.Decision decision = governed.status() == GovernanceGate.Status.SANITIZED
                ? OisMetadata.Decision.REDACT : OisMetadata.Decision.ALLOW;
        java.util.Set<String> categories = new java.util.TreeSet<>();
        governed.audit().forEach((key, value) -> {
            int at = key.indexOf(".masked.");
            if (at > 0 && !"0".equals(value)) {
                categories.add(key.substring(at + ".masked.".length()).toUpperCase(java.util.Locale.ROOT));
            }
        });
        return OisMetadata.withGovernance(metadata, decision, categories,
                String.join(",", governanceGate.hookNames()), java.time.Instant.now());
    }

    /**
     * Runs the governance gate. A rejected document is not embedded and, unless the gate keeps rejected updates, its
     * earlier chunks are removed with a tombstone and a {@link IngestionMessage#governanceRejection governance
     * rejection} goes to the documents topic, so the authorization sync removes its permissions too. When the gate cannot decide, nothing is embedded or removed.
     */
    private GovernanceGate.Result govern(IngestionMessage message, String text, Map<String, List<String>> metadata,
                                         String traceId, String jobId) throws Exception {
        long start = System.currentTimeMillis();
        GovernanceGate.Result result = governanceGate.evaluate(message.documentId(), message.uri(), text, metadata,
                message.security());
        long took = System.currentTimeMillis() - start;
        Map<String, String> attributes = new java.util.LinkedHashMap<>(result.audit());
        attributes.put("outcome", result.status().name());
        if (result.decidedBy() != null) {
            attributes.put("decidedBy", result.decidedBy());
        }
        switch (result.status()) {
            case APPROVED, SANITIZED -> log.info("Governance {} document {}", result.status(), message.documentId());
            case REJECTED -> {
                boolean purge = governanceGate.purgeOnReject();
                attributes.put("earlierVersion", purge ? "purged" : "kept");
                log.warn("Governance hook {} rejected document {}: {}. Earlier chunks {}.", result.decidedBy(),
                        message.documentId(), result.reason(), purge ? "are removed" : "are kept");
                if (purge) {
                    publishTombstone(message);
                    // Tell the other readers of the documents topic, so the OpenFGA sync removes the permissions it
                    // wrote for this version. Same key, so it follows that version on its partition.
                    kafkaTemplate.send(KafkaConfig.TOPIC_NAME, message.documentId(),
                            IngestionMessage.governanceRejection(message)).get();
                }
            }
            case UNAVAILABLE -> {
                log.error("Governance could not decide on document {} ({}: {}); it is not embedded.",
                        message.documentId(), result.decidedBy(), result.reason());
                if (traceStore != null) {
                    traceStore.recordError(jobId, "ERROR", "Governance", "Governance hook " + result.decidedBy()
                            + " could not decide on document " + message.documentId() + ": " + result.reason(), null);
                }
            }
        }
        if (traceStore != null) {
            traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                    UUID.randomUUID().toString(), traceId, jobId, "Governance", "PreEmbeddingGovernance",
                    start, took, result.status().name(), result.reason(), attributes));
        }
        return result;
    }

    /** Publishes a DELETE for the document's chunks; the writer removes every chunk stored under its id. */
    private void publishTombstone(IngestionMessage message) throws Exception {
        DocumentChunkMessage deleteChunkMsg = new DocumentChunkMessage(
            message.documentId(),
            message.documentId(),
            "",
            message.metadata() != null ? new HashMap<>(message.metadata()) : Map.of(),
            message.transformationConnector(),
            message.transformationEngine(),
            message.transformationConfig(),
            DocumentAction.DELETE
        );
        kafkaTemplate.send(KafkaConfig.CHUNKS_TOPIC_NAME, deleteChunkMsg.partitionKey(), deleteChunkMsg).get();
    }

    private void cleanupClaim(URI fileUri) {
        if (claimCheckProperties.isCleanupOnConsume() && !"http".equalsIgnoreCase(fileUri.getScheme()) && !"https".equalsIgnoreCase(fileUri.getScheme())) {
            try {
                claimCheckStore.delete(fileUri);
            } catch (Exception e) {
                log.debug("Cleanup of claim check object {} failed or skipped: {}", fileUri, e.getMessage());
            }
        }
    }
}
