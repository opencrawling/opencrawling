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

import org.opencrawling.migrator.mcf.engine.ConnectionPlanEntry;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;

/**
 * The short summary printed to stdout — full detail (field notes, apply results) lives only in
 * the file written by {@link MarkdownReportRenderer}, kept out of the console so it stays scannable.
 */
public class ConsoleReportRenderer implements ReportRenderer {

    private final String reportFilePath;

    public ConsoleReportRenderer(String reportFilePath) {
        this.reportFilePath = reportFilePath;
    }

    @Override
    public String render(MigrationReport report) {
        MigrationPlan plan = report.plan();
        long connectionsMigrated = plan.connections().stream().filter(e -> e.mapping().supported()).count();
        long jobsMigrated = plan.jobs().stream().filter(e -> e.mapping().supported()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("ManifoldCF -> OpenCrawling migration (").append(report.applyMode() ? "APPLY" : "DRY-RUN")
            .append(")\n");
        sb.append("  Connections: ").append(connectionsMigrated).append('/').append(plan.connections().size())
            .append(" migrated\n");
        sb.append("  Jobs:        ").append(jobsMigrated).append('/').append(plan.jobs().size())
            .append(" migrated\n");

        for (ConnectionPlanEntry entry : plan.connections()) {
            if (!entry.mapping().supported()) {
                sb.append("  SKIP connection '").append(entry.source().name()).append("': ")
                    .append(entry.mapping().unsupportedReason()).append('\n');
            }
        }
        for (JobPlanEntry entry : plan.jobs()) {
            if (!entry.mapping().supported()) {
                sb.append("  SKIP job '").append(entry.source().description()).append("': ")
                    .append(entry.mapping().unsupportedReason()).append('\n');
            }
        }

        sb.append("Full report written to: ").append(reportFilePath).append('\n');
        return sb.toString();
    }
}
