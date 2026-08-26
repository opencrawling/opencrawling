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
import org.opencrawling.migrator.mcf.engine.ConnectionPlanEntry;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.mapping.FieldNote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts engine types ({@code MigrationPlan}, {@code ApplyOutcome}) into the serialization-
 * friendly DTOs in this package, attaching a {@link RecommendedActions} next-step to every note
 * and skip reason along the way. The single place both the CLI's {@link JsonReportRenderer} and
 * {@code oc-runtime}'s REST responses go through, so they can never drift on this logic.
 */
public final class MigrationReportData {

    private MigrationReportData() {
    }

    public static List<ConnectionSummary> connectionSummaries(MigrationPlan plan) {
        return plan.connections().stream().map(MigrationReportData::toConnectionSummary).toList();
    }

    public static List<JobSummary> jobSummaries(MigrationPlan plan) {
        return plan.jobs().stream().map(MigrationReportData::toJobSummary).toList();
    }

    public static PlanSummary summary(MigrationPlan plan) {
        List<ConnectionSummary> connections = connectionSummaries(plan);
        List<JobSummary> jobs = jobSummaries(plan);
        long connectionsMigrated = connections.stream().filter(ConnectionSummary::supported).count();
        long jobsMigrated = jobs.stream().filter(JobSummary::supported).count();
        int score = jobs.isEmpty() ? 0 : (int) Math.round((jobsMigrated / (double) jobs.size()) * 100);
        return new PlanSummary(connections.size(), (int) connectionsMigrated, jobs.size(), (int) jobsMigrated, score);
    }

    public static Map<String, ApplyResultSummary> applyResultSummaries(Map<String, ApplyOutcome.ApplyResult> results) {
        Map<String, ApplyResultSummary> mapped = new LinkedHashMap<>();
        results.forEach((name, result) -> mapped.put(name, new ApplyResultSummary(result.success(), result.detail())));
        return mapped;
    }

    private static ConnectionSummary toConnectionSummary(ConnectionPlanEntry entry) {
        var mapping = entry.mapping();
        var target = mapping.target();
        return new ConnectionSummary(
            entry.source().name(),
            entry.source().className(),
            mapping.supported(),
            target != null ? target.type() : null,
            target != null ? target.className() : null,
            mapping.unsupportedReason(),
            mapping.supported() ? null : RecommendedActions.forUnsupportedConnector(),
            mapping.notes().stream().map(MigrationReportData::toNoteSummary).toList(),
            mapping.overrideTargetName()
        );
    }

    private static JobSummary toJobSummary(JobPlanEntry entry) {
        var mapping = entry.mapping();
        var target = mapping.supported() ? mapping.target() : null;
        return new JobSummary(
            entry.source().description(),
            mapping.supported(),
            target != null ? target.repositoryConnector() : null,
            target != null ? target.outputConnector() : null,
            target != null ? target.transformationConnector() : null,
            target != null ? target.path() : null,
            mapping.blockingConnectors(),
            mapping.unsupportedReason(),
            mapping.supported() ? null : RecommendedActions.forUnsupportedJob(mapping.unsupportedReason()),
            mapping.notes().stream().map(MigrationReportData::toNoteSummary).toList()
        );
    }

    private static NoteSummary toNoteSummary(FieldNote note) {
        return new NoteSummary(note.field(), note.kind().name(), note.message(), RecommendedActions.forNote(note.kind()));
    }
}
