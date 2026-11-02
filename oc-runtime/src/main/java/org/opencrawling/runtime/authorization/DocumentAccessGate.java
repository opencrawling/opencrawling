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
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The single point every MCP retrieval backend passes its candidates through before returning them.
 *
 * <p>Backends fetch candidates from their store, map each one to a {@link ResourceRef} and keep only what the
 * gate returns. The gate asks the configured {@link AuthorizationProvider} once per batch and fails closed: a
 * provider error or a malformed answer denies the whole request.
 */
public final class DocumentAccessGate {

    private static final Logger log = LoggerFactory.getLogger(DocumentAccessGate.class);

    private static final DocumentAccessGate METADATA_DEFAULT = new DocumentAccessGate(new MetadataAuthorizationProvider());

    private final AuthorizationProvider provider;

    public DocumentAccessGate(AuthorizationProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * The gate backends use when none is injected (tests and programmatic construction): metadata decisions,
     * identical to the behaviour before the gate existed.
     */
    public static DocumentAccessGate metadataDefault() {
        return METADATA_DEFAULT;
    }

    public AuthorizationProvider provider() {
        return provider;
    }

    /**
     * Keeps the candidates the caller may read, in their original order.
     *
     * @param caller     the caller
     * @param candidates the store's candidates
     * @param toRef      maps a candidate to the reference the provider decides on
     * @return the readable candidates
     * @throws AuthorizationUnavailableException when the provider cannot decide
     */
    public <T> List<T> filter(Caller caller, List<T> candidates, Function<T, ResourceRef> toRef) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ResourceRef> refs = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            refs.add(toRef.apply(candidate));
        }
        boolean[] decisions = decide(caller, refs);
        List<T> allowed = new ArrayList<>();
        for (int i = 0; i < decisions.length; i++) {
            if (decisions[i]) {
                allowed.add(candidates.get(i));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Authorization ({}) kept {} of {} candidates for principal '{}'.",
                    provider.name(), allowed.size(), candidates.size(), caller.principal());
        }
        return allowed;
    }

    /**
     * Decides read access for a single document.
     *
     * @throws AuthorizationUnavailableException when the provider cannot decide
     */
    public boolean canRead(Caller caller, ResourceRef ref) {
        return decide(caller, List.of(ref))[0];
    }

    private boolean[] decide(Caller caller, List<ResourceRef> refs) {
        boolean[] decisions;
        try {
            decisions = provider.canRead(caller, refs);
        } catch (AuthorizationUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AuthorizationUnavailableException(
                    "Authorization provider '" + provider.name() + "' failed; access denied.", e);
        }
        if (decisions == null || decisions.length != refs.size()) {
            throw new AuthorizationUnavailableException("Authorization provider '" + provider.name()
                    + "' returned " + (decisions == null ? "no" : decisions.length) + " decisions for "
                    + refs.size() + " candidates; access denied.");
        }
        return decisions;
    }
}
