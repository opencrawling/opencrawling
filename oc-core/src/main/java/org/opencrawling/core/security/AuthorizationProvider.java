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
package org.opencrawling.core.security;

import java.util.List;

/**
 * Decides which candidate documents a caller may read.
 *
 * <p>Every MCP retrieval backend hands its candidates to one gate, and the gate asks the configured provider.
 * The call is a batch so that a remote engine (for example OpenFGA) costs one round trip per page of results,
 * not one per document.
 *
 * <p>Implementations must fail closed: a candidate whose decision cannot be made is denied, and an engine that
 * cannot be reached raises {@link AuthorizationUnavailableException} instead of falling back to another rule.
 */
public interface AuthorizationProvider {

    /**
     * The provider's name, as used in logs and metrics (for example {@code metadata} or {@code openfga}).
     */
    String name();

    /**
     * Decides read access for each candidate.
     *
     * @param caller     the caller the decision is made for
     * @param candidates the candidate documents, in the order the store ranked them
     * @return one decision per candidate, in the same order; {@code true} means the caller may read it
     * @throws AuthorizationUnavailableException when the decision cannot be made at all
     */
    boolean[] canRead(Caller caller, List<ResourceRef> candidates);

    /**
     * Decides read access for a single document.
     */
    default boolean canRead(Caller caller, ResourceRef candidate) {
        return canRead(caller, List.of(candidate))[0];
    }

    /**
     * Whether a store may pre-filter candidates by their stored ACL metadata before calling this provider
     * without hiding documents the provider would allow. True for the metadata provider; false for a provider
     * whose decisions can grant access the stored metadata does not show (for example group membership kept
     * in a relationship store).
     */
    default boolean metadataPrefilterSafe() {
        return true;
    }
}
