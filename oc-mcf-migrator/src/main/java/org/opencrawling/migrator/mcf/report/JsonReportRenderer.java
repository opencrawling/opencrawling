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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome;

import java.util.List;
import java.util.Map;

/**
 * A structured, machine-readable alternative to {@link MarkdownReportRenderer}, matching the
 * shape opencrawling/opencrawling#96 proposes for its audit report: a numeric summary (including
 * {@code compatibilityScorePercentage}) plus per-item {@code recommendedAction} text — see
 * {@link MigrationReportData} for where those are actually computed.
 */
public class JsonReportRenderer implements ReportRenderer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String render(MigrationReport report) {
        Payload payload = new Payload(
            report.generatedAt(),
            report.applyMode() ? "APPLY" : "DRY_RUN",
            report.manifoldCfUrl(),
            report.openCrawlingUrl(),
            MigrationReportData.summary(report.plan()),
            MigrationReportData.connectionSummaries(report.plan()),
            MigrationReportData.jobSummaries(report.plan()),
            report.applyOutcome() != null ? MigrationReportData.applyResultSummaries(report.applyOutcome().connectionResults()) : null,
            report.applyOutcome() != null ? MigrationReportData.applyResultSummaries(report.applyOutcome().jobResults()) : null
        );

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render JSON report", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payload(
        String generatedAt,
        String mode,
        String source,
        String target,
        PlanSummary summary,
        List<ConnectionSummary> connections,
        List<JobSummary> jobs,
        Map<String, ApplyResultSummary> connectionResults,
        Map<String, ApplyResultSummary> jobResults
    ) {
    }
}
