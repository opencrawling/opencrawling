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

import java.util.List;

/**
 * Serialization-friendly, front-end-agnostic view of one {@code ConnectionPlanEntry} — shared by
 * the CLI's JSON report and {@code oc-runtime}'s REST responses so both stay in sync automatically.
 *
 * <p>{@code overrideTargetName} is non-null only when this connection was manually redirected via
 * {@code --map-connector} rather than auto-mapped — {@code targetType}/{@code targetClass} stay
 * null in that case since nothing was actually created and this tool has no visibility into the
 * pre-existing target connector's class.
 */
public record ConnectionSummary(
    String name,
    String manifoldClass,
    boolean supported,
    String targetType,
    String targetClass,
    String reason,
    String recommendedAction,
    List<NoteSummary> notes,
    String overrideTargetName
) {
}
