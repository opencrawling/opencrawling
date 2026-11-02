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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    /** Default multiple of the requested results fetched when the provider may reject stored-ACL matches. */
    public static final int DEFAULT_OVERFETCH_FACTOR = 10;
    /** Default cap on candidates fetched for one request in those modes. */
    public static final int DEFAULT_CANDIDATE_BUDGET = 500;

    private final AuthorizationProvider provider;
    private final int overfetchFactor;
    private final int candidateBudget;
    private final Timer checkTimer;
    private final Counter candidatesCounter;
    private final Counter deniedCounter;
    private final Counter errorCounter;

    public DocumentAccessGate(AuthorizationProvider provider) {
        this(provider, DEFAULT_OVERFETCH_FACTOR, DEFAULT_CANDIDATE_BUDGET, null);
    }

    /**
     * @param overfetchFactor how many times the requested results to fetch when the provider is not
     *                        metadata-prefilter safe
     * @param candidateBudget the most candidates to fetch for one request in that case
     * @param meterRegistry   where to publish {@code oc.authz.*} metrics, or {@code null}
     */
    public DocumentAccessGate(AuthorizationProvider provider, int overfetchFactor, int candidateBudget,
                              MeterRegistry meterRegistry) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.overfetchFactor = Math.max(1, overfetchFactor);
        this.candidateBudget = Math.max(1, candidateBudget);
        if (meterRegistry != null) {
            String name = provider.name();
            this.checkTimer = Timer.builder("oc.authz.check").description("Time to decide one batch of candidates")
                    .tag("provider", name).register(meterRegistry);
            this.candidatesCounter = Counter.builder("oc.authz.candidates").tag("provider", name).register(meterRegistry);
            this.deniedCounter = Counter.builder("oc.authz.denied").tag("provider", name).register(meterRegistry);
            this.errorCounter = Counter.builder("oc.authz.errors").description("Requests denied because the provider failed")
                    .tag("provider", name).register(meterRegistry);
        } else {
            this.checkTimer = null;
            this.candidatesCounter = null;
            this.deniedCounter = null;
            this.errorCounter = null;
        }
    }

    /**
     * Whether a store may filter by the stored ACL metadata inside its own query. False when the provider can
     * allow documents the stored metadata does not show (relationship modes), in which case the store must
     * return unfiltered candidates and leave the decision to the gate.
     */
    public boolean storePrefilterAllowed() {
        return provider.metadataPrefilterSafe();
    }

    /**
     * How many candidates a backend should fetch for {@code limit} results.
     *
     * @param limit          the results the caller asked for
     * @param metadataWindow what the backend fetches when stored metadata decides
     */
    public int candidateWindow(int limit, int metadataWindow) {
        if (provider.metadataPrefilterSafe()) {
            return metadataWindow;
        }
        long wanted = (long) Math.max(limit, 1) * overfetchFactor;
        return (int) Math.max(metadataWindow, Math.min(wanted, candidateBudget));
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
        if (candidatesCounter != null) {
            candidatesCounter.increment(candidates.size());
        }
        for (int i = 0; i < decisions.length; i++) {
            if (decisions[i]) {
                allowed.add(candidates.get(i));
            }
        }
        if (deniedCounter != null) {
            deniedCounter.increment(candidates.size() - allowed.size());
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
        long start = System.nanoTime();
        try {
            decisions = provider.canRead(caller, refs);
        } catch (AuthorizationUnavailableException e) {
            countError();
            throw e;
        } catch (RuntimeException e) {
            countError();
            throw new AuthorizationUnavailableException(
                    "Authorization provider '" + provider.name() + "' failed; access denied.", e);
        } finally {
            if (checkTimer != null) {
                checkTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
        if (decisions == null || decisions.length != refs.size()) {
            countError();
            throw new AuthorizationUnavailableException("Authorization provider '" + provider.name()
                    + "' returned " + (decisions == null ? "no" : decisions.length) + " decisions for "
                    + refs.size() + " candidates; access denied.");
        }
        return decisions;
    }

    private void countError() {
        if (errorCounter != null) {
            errorCounter.increment();
        }
    }
}
