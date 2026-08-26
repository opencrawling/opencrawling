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
package org.opencrawling.migrator.mcf.report;

import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;

/**
 * Turns a {@link FieldNoteKind} or a skip reason into a short, human-actionable next step — the
 * "recommended_action" concept from opencrawling/opencrawling#96's proposed audit report shape.
 * Shared between the CLI's structured JSON report and the {@code oc-runtime} REST responses so the
 * two front ends never drift on wording.
 */
public final class RecommendedActions {

    private RecommendedActions() {
    }

    public static String forNote(FieldNoteKind kind) {
        return switch (kind) {
            case DROPPED -> "No action needed unless this specific behavior is required — there is no target field for it.";
            case DEFAULTED -> "Review the defaulted value; override the relevant --default-* flag if it doesn't match your deployment.";
            case CONVERTED -> "No action needed — the value was translated automatically.";
            case SCOPE_CHANGE -> "Review the target's actual crawl scope before relying on this job in production; its behavior differs from the original ManifoldCF job.";
            case RUNTIME_RISK -> "Start the migrated job and verify it resolves the intended connector — OpenCrawling's dynamic connector resolution doesn't cover this combination yet, and it may fall back to a default you didn't intend.";
        };
    }

    public static String forUnsupportedConnector() {
        return "Contribute a ConnectorMapper for this class (see the README's Extending section), or migrate this connection manually.";
    }

    public static String forUnsupportedJob(String reason) {
        if (reason != null && reason.contains("output stage(s)")) {
            return "OpenCrawling supports exactly one output connector per job — split this job or choose a single output manually.";
        }
        return "Resolve the blocking connector(s) listed above first (see their own recommended action), or migrate this job manually.";
    }
}
