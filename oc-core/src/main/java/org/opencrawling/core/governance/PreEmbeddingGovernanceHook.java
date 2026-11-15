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

/**
 * Decides, before a document is chunked and embedded, whether it may be embedded, and in what form.
 *
 * <p>Hooks run in the ingestion consumer, once per document, in the order they are configured. A
 * {@link GovernanceDecision.Outcome#SANITIZE SANITIZE} result is what the next hook sees; a
 * {@link GovernanceDecision.Outcome#REJECT REJECT} ends the evaluation. A hook that cannot decide throws
 * {@link GovernanceUnavailableException}, and the document is not embedded (fail closed).
 *
 * <p>A hook never changes a document's permissions: the decision has no field for them, and metadata keys that
 * carry permissions or identity are kept as the source gave them.
 */
public interface PreEmbeddingGovernanceHook {

    /** A short name for traces, logs and the audit trail, such as {@code pii-masking}. */
    String name();

    /**
     * @throws GovernanceUnavailableException when no decision can be made (the document is then not embedded)
     */
    GovernanceDecision evaluate(GovernanceSubject subject) throws GovernanceUnavailableException;
}
