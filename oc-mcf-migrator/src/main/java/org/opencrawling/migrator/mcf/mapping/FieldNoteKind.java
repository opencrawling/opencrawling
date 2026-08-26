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
package org.opencrawling.migrator.mcf.mapping;

/**
 * Why a single source field ended up the way it did in an otherwise-supported migration. These
 * are informational, not disqualifying — a connector/job with a registered mapper always migrates;
 * these notes explain the fidelity loss.
 */
public enum FieldNoteKind {
    /** Source field has no target equivalent; simply omitted. */
    DROPPED,
    /** Target field has no source equivalent; a chosen default was written instead. */
    DEFAULTED,
    /** Source value was transformed (renamed, unit-converted) on its way to the target. */
    CONVERTED,
    /**
     * The dropped field would have changed actual crawl/runtime *behavior*, not just metadata —
     * e.g. ManifoldCF include/exclude filters, dropped because the target connector scans
     * unconditionally. Rendered more prominently than the other kinds.
     */
    SCOPE_CHANGE,
    /**
     * Not about a field at all — a warning that the migrated connector/job may not actually
     * function correctly once started, because it falls outside OpenCrawling's current narrow
     * dynamic-connector-resolution coverage (verified in {@code JobController.startJob()}: only
     * Alfresco/Iceberg repositories and Qdrant/Vespa outputs get resolved to the connector you
     * actually configured — everything else silently falls back to a default bean). Rendered at
     * least as prominently as {@link #SCOPE_CHANGE}.
     */
    RUNTIME_RISK
}
