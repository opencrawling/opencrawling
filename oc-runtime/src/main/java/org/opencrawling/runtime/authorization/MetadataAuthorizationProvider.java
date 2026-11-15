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
package org.opencrawling.runtime.authorization;

import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpAclEvaluator;

import java.util.List;

/**
 * The default provider: decides from the ACL metadata stored with each document, exactly as the MCP
 * backends always have ({@link McpAclEvaluator}). Explicit deny wins, structured allow rules take precedence
 * over the legacy {@code acl} field, and a document with no ACL data at all is denied.
 */
public final class MetadataAuthorizationProvider implements AuthorizationProvider {

    public static final String NAME = "metadata";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
        boolean[] decisions = new boolean[candidates.size()];
        for (int i = 0; i < decisions.length; i++) {
            ResourceRef candidate = candidates.get(i);
            decisions[i] = candidate != null
                    && McpAclEvaluator.isAccessible(candidate.metadata(), caller.principal(), caller.groups());
        }
        return decisions;
    }
}
