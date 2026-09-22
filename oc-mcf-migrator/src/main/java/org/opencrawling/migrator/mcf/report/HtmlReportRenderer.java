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
 * A self-contained, single-file HTML report — inline CSS only, no external stylesheet/script/font
 * dependency, so it opens correctly from disk with no server and nothing to fetch. Same content as
 * {@link MarkdownReportRenderer}, matching opencrawling/opencrawling#96's proposed
 * {@code --report-format html}.
 */
public class HtmlReportRenderer implements ReportRenderer {

    private static final String STYLE = """
        body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem;color:#1a1a2e;line-height:1.5}
        h1{font-size:1.6rem;border-bottom:2px solid #06b6d4;padding-bottom:.5rem}
        h2{font-size:1.2rem;margin-top:2rem;color:#0f172a}
        h3{font-size:1rem;color:#334155}
        table{border-collapse:collapse;width:100%;margin:.75rem 0 1.5rem;font-size:.9rem}
        th,td{border:1px solid #e2e8f0;padding:.4rem .6rem;text-align:left;vertical-align:top}
        th{background:#f1f5f9;font-weight:600}
        tr:nth-child(even){background:#f8fafc}
        .meta{color:#475569;font-size:.9rem}
        .badge{display:inline-block;padding:.1rem .5rem;border-radius:.4rem;font-size:.75rem;font-weight:600;text-transform:uppercase}
        .badge-runtime_risk{background:#fee2e2;color:#b91c1c}
        .badge-scope_change{background:#fef3c7;color:#92400e}
        .badge-dropped{background:#f1f5f9;color:#475569}
        .badge-defaulted{background:#dbeafe;color:#1e40af}
        .badge-converted{background:#cffafe;color:#0e7490}
        .success{color:#15803d;font-weight:600}
        .failed{color:#b91c1c;font-weight:600}
        .limitations{background:#f0f9ff;border:1px solid #bae6fd;border-radius:.5rem;padding:1rem 1.25rem}
        """;

    @Override
    public String render(MigrationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            .append("<title>ManifoldCF → OpenCrawling Migration Report</title>")
            .append("<style>").append(STYLE).append("</style></head><body>\n");

        sb.append("<h1>ManifoldCF → OpenCrawling Migration Report</h1>\n");
        sb.append("<p class=\"meta\">Generated: ").append(escape(report.generatedAt())).append("<br>")
            .append("Mode: ").append(report.applyMode() ? "APPLY" : "DRY-RUN").append("<br>")
            .append("Source (ManifoldCF): ").append(escape(report.manifoldCfUrl())).append("<br>")
            .append("Target (OpenCrawling): ").append(escape(report.openCrawlingUrl())).append("</p>\n");

        renderSummary(sb, report.plan());
        renderConnections(sb, report.plan().connections());
        renderJobs(sb, report.plan().jobs());
        renderFieldNotes(sb, report.plan());
        renderLimitations(sb);
        if (report.applyOutcome() != null) {
            renderApplyResults(sb, report.applyOutcome());
        }

        sb.append("</body></html>\n");
        return sb.toString();
    }

    private void renderSummary(StringBuilder sb, MigrationPlan plan) {
        sb.append("<h2>Summary</h2>\n<table><tr><th>Category</th><th>Total</th><th>Migrated</th><th>Skipped</th></tr>\n");

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
            sb.append("<tr><td>").append(displayName(e.getKey())).append(" connections</td><td>").append(total)
                .append("</td><td>").append(migrated).append("</td><td>").append(total - migrated).append("</td></tr>\n");
        }

        long jobsMigrated = plan.jobs().stream().filter(j -> j.mapping().supported()).count();
        sb.append("<tr><td>Jobs</td><td>").append(plan.jobs().size()).append("</td><td>").append(jobsMigrated)
            .append("</td><td>").append(plan.jobs().size() - jobsMigrated).append("</td></tr>\n</table>\n");
    }

    private void renderConnections(StringBuilder sb, List<ConnectionPlanEntry> connections) {
        sb.append("<h2>Connections</h2>\n<h3>Migrated</h3>\n<table><tr><th>Name</th><th>ManifoldCF class</th><th>OpenCrawling class</th></tr>\n");
        for (ConnectionPlanEntry entry : connections) {
            if (entry.mapping().supported()) {
                String targetClass = entry.mapping().overrideTargetName() != null
                    ? "<em>manually mapped to '" + escape(entry.mapping().overrideTargetName()) + "' via --map-connector</em>"
                    : escape(entry.mapping().target().className());
                sb.append("<tr><td>").append(escape(entry.source().name())).append("</td><td>")
                    .append(escape(entry.source().className())).append("</td><td>").append(targetClass).append("</td></tr>\n");
            }
        }
        sb.append("</table>\n<h3>Skipped</h3>\n<table><tr><th>Name</th><th>ManifoldCF class</th><th>Reason</th></tr>\n");
        for (ConnectionPlanEntry entry : connections) {
            if (!entry.mapping().supported()) {
                sb.append("<tr><td>").append(escape(entry.source().name())).append("</td><td>")
                    .append(escape(entry.source().className())).append("</td><td>")
                    .append(escape(entry.mapping().unsupportedReason())).append("</td></tr>\n");
            }
        }
        sb.append("</table>\n");
    }

    private void renderJobs(StringBuilder sb, List<JobPlanEntry> jobs) {
        sb.append("<h2>Jobs</h2>\n<h3>Migrated</h3>\n<table><tr><th>Name</th><th>Repository</th><th>Output</th><th>Transformation</th></tr>\n");
        for (JobPlanEntry entry : jobs) {
            if (entry.mapping().supported()) {
                var target = entry.mapping().target();
                sb.append("<tr><td>").append(escape(entry.source().description())).append("</td><td>")
                    .append(escape(target.repositoryConnector())).append("</td><td>").append(escape(target.outputConnector()))
                    .append("</td><td>").append(target.transformationConnector() != null ? escape(target.transformationConnector()) : "-")
                    .append("</td></tr>\n");
            }
        }
        sb.append("</table>\n<h3>Skipped</h3>\n<table><tr><th>Name</th><th>Blocking connector(s)</th><th>Reason</th></tr>\n");
        for (JobPlanEntry entry : jobs) {
            if (!entry.mapping().supported()) {
                String blocking = entry.mapping().blockingConnectors().isEmpty()
                    ? "-" : String.join(", ", entry.mapping().blockingConnectors());
                sb.append("<tr><td>").append(escape(entry.source().description())).append("</td><td>")
                    .append(escape(blocking)).append("</td><td>").append(escape(entry.mapping().unsupportedReason()))
                    .append("</td></tr>\n");
            }
        }
        sb.append("</table>\n");
    }

    private void renderFieldNotes(StringBuilder sb, MigrationPlan plan) {
        sb.append("<h2>Field-level warnings</h2>\n<p>Notes for connectors/jobs that migrated with partial fidelity. ")
            .append("<span class=\"badge badge-scope_change\">SCOPE_CHANGE</span> entries change actual crawl behavior, not just metadata; ")
            .append("<span class=\"badge badge-runtime_risk\">RUNTIME_RISK</span> entries mean the migrated item may not run correctly at all until verified.</p>\n")
            .append("<table><tr><th>Connector/Job</th><th>Field</th><th>Kind</th><th>Note</th></tr>\n");

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
            sb.append("<tr><td colspan=\"4\">(none)</td></tr>\n");
        }
        sb.append("</table>\n");
    }

    private void appendNoteRow(StringBuilder sb, String subject, FieldNote note) {
        sb.append("<tr><td>").append(escape(subject)).append("</td><td>").append(escape(note.field())).append("</td><td>")
            .append("<span class=\"badge badge-").append(note.kind().name().toLowerCase()).append("\">")
            .append(note.kind().name()).append("</span></td><td>").append(escape(note.message())).append("</td></tr>\n");
    }

    private void renderLimitations(StringBuilder sb) {
        sb.append("<h2>Known target-system limitations</h2>\n<div class=\"limitations\">");
        sb.append("<p>These are properties of the current OpenCrawling reference implementation, not bugs in this "
            + "migration tool — verified directly against its source:</p><ul>");
        sb.append("<li><strong>Dynamic connector resolution is narrow.</strong> At job-start time, <code>JobController</code> only "
            + "resolves repository connectors whose class name contains \"Alfresco\" or \"Iceberg\", and output "
            + "connectors whose class name contains \"Qdrant\" or \"Vespa\" — everything else silently falls "
            + "back to the default configured beans. A migrated job whose connectors fall outside those four "
            + "will be created successfully but may not run against the connector you expect.</li>");
        sb.append("<li><strong>No scheduler.</strong> Jobs only run via a manual/API <code>start</code> call; ManifoldCF schedule, "
            + "hopcount, recrawl-interval and reseed settings have no equivalent and are always dropped.</li>");
        sb.append("<li><strong>No filesystem filtering.</strong> <code>FileSystemRepositoryConnector</code> scans every file under its "
            + "root unconditionally — any ManifoldCF include/exclude filters are dropped (flagged above as "
            + "SCOPE_CHANGE, not just DROPPED).</li>");
        sb.append("<li><strong>Connector names are assumed stable.</strong> This tool preserves ManifoldCF connection names "
            + "verbatim as OpenCrawling connector names (unless manually overridden); job references rely on that.</li>");
        sb.append("</ul></div>\n");
    }

    private void renderApplyResults(StringBuilder sb, ApplyOutcome outcome) {
        sb.append("<h2>Apply Results</h2>\n<h3>Connections</h3>\n<table><tr><th>Name</th><th>Result</th><th>Detail</th></tr>\n");
        appendResultRows(sb, outcome.connectionResults());
        sb.append("</table>\n<h3>Jobs</h3>\n<table><tr><th>Name</th><th>Result</th><th>Detail</th></tr>\n");
        appendResultRows(sb, outcome.jobResults());
        sb.append("</table>\n");
    }

    private void appendResultRows(StringBuilder sb, Map<String, ApplyResult> results) {
        for (Map.Entry<String, ApplyResult> entry : results.entrySet()) {
            String cssClass = entry.getValue().success() ? "success" : "failed";
            sb.append("<tr><td>").append(escape(entry.getKey())).append("</td><td class=\"").append(cssClass).append("\">")
                .append(entry.getValue().success() ? "SUCCESS" : "FAILED").append("</td><td>")
                .append(escape(entry.getValue().detail())).append("</td></tr>\n");
        }
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

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
