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
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.Function;

/**
 * Base for the MCP retrieval backends: every candidate a backend returns goes through the
 * {@link DocumentAccessGate}, so the access decision is made in one place for all stores.
 *
 * <p>The gate is injected by Spring; a backend built directly (tests, programmatic use) falls back to the
 * metadata gate, which reproduces the stored-ACL behaviour.
 */
public abstract class GatedMcpSearchBackend implements McpVectorSearchBackend {

    private DocumentAccessGate accessGate = DocumentAccessGate.metadataDefault();

    @Autowired(required = false)
    public void setAccessGate(DocumentAccessGate accessGate) {
        if (accessGate != null) {
            this.accessGate = accessGate;
        }
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
     * Whether the caller may read a single candidate.
     */
    protected boolean readable(String userPrincipal, List<String> userRoles, ResourceRef ref) {
        return accessGate.canRead(Caller.asserted(userPrincipal, userRoles), ref);
    }
}
