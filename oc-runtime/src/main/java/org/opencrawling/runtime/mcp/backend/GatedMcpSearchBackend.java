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
package org.opencrawling.runtime.mcp.backend;

import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer.DocumentDetailsResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Base for the MCP retrieval backends: every candidate a backend returns goes through the
 * {@link DocumentAccessGate}, so the access decision is made in one place for all stores.
 *
 * <p>The gate is injected by Spring; a backend built directly (tests, programmatic use) falls back to the
 * metadata gate, which reproduces the stored-ACL behaviour.
 */
public abstract class GatedMcpSearchBackend implements McpVectorSearchBackend {

    /**
     * The most chunks {@code getDocumentContent} joins into one document. It bounds the tool's answer; each store's
     * query fetches one more. Vespa's default query profile allows 400 hits, so stay below that on Vespa.
     */
    public static final int DEFAULT_DOCUMENT_MAX_CHUNKS = 200;

    private DocumentAccessGate accessGate = DocumentAccessGate.metadataDefault();
    private int documentMaxChunks = DEFAULT_DOCUMENT_MAX_CHUNKS;

    @Autowired(required = false)
    public void setAccessGate(DocumentAccessGate accessGate) {
        if (accessGate != null) {
            this.accessGate = accessGate;
        }
    }

    @Autowired(required = false)
    public void setDocumentMaxChunks(
            @Value("${opencrawling.mcp.document-content.max-chunks:" + DEFAULT_DOCUMENT_MAX_CHUNKS + "}") int maxChunks) {
        this.documentMaxChunks = Math.max(1, maxChunks);
    }

    /** How many chunks a document lookup asks its store for. */
    protected int documentFetchSize() {
        return DocumentChunks.fetchSize(documentMaxChunks);
    }

    /**
     * The document made of the chunks the caller may read, joined in order.
     *
     * @param readableChunks the lookup's chunks that passed {@link #readable}
     * @param requested      the URI or id the caller asked for
     * @param fetched        how many chunks the store returned for the lookup, readable or not
     */
    protected Optional<DocumentDetailsResult> assembleDocument(List<DocumentChunks.Chunk> readableChunks,
                                                               String requested, int fetched) {
        return DocumentChunks.assemble(readableChunks, requested, documentMaxChunks, fetched >= documentFetchSize());
    }

    protected DocumentAccessGate accessGate() {
        return accessGate;
    }

    /**
     * Keeps the candidates the caller may read, in their original order.
     */
    protected <T> List<T> readable(String userPrincipal, List<String> userRoles, List<T> candidates,
                                   Function<T, ResourceRef> toRef) {
        return accessGate.filter(Caller.asserted(userPrincipal, userRoles), candidates, toRef);
    }

    /**
     * How many candidates to fetch for {@code limit} results; larger when the provider may reject stored-ACL
     * matches. {@code metadataWindow} is what the backend fetches when stored metadata decides.
     */
    protected int candidateWindow(int limit, int metadataWindow) {
        return accessGate.candidateWindow(limit, metadataWindow);
    }

    /**
     * Whether the backend may filter by stored ACL metadata inside its own query.
     */
    protected boolean storePrefilterAllowed() {
        return accessGate.storePrefilterAllowed();
    }

    /**
     * Whether the caller may read a single candidate.
     */
    protected boolean readable(String userPrincipal, List<String> userRoles, ResourceRef ref) {
        return accessGate.canRead(Caller.asserted(userPrincipal, userRoles), ref);
    }
}
