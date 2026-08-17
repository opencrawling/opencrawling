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
package org.opencrawling.migrator.mcf.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-item outcome of an {@code --apply} run, keyed by source connection name / job description
 * (ManifoldCF's human-readable job name). There is no rollback concept on the OpenCrawling side —
 * {@code MigrationEngine.apply} continues past a failed item rather than aborting the batch, so
 * this can legitimately hold a mix of successes and failures.
 */
public record ApplyOutcome(Map<String, ApplyResult> connectionResults, Map<String, ApplyResult> jobResults) {

    public ApplyOutcome {
        connectionResults = connectionResults == null ? Map.of() : new LinkedHashMap<>(connectionResults);
        jobResults = jobResults == null ? Map.of() : new LinkedHashMap<>(jobResults);
    }

    public record ApplyResult(boolean success, String detail) {
    }
}
