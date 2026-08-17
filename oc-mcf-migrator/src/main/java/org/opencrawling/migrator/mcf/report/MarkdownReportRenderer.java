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

import org.opencrawling.migrator.mcf.engine.ApplyOutcome;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome.ApplyResult;
import org.opencrawling.migrator.mcf.engine.ConnectionPlanEntry;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the full-detail report written to {@code --report-file}. Never touches a raw
 * ManifoldCF configuration value — every string in this output comes from names, class names,
 * and {@code FieldNote} messages, which callers are responsible for keeping secret-free (see
 * {@link SecretRedactor}; the mappers redact/omit at the source, so there is nothing left to
 * redact here).
 */
public class MarkdownReportRenderer implements ReportRenderer {

    @Override
    public String render(MigrationReport report) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ManifoldCF → OpenCrawling Migration Report\n\n");
        sb.append("- Generated: ").append(report.generatedAt()).append('\n');
        sb.append("- Mode: ").append(report.applyMode() ? "APPLY" : "DRY-RUN").append('\n');
        sb.append("- Source (ManifoldCF): ").append(report.manifoldCfUrl()).append('\n');
        sb.append("- Target (OpenCrawling): ").append(report.openCrawlingUrl()).append("\n\n");

        renderSummary(sb, report.plan());
        renderConnections(sb, report.plan().connections());
        renderJobs(sb, report.plan().jobs());
        renderFieldNotes(sb, report.plan());
        renderLimitations(sb);
        if (report.applyOutcome() != null) {
            renderApplyResults(sb, report.applyOutcome());
        }

        return sb.toString();
    }

    private void renderSummary(StringBuilder sb, MigrationPlan plan) {
        sb.append("## Summary\n\n");
        sb.append("| Category | Total | Migrated | Skipped |\n|---|---|---|---|\n");

        Map<McfConnectionKind, int[]> byKind = new LinkedHashMap<>();
        for (McfConnectionKind kind : McfConnectionKind.values()) {
            byKind.put(kind, new int[]{0, 0});
        }
        for (ConnectionPlanEntry entry : plan.connections()) {
            int[] counts = byKind.get(entry.source().kind());
            counts[0]++;
            if (entry.mapping().supported()) {
                counts[1]++;
            }
        }
        for (Map.Entry<McfConnectionKind, int[]> e : byKind.entrySet()) {
            int total = e.getValue()[0];
            if (total == 0) {
                continue;
            }
            int migrated = e.getValue()[1];
            sb.append("| ").append(displayName(e.getKey())).append(" connections | ").append(total)
                .append(" | ").append(migrated).append(" | ").append(total - migrated).append(" |\n");
        }

        long jobsMigrated = plan.jobs().stream().filter(j -> j.mapping().supported()).count();
        sb.append("| Jobs | ").append(plan.jobs().size()).append(" | ").append(jobsMigrated)
            .append(" | ").append(plan.jobs().size() - jobsMigrated).append(" |\n\n");
    }

    private void renderConnections(StringBuilder sb, List<ConnectionPlanEntry> connections) {
        sb.append("## Connections\n\n");

        sb.append("### Migrated\n\n");
        sb.append("| Name | ManifoldCF class | OpenCrawling class |\n|---|---|---|\n");
        for (ConnectionPlanEntry entry : connections) {
            if (entry.mapping().supported()) {
                String targetClass = entry.mapping().overrideTargetName() != null
                    ? "*(manually mapped to '" + escape(entry.mapping().overrideTargetName()) + "' via --map-connector)*"
                    : escape(entry.mapping().target().className());
                sb.append("| ").append(escape(entry.source().name())).append(" | ").append(escape(entry.source().className()))
                    .append(" | ").append(targetClass).append(" |\n");
            }
        }

        sb.append("\n### Skipped\n\n");
        sb.append("| Name | ManifoldCF class | Reason |\n|---|---|---|\n");
        for (ConnectionPlanEntry entry : connections) {
            if (!entry.mapping().supported()) {
                sb.append("| ").append(escape(entry.source().name())).append(" | ").append(escape(entry.source().className()))
                    .append(" | ").append(escape(entry.mapping().unsupportedReason())).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private void renderJobs(StringBuilder sb, List<JobPlanEntry> jobs) {
        sb.append("## Jobs\n\n");

        sb.append("### Migrated\n\n");
        sb.append("| Name | Repository | Output | Transformation |\n|---|---|---|---|\n");
        for (JobPlanEntry entry : jobs) {
            if (entry.mapping().supported()) {
                var target = entry.mapping().target();
                sb.append("| ").append(escape(entry.source().description())).append(" | ")
                    .append(escape(target.repositoryConnector())).append(" | ").append(escape(target.outputConnector()))
                    .append(" | ").append(target.transformationConnector() != null ? escape(target.transformationConnector()) : "-")
                    .append(" |\n");
            }
        }

        sb.append("\n### Skipped\n\n");
        sb.append("| Name | Blocking connector(s) | Reason |\n|---|---|---|\n");
        for (JobPlanEntry entry : jobs) {
            if (!entry.mapping().supported()) {
                String blocking = entry.mapping().blockingConnectors().isEmpty()
                    ? "-" : escape(String.join(", ", entry.mapping().blockingConnectors()));
                sb.append("| ").append(escape(entry.source().description())).append(" | ").append(blocking)
                    .append(" | ").append(escape(entry.mapping().unsupportedReason())).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private void renderFieldNotes(StringBuilder sb, MigrationPlan plan) {
        sb.append("## Field-level warnings\n\n");
        sb.append("Notes for connectors/jobs that migrated with partial fidelity. "
            + "**SCOPE_CHANGE** entries change actual crawl behavior, not just metadata; "
            + "**RUNTIME_RISK** entries mean the migrated item may not run correctly at all until verified.\n\n");
        sb.append("| Connector/Job | Field | Kind | Note |\n|---|---|---|---|\n");

        boolean any = false;
        for (ConnectionPlanEntry entry : plan.connections()) {
            for (FieldNote note : entry.mapping().notes()) {
                any = true;
                appendNoteRow(sb, entry.source().name(), note);
            }
        }
        for (JobPlanEntry entry : plan.jobs()) {
            if (!entry.mapping().supported()) {
                continue;
            }
            for (FieldNote note : entry.mapping().notes()) {
                any = true;
                appendNoteRow(sb, entry.source().description(), note);
            }
        }
        if (!any) {
            sb.append("| - | - | - | (none) |\n");
        }
        sb.append('\n');
    }

    private void appendNoteRow(StringBuilder sb, String subject, FieldNote note) {
        String kind = (note.kind() == FieldNoteKind.SCOPE_CHANGE || note.kind() == FieldNoteKind.RUNTIME_RISK)
            ? "**" + note.kind().name() + "**" : note.kind().name();
        sb.append("| ").append(escape(subject)).append(" | ").append(escape(note.field())).append(" | ").append(kind)
            .append(" | ").append(escape(note.message())).append(" |\n");
    }

    private void renderLimitations(StringBuilder sb) {
        sb.append("## Known target-system limitations\n\n");
        sb.append("These are properties of the current OpenCrawling reference implementation, not bugs in this "
            + "migration tool — verified directly against its source:\n\n");
        sb.append("- **Dynamic connector resolution is narrow.** At job-start time, `JobController` only "
            + "resolves repository connectors whose class name contains \"Alfresco\" or \"Iceberg\", and output "
            + "connectors whose class name contains \"Qdrant\" or \"Vespa\" — everything else silently falls "
            + "back to the default configured beans. A migrated job whose connectors fall outside those four "
            + "will be created successfully but may not run against the connector you expect.\n");
        sb.append("- **No scheduler.** Jobs only run via a manual/API `start` call; ManifoldCF schedule, "
            + "hopcount, recrawl-interval and reseed settings have no equivalent and are always dropped.\n");
        sb.append("- **No filesystem filtering.** `FileSystemRepositoryConnector` scans every file under its "
            + "root unconditionally — any ManifoldCF include/exclude filters are dropped (flagged above as "
            + "SCOPE_CHANGE, not just DROPPED).\n");
        sb.append("- **Connector names are assumed stable.** This tool preserves ManifoldCF connection names "
            + "verbatim as OpenCrawling connector names; job references rely on that.\n\n");
    }

    private void renderApplyResults(StringBuilder sb, ApplyOutcome outcome) {
        sb.append("## Apply Results\n\n");
        sb.append("### Connections\n\n| Name | Result | Detail |\n|---|---|---|\n");
        appendResultRows(sb, outcome.connectionResults());
        sb.append("\n### Jobs\n\n| Name | Result | Detail |\n|---|---|---|\n");
        appendResultRows(sb, outcome.jobResults());
        sb.append('\n');
    }

    private void appendResultRows(StringBuilder sb, Map<String, ApplyResult> results) {
        for (Map.Entry<String, ApplyResult> entry : results.entrySet()) {
            sb.append("| ").append(escape(entry.getKey())).append(" | ")
                .append(entry.getValue().success() ? "SUCCESS" : "FAILED").append(" | ")
                .append(escape(entry.getValue().detail())).append(" |\n");
        }
    }

    /** Escapes characters that would otherwise corrupt a Markdown table's `| ... |` cell structure. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    private static String displayName(McfConnectionKind kind) {
        return switch (kind) {
            case REPOSITORY -> "Repository";
            case OUTPUT -> "Output";
            case TRANSFORMATION -> "Transformation";
            case AUTHORITY -> "Authority";
            case MAPPING -> "Mapping";
            case NOTIFICATION -> "Notification";
        };
    }
}
