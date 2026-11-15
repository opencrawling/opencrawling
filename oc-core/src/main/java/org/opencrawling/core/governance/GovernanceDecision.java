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
package org.opencrawling.core.governance;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link PreEmbeddingGovernanceHook}'s answer about one document.
 *
 * @param outcome  approve as is, approve a sanitized version, or reject
 * @param text     for {@link Outcome#SANITIZE}, the text to embed instead; otherwise {@code null}
 * @param metadata for {@link Outcome#SANITIZE}, the metadata to store instead ({@code null} keeps it as it was);
 *                 otherwise {@code null}. Keys that carry permissions or identity are never taken from it.
 * @param reason   for {@link Outcome#REJECT}, why; shown in traces and logs, so it must not repeat what was found
 * @param audit    short facts for the audit trail (for example, how many items were masked); never the content
 */
public record GovernanceDecision(Outcome outcome, String text, Map<String, List<String>> metadata, String reason,
                                 Map<String, String> audit) {

    /** What happens to the document. */
    public enum Outcome {
        /** Embed the document as it is. */
        APPROVE,
        /** Embed the decision's text and metadata instead. */
        SANITIZE,
        /** Embed nothing. */
        REJECT
    }

    public GovernanceDecision {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.SANITIZE && text == null) {
            throw new IllegalArgumentException("A SANITIZE decision needs the sanitized text.");
        }
        if (outcome != Outcome.SANITIZE) {
            text = null;
            metadata = null;
        } else {
            metadata = metadata != null ? GovernanceSubject.copy(metadata) : null;
        }
        if (outcome != Outcome.REJECT) {
            reason = null;
        }
        audit = audit != null ? Map.copyOf(audit) : Map.of();
    }

    public static GovernanceDecision approve() {
        return new GovernanceDecision(Outcome.APPROVE, null, null, null, Map.of());
    }

    public static GovernanceDecision approve(Map<String, String> audit) {
        return new GovernanceDecision(Outcome.APPROVE, null, null, null, audit);
    }

    public static GovernanceDecision sanitize(String text, Map<String, List<String>> metadata, Map<String, String> audit) {
        return new GovernanceDecision(Outcome.SANITIZE, text, metadata, null, audit);
    }

    public static GovernanceDecision reject(String reason, Map<String, String> audit) {
        return new GovernanceDecision(Outcome.REJECT, null, null, reason, audit);
    }
}
