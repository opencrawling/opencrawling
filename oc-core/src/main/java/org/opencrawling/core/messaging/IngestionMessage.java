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
package org.opencrawling.core.messaging;

import java.util.List;
import java.util.Map;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.SecurityConfig;

/**
 * Message payload sent to Kafka to trigger vector store processing or content migration.
 * Follows the Claim Check pattern: carries metadata and URI, consumer pulls content.
 *
 * <p>{@code chunking} is optional: {@code null} (and in messages from producers that predate it) means the
 * ingestion consumer's default chunking.
 */
public record IngestionMessage(
    String documentId,
    String uri,
    Map<String, List<String>> metadata,
    String acl,
    SecurityConfig security,
    String lastModified,
    String transformationConnector,
    String transformationEngine,
    Map<String, String> transformationConfig,
    DocumentAction action,
    PipelineMode pipelineMode,
    ChunkingSpec chunking
) {
    public IngestionMessage {
        if (pipelineMode == null) {
            pipelineMode = PipelineMode.RAG;
        }
        if (security == null) {
            // The source reported no permissions: never public. The acl string alone decides, and a blank one
            // grants no one, as discovery does on the source.
            security = SecurityConfig.none();
        }
        if (action == null) {
            action = DocumentAction.UPSERT;
        }
        if (chunking != null && chunking.isEmpty()) {
            chunking = null;
        }
    }

    public IngestionMessage(
        String documentId,
        String uri,
        Map<String, List<String>> metadata,
        String acl,
        SecurityConfig security,
        String lastModified,
        String transformationConnector,
        String transformationEngine,
        Map<String, String> transformationConfig,
        DocumentAction action,
        PipelineMode pipelineMode
    ) {
        this(documentId, uri, metadata, acl, security, lastModified, transformationConnector, transformationEngine, transformationConfig, action, pipelineMode, null);
    }

    public IngestionMessage(
        String documentId,
        String uri,
        Map<String, List<String>> metadata,
        String acl,
        SecurityConfig security,
        String lastModified,
        String transformationConnector,
        String transformationEngine,
        Map<String, String> transformationConfig,
        DocumentAction action
    ) {
        this(documentId, uri, metadata, acl, security, lastModified, transformationConnector, transformationEngine, transformationConfig, action, PipelineMode.RAG);
    }

    public IngestionMessage(
        String documentId,
        String uri,
        Map<String, List<String>> metadata,
        String acl,
        SecurityConfig security,
        String lastModified,
        String transformationConnector,
        String transformationEngine,
        Map<String, String> transformationConfig
    ) {
        this(documentId, uri, metadata, acl, security, lastModified, transformationConnector, transformationEngine, transformationConfig, DocumentAction.UPSERT, PipelineMode.RAG);
    }

    public IngestionMessage(
        String documentId,
        String uri,
        Map<String, List<String>> metadata,
        String acl,
        String lastModified,
        String transformationConnector,
        String transformationEngine,
        Map<String, String> transformationConfig
    ) {
        this(documentId, uri, metadata, acl, SecurityConfig.createPublic(), lastModified, transformationConnector, transformationEngine, transformationConfig, DocumentAction.UPSERT, PipelineMode.RAG);
    }

    /**
     * Metadata key that marks a DELETE published because the pre-embedding governance gate rejected a version of the
     * document. Its value is {@code "true"}.
     */
    public static final String GOVERNANCE_REJECTED_KEY = "oc:governance-rejected";

    /**
     * The DELETE that tells the other readers of the documents topic that governance rejected {@code rejected}, so
     * nothing of the document is indexed. The OpenFGA sync then removes the document's tuples. It carries the
     * rejected version's {@code lastModified}, so a reader can tell whether a later version was already applied.
     * It is a RAG message, so the migration writers ignore it.
     */
    public static IngestionMessage governanceRejection(IngestionMessage rejected) {
        return new IngestionMessage(rejected.documentId(), rejected.uri(), Map.of(GOVERNANCE_REJECTED_KEY, List.of("true")),
                null, SecurityConfig.none(), rejected.lastModified(), null, null, Map.of(), DocumentAction.DELETE,
                PipelineMode.RAG, null);
    }

    /** Whether {@code message} is a DELETE published by {@link #governanceRejection}. */
    public static boolean isGovernanceRejection(IngestionMessage message) {
        return message != null && message.action() == DocumentAction.DELETE && message.metadata() != null
                && List.of("true").equals(message.metadata().get(GOVERNANCE_REJECTED_KEY));
    }
}
