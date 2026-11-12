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
package org.opencrawling.runtime.governance;

import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.GovernanceUnavailableException;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.opencrawling.core.ois.OisMetadata;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the configured governance hooks over a document, in order, before it is chunked.
 *
 * <p>A sanitized version is what the next hook sees. The first rejection ends the evaluation. A hook that fails or
 * gives no answer makes the result {@link Status#UNAVAILABLE}, and the document is not embedded. A sanitized text
 * that is blank is a rejection, since embedding nothing would leave the previous version searchable.
 *
 * <p>Metadata keys that carry permissions or identity ({@link #isProtectedKey}) always keep the values the source
 * gave them: a hook can neither change, add nor remove them. That includes the OIS {@code ois:source},
 * {@code ois:provenance} and {@code ois:governance} blocks, so a hook cannot forge who ingested a document or what
 * governance decided.
 */
public final class GovernanceGate {

    private static final Logger log = LoggerFactory.getLogger(GovernanceGate.class);

    private static final Set<String> PROTECTED_KEYS = Set.of("acl", "security", "sys_acl", "documentId", "uri",
            "lastModified", OisMetadata.SOURCE, OisMetadata.PROVENANCE, OisMetadata.GOVERNANCE);
    private static final int MAX_REASON_LENGTH = 200;
    private static final int MAX_AUDIT_ENTRIES = 32;
    private static final int MAX_AUDIT_VALUE_LENGTH = 200;

    /** What the gate decided. */
    public enum Status {
        /** Embed the document as it is. */
        APPROVED,
        /** Embed the result's text and metadata. */
        SANITIZED,
        /** Embed nothing; see {@link GovernanceGate#purgeOnReject()} for the earlier version. */
        REJECTED,
        /** No decision could be made; embed nothing and leave the earlier version alone. */
        UNAVAILABLE
    }

    /**
     * The gate's result.
     *
     * @param text     what to chunk (for {@code SANITIZED}, the sanitized text)
     * @param metadata what to store (for {@code SANITIZED}, the sanitized metadata)
     * @param decidedBy for {@code REJECTED} and {@code UNAVAILABLE}, the hook that ended the evaluation
     * @param reason   for {@code REJECTED} and {@code UNAVAILABLE}, why (bounded, safe to log)
     * @param audit    the hooks' audit facts, keyed {@code hook.key}
     */
    public record Result(Status status, String text, Map<String, List<String>> metadata, String decidedBy,
                         String reason, Map<String, String> audit) {

        public boolean embeds() {
            return status == Status.APPROVED || status == Status.SANITIZED;
        }
    }

    private final List<PreEmbeddingGovernanceHook> hooks;
    private final GovernanceProperties.OnRejectedUpdate onRejectedUpdate;

    public GovernanceGate(List<PreEmbeddingGovernanceHook> hooks, GovernanceProperties.OnRejectedUpdate onRejectedUpdate) {
        this.hooks = List.copyOf(hooks);
        if (this.hooks.isEmpty()) {
            throw new IllegalArgumentException("The governance gate needs at least one hook.");
        }
        this.onRejectedUpdate = Objects.requireNonNull(onRejectedUpdate, "onRejectedUpdate");
    }

    /** Whether a rejected document's earlier chunks are removed. */
    public boolean purgeOnReject() {
        return onRejectedUpdate == GovernanceProperties.OnRejectedUpdate.PURGE;
    }

    /** The hooks' names, in the order they run. */
    public List<String> hookNames() {
        return hooks.stream().map(PreEmbeddingGovernanceHook::name).toList();
    }

    /** Whether a metadata key carries permissions or identity, so that no hook may touch it. */
    public static boolean isProtectedKey(String key) {
        return key != null && (PROTECTED_KEYS.contains(key) || key.startsWith("security_") || key.startsWith("acl_")
                || OisMetadata.isOisBlockKey(key));
    }

    public Result evaluate(String documentId, String uri, String text, Map<String, List<String>> metadata,
                           SecurityConfig security) {
        Map<String, List<String>> source = metadata != null ? metadata : Map.of();
        String currentText = text != null ? text : "";
        Map<String, List<String>> currentMetadata = source;
        boolean sanitized = false;
        Map<String, String> audit = new LinkedHashMap<>();
        for (PreEmbeddingGovernanceHook hook : hooks) {
            String name = hook.name();
            GovernanceDecision decision;
            try {
                decision = hook.evaluate(new GovernanceSubject(documentId, uri, currentText, currentMetadata, security));
            } catch (GovernanceUnavailableException e) {
                return unavailable(name, e.getMessage(), audit);
            } catch (RuntimeException e) {
                log.warn("Governance hook {} failed on document {}", name, documentId, e);
                return unavailable(name, "the hook failed (" + e.getClass().getSimpleName() + ")", audit);
            }
            if (decision == null) {
                return unavailable(name, "the hook gave no decision", audit);
            }
            addAudit(audit, name, decision.audit());
            switch (decision.outcome()) {
                case APPROVE -> { }
                case SANITIZE -> {
                    currentText = decision.text();
                    if (decision.metadata() != null) {
                        currentMetadata = withProtectedKeys(decision.metadata(), source);
                    }
                    sanitized = true;
                }
                case REJECT -> {
                    return new Result(Status.REJECTED, null, null, name,
                            bounded(decision.reason() != null ? decision.reason() : "rejected"), Map.copyOf(audit));
                }
            }
        }
        if (sanitized && currentText.isBlank()) {
            String last = hooks.getLast().name();
            return new Result(Status.REJECTED, null, null, last, "nothing was left after sanitizing", Map.copyOf(audit));
        }
        return new Result(sanitized ? Status.SANITIZED : Status.APPROVED, currentText, currentMetadata, null, null,
                Map.copyOf(audit));
    }

    private static Result unavailable(String hook, String reason, Map<String, String> audit) {
        return new Result(Status.UNAVAILABLE, null, null, hook, bounded(reason != null ? reason : "unavailable"),
                Map.copyOf(audit));
    }

    /** The proposed metadata without protected keys, with the source's protected keys put back. */
    static Map<String, List<String>> withProtectedKeys(Map<String, List<String>> proposed, Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        proposed.forEach((key, values) -> {
            if (!isProtectedKey(key)) {
                result.put(key, values);
            }
        });
        source.forEach((key, values) -> {
            if (isProtectedKey(key)) {
                result.put(key, values);
            }
        });
        return result;
    }

    private static void addAudit(Map<String, String> audit, String hook, Map<String, String> facts) {
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            if (audit.size() >= MAX_AUDIT_ENTRIES) {
                return;
            }
            if (fact.getKey() != null && fact.getValue() != null) {
                audit.put(hook + "." + fact.getKey(), bounded(fact.getValue(), MAX_AUDIT_VALUE_LENGTH));
            }
        }
    }

    private static String bounded(String value) {
        return bounded(value, MAX_REASON_LENGTH);
    }

    private static String bounded(String value, int max) {
        String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    @Override
    public String toString() {
        return "GovernanceGate" + hookNames() + " onRejectedUpdate=" + onRejectedUpdate.name().toLowerCase(Locale.ROOT);
    }
}
