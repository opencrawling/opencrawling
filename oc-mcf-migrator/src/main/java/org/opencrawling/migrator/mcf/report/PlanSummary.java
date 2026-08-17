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

/**
 * {@code compatibilityScorePercentage} is deliberately based on jobs, not connections — a
 * connection migrating in isolation has no end-user value until a job actually uses it, so jobs
 * are the more honest unit to score against. Matches the "compatibility_score_percentage" concept
 * from opencrawling/opencrawling#96's proposed audit report.
 */
public record PlanSummary(
    int connectionsTotal,
    int connectionsMigrated,
    int jobsTotal,
    int jobsMigrated,
    int compatibilityScorePercentage
) {
}
