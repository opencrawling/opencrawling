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

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.ReadKeys;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'pgvector'")
public class VectorStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreWriterConsumer.class);
    
    private final PgVectorStore vectorStore;
    private final PgVectorStore vectorStore384;
    private final PgVectorStore vectorStore768;
    private final PgVectorStore vectorStore1024;
    private final TelemetryTraceStore traceStore;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final boolean flowableCollocatedSchema;

    @org.springframework.beans.factory.annotation.Autowired
    public VectorStoreWriterConsumer(
            @org.springframework.beans.factory.annotation.Qualifier("vectorStore") PgVectorStore vectorStore,
            @org.springframework.beans.factory.annotation.Qualifier("vectorStore384") PgVectorStore vectorStore384,
            @org.springframework.beans.factory.annotation.Qualifier("vectorStore768") PgVectorStore vectorStore768,
            @org.springframework.beans.factory.annotation.Qualifier("vectorStore1024") PgVectorStore vectorStore1024,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TelemetryTraceStore traceStore,
            @org.springframework.beans.factory.annotation.Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("pgVectorJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value("${spring.opencrawling.output.pgvector.flowable-collocated-schema:false}") boolean flowableCollocatedSchema) {
        this.vectorStore = vectorStore;
        this.vectorStore384 = vectorStore384;
        this.vectorStore768 = vectorStore768;
        this.vectorStore1024 = vectorStore1024;
        this.traceStore = traceStore;
        this.jdbcTemplate = jdbcTemplate;
        this.flowableCollocatedSchema = flowableCollocatedSchema;
    }

    public VectorStoreWriterConsumer(
            PgVectorStore vectorStore,
            PgVectorStore vectorStore384,
            PgVectorStore vectorStore768,
            PgVectorStore vectorStore1024,
            TelemetryTraceStore traceStore) {
        this(vectorStore, vectorStore384, vectorStore768, vectorStore1024, traceStore, null, false);
    }

    /**
     * Stores one embedded chunk, or purges a document.
     *
     * <p>A chunk is upserted under its chunk id into the store matching its embedding dimensions. The chunk with
     * {@code chunk_index} 0 also deletes, from that store, the rows of the same {@code documentId} whose
     * {@code chunk_index} is at or past its {@code total_chunks}. A DELETE removes every row whose
     * {@code documentId} metadata equals the message's document id, from all four stores.
     */
    @KafkaListener(
            topics = KafkaConfig.EMBEDDED_TOPIC_NAME,
            groupId = "${spring.kafka.consumer.writer-group-id:opencrawling-vector-group}")
    public void consume(DocumentEmbeddedMessage message) {
        log.info("Received embedded chunk for storage: {} (Dimensions: {})", message.chunkId(), 
            message.embedding() != null ? message.embedding().length : 0);
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String jobId = message.documentId();

        try {
            if (message.action() == DocumentAction.DELETE) {
                log.info("Received DELETE tombstone for document: {}. Purging document/chunks from pgvector store.", message.documentId());
                // Chunks are stored under their own ids; the document id is in their metadata.
                Filter.Expression byDocument = new FilterExpressionBuilder().eq("documentId", message.documentId()).build();
                vectorStore.delete(byDocument);
                vectorStore384.delete(byDocument);
                vectorStore768.delete(byDocument);
                vectorStore1024.delete(byDocument);
                log.info("Successfully purged tombstone document {} from pgvector stores.", message.documentId());
                return;
            }
            Map<String, Object> metadata = new HashMap<>(message.metadata());
            ensureSecurityMetadata(metadata);
            // Put the precomputed embedding into the metadata so PrecomputedEmbeddingModel can extract it
            metadata.put("embedding", message.embedding());
            
            Document doc = new Document(message.chunkId(), message.text(), metadata);
            
            // Route to correct vector store dynamically based on embedding vector dimensions
            PgVectorStore targetStore = vectorStore;
            if (message.embedding() != null) {
                int len = message.embedding().length;
                targetStore = switch (len) {
                    case 384 -> vectorStore384;
                    case 768 -> vectorStore768;
                    case 1024 -> vectorStore1024;
                    default -> vectorStore;
                };
            }
            
            try {
                if (message.embedding() != null) {
                    PrecomputedEmbeddingModel.setPrecomputedVector(message.text(), message.embedding());
                }
                targetStore.add(List.of(doc));
            } finally {
                PrecomputedEmbeddingModel.clear();
            }

            if (flowableCollocatedSchema && jdbcTemplate != null) {
                updateFlowableCollocatedColumns(message.chunkId(), message.embedding() != null ? message.embedding().length : null, metadata);
            }
            // A re-crawled document that got shorter keeps rows past its new length; its first chunk removes them.
            if (message.metadata().get("chunk_index") instanceof Number index && index.intValue() == 0
                    && message.metadata().get("total_chunks") instanceof Number total) {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                targetStore.delete(b.and(b.eq("documentId", message.documentId()), b.gte("chunk_index", total.intValue())).build());
            }
            long duration = System.currentTimeMillis() - startTime;

            if (traceStore != null) {
                traceStore.recordSpan(new TelemetryTraceStore.SpanRecord(
                        UUID.randomUUID().toString(), traceId, jobId, "Indexing", "VectorStoreWriterConsumer",
                        startTime, duration, "SUCCESS", null, Map.of("db", "pgvector", "chunkId", message.chunkId())
                ));
            }

            log.info("Successfully saved chunk {} to Vector Store in {} ms.", message.chunkId(), duration);
        } catch (Exception e) {
            log.error("Failed to store embedded chunk: {}", message.chunkId(), e);
            if (traceStore != null) {
                traceStore.recordError(jobId, "ERROR", "VectorStoreWriterConsumer", "Failed to store embedded chunk: " + message.chunkId() + " - " + e.getMessage(), e.toString());
            }
            // Rethrown so the container's error handler retries the chunk, then dead-letters it. Chunks are upserted
            // under their own ids and a DELETE removes by document id, so retrying either is safe.
            throw new IllegalStateException("pgvector write failed for chunk " + message.chunkId(), e);
        }
    }

    /** The permission fields a chunk is stored with, recomputed from its {@code security} and {@code acl}. */
    static void ensureSecurityMetadata(Map<String, Object> metadata) {
        // Recomputed from the security object and the legacy acl even when the ingestion step already set the
        // flattened fields: EffectiveAcl is idempotent, and this keeps chunks from older producers consistent.
        EffectiveAcl effectiveAcl = EffectiveAcl.resolve(metadata.get("security"), metadata.get("acl"));
        if (effectiveAcl.security() != null) {
            metadata.put("security", effectiveAcl.security());
        }
        metadata.put("security_inheritance", effectiveAcl.inheritanceEnabled());
        metadata.put("security_allowed_read", effectiveAcl.allowedRead());
        metadata.put("security_denied_read", effectiveAcl.deniedRead());
        metadata.put(ReadKeys.FIELD, ReadKeys.forAllowed(effectiveAcl.allowedRead()));
    }

    private String getTableName(Integer dimensions) {
        if (dimensions == null) return "vector_store";
        return switch (dimensions) {
            case 384 -> "vector_store_384";
            case 768 -> "vector_store_768";
            case 1024 -> "vector_store_1024";
            default -> "vector_store";
        };
    }

    private void updateFlowableCollocatedColumns(String chunkId, Integer dimensions, Map<String, Object> metadata) {
        if (!flowableCollocatedSchema || jdbcTemplate == null || metadata == null) {
            return;
        }

        String scopeId = null;
        if (metadata.get("flowable_scope_id") instanceof List<?> list && !list.isEmpty()) {
            scopeId = String.valueOf(list.get(0));
        } else if (metadata.get("flowable_scope_id") != null) {
            scopeId = String.valueOf(metadata.get("flowable_scope_id"));
        } else if (metadata.get("flowable_proc_inst_id") instanceof List<?> list && !list.isEmpty()) {
            scopeId = String.valueOf(list.get(0));
        } else if (metadata.get("flowable_proc_inst_id") != null) {
            scopeId = String.valueOf(metadata.get("flowable_proc_inst_id"));
        }

        String scopeType = null;
        if (metadata.get("flowable_scope_type") instanceof List<?> list && !list.isEmpty()) {
            scopeType = String.valueOf(list.get(0));
        } else if (metadata.get("flowable_scope_type") != null) {
            scopeType = String.valueOf(metadata.get("flowable_scope_type"));
        }

        if (scopeId != null && scopeType != null) {
            String tableName = getTableName(dimensions);
            try {
                jdbcTemplate.update("UPDATE " + tableName + " SET scope_id_ = ?, scope_type_ = ? WHERE id = ?::uuid", scopeId, scopeType, chunkId);
            } catch (Exception e) {
                try {
                    jdbcTemplate.update("UPDATE " + tableName + " SET scope_id_ = ?, scope_type_ = ? WHERE id = ?", scopeId, scopeType, chunkId);
                } catch (Exception ex) {
                    log.warn("Failed to populate dedicated Flowable columns on {}: {}", tableName, ex.getMessage());
                }
            }
            log.info("Updated dedicated Flowable columns (scope_id: {}, scope_type: {}) for chunk {} in {}", scopeId, scopeType, chunkId, tableName);
        }
    }
}
